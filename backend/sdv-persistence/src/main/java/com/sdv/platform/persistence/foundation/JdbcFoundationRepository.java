package com.sdv.platform.persistence.foundation;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.source.IngestionSourceAuthType;
import com.sdv.platform.domain.source.IngestionSourceStatus;
import com.sdv.platform.domain.source.SourceLimits;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantGuardrails;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcFoundationRepository implements TenantRepository, IngestionSourceRepository,
        MetadataFieldDefinitionRepository, AuditEventPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcFoundationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Tenant> findById(UUID tenantId) {
        return jdbc.query("""
                select tenant_id, tenant_key, display_name, status, default_retention_days,
                       max_pdf_bytes, max_pages_per_pdf, max_documents_per_submission,
                       max_aggregate_submission_bytes, incomplete_attention_hours,
                       storage_warning_percent, storage_stop_write_percent, created_at, updated_at
                  from tenants
                 where tenant_id = :tenantId
                """, params("tenantId", tenantId), tenantMapper()).stream().findFirst();
    }

    @Override
    public List<IngestionSource> findAll(UUID tenantId) {
        return jdbc.query(sourceSelect() + " where tenant_id = :tenantId order by source_key",
                params("tenantId", tenantId), sourceMapper());
    }

    @Override
    public Optional<IngestionSource> findById(UUID tenantId, UUID sourceId) {
        var p = params("tenantId", tenantId).addValue("sourceId", sourceId);
        return jdbc.query(sourceSelect() + " where tenant_id = :tenantId and source_id = :sourceId",
                p, sourceMapper()).stream().findFirst();
    }

    @Override
    public Optional<IngestionSource> findByKey(UUID tenantId, String sourceKey) {
        var p = params("tenantId", tenantId).addValue("sourceKey", sourceKey);
        return jdbc.query(sourceSelect() + " where tenant_id = :tenantId and source_key = :sourceKey",
                p, sourceMapper()).stream().findFirst();
    }

    @Override
    public void insert(IngestionSource source) {
        jdbc.update("""
                insert into ingestion_sources(
                    source_id, tenant_id, source_key, display_name, status, auth_type, credential_reference,
                    max_pdf_bytes, max_pages_per_pdf, max_documents_per_submission,
                    max_aggregate_submission_bytes, incomplete_attention_hours, created_at, updated_at)
                values (
                    :sourceId, :tenantId, :sourceKey, :displayName, :status, :authType, :credentialReference,
                    :maxPdfBytes, :maxPages, :maxDocuments, :maxAggregateBytes, :attentionHours, :createdAt, :updatedAt)
                """, sourceParams(source));
    }

    @Override
    public void update(IngestionSource source) {
        var p = sourceParams(source);
        int updated = jdbc.update("""
                update ingestion_sources
                   set display_name = :displayName,
                       status = :status,
                       auth_type = :authType,
                       credential_reference = :credentialReference,
                       max_pdf_bytes = :maxPdfBytes,
                       max_pages_per_pdf = :maxPages,
                       max_documents_per_submission = :maxDocuments,
                       max_aggregate_submission_bytes = :maxAggregateBytes,
                       incomplete_attention_hours = :attentionHours,
                       updated_at = :updatedAt
                 where tenant_id = :tenantId and source_id = :sourceId
                """, p);
        if (updated != 1) throw new IllegalStateException("Tenant-scoped ingestion source update affected " + updated + " rows.");
    }

    @Override
    public List<MetadataFieldDefinition> findCurrent(UUID tenantId, UUID sourceId) {
        var p = params("tenantId", tenantId).addValue("sourceId", sourceId);
        return jdbc.query(metadataSelect() + " where tenant_id=:tenantId and source_id=:sourceId and active=true order by field_key",
                p, metadataMapper());
    }

    @Override
    public List<MetadataFieldDefinition> findHistory(UUID tenantId, UUID sourceId, String fieldKey) {
        var p = params("tenantId", tenantId).addValue("sourceId", sourceId).addValue("fieldKey", fieldKey);
        return jdbc.query(metadataSelect() + " where tenant_id=:tenantId and source_id=:sourceId and field_key=:fieldKey order by version",
                p, metadataMapper());
    }

    @Override
    public Optional<MetadataFieldDefinition> findCurrent(UUID tenantId, UUID sourceId, String fieldKey) {
        var p = params("tenantId", tenantId).addValue("sourceId", sourceId).addValue("fieldKey", fieldKey);
        return jdbc.query(metadataSelect() + " where tenant_id=:tenantId and source_id=:sourceId and field_key=:fieldKey and active=true",
                p, metadataMapper()).stream().findFirst();
    }

    @Override
    public void retire(UUID tenantId, UUID sourceId, String fieldKey, Instant retiredAt) {
        var p = params("tenantId", tenantId).addValue("sourceId", sourceId).addValue("fieldKey", fieldKey)
                .addValue("retiredAt", Timestamp.from(retiredAt));
        int updated = jdbc.update("""
                update metadata_field_definitions
                   set active=false, retired_at=:retiredAt
                 where tenant_id=:tenantId and source_id=:sourceId and field_key=:fieldKey and active=true
                """, p);
        if (updated > 1) throw new IllegalStateException("More than one current metadata definition existed.");
    }

    @Override
    public void insert(MetadataFieldDefinition definition) {
        var p = new MapSqlParameterSource()
                .addValue("definitionId", definition.definitionId())
                .addValue("tenantId", definition.tenantId())
                .addValue("sourceId", definition.sourceId())
                .addValue("fieldKey", definition.fieldKey())
                .addValue("version", definition.version())
                .addValue("displayLabel", definition.displayLabel())
                .addValue("fieldType", definition.fieldType().name())
                .addValue("required", definition.required())
                .addValue("businessIdentifier", definition.businessIdentifier())
                .addValue("searchable", definition.searchable())
                .addValue("normalization", definition.normalization().name())
                .addValue("searchMode", definition.searchMode().name())
                .addValue("validationRules", definition.validationRulesJson())
                .addValue("active", definition.active())
                .addValue("effectiveAt", Timestamp.from(definition.effectiveAt()))
                .addValue("retiredAt", definition.retiredAt() == null ? null : Timestamp.from(definition.retiredAt()))
                .addValue("createdAt", Timestamp.from(definition.createdAt()));
        jdbc.update("""
                insert into metadata_field_definitions(
                    definition_id, tenant_id, source_id, field_key, version, display_label, field_type,
                    required, business_identifier, searchable, normalization, search_mode, validation_rules,
                    active, effective_at, retired_at, created_at)
                values (:definitionId, :tenantId, :sourceId, :fieldKey, :version, :displayLabel, :fieldType,
                    :required, :businessIdentifier, :searchable, :normalization, :searchMode, cast(:validationRules as jsonb),
                    :active, :effectiveAt, :retiredAt, :createdAt)
                """, p);
    }

    @Override
    public void append(AuditEvent event) {
        var p = new MapSqlParameterSource()
                .addValue("id", event.auditEventId())
                .addValue("tenantId", event.tenantId())
                .addValue("actorType", event.actorType())
                .addValue("actorId", event.actorId())
                .addValue("action", event.action())
                .addValue("targetType", event.targetType())
                .addValue("targetId", event.targetId())
                .addValue("occurredAt", Timestamp.from(event.occurredAt()))
                .addValue("correlationId", event.correlationId())
                .addValue("outcome", event.outcome().name())
                .addValue("safeDetails", event.safeDetailsJson());
        jdbc.update("""
                insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, action, target_type,
                                         target_id, occurred_at, correlation_id, outcome, safe_details)
                values (:id, :tenantId, :actorType, :actorId, :action, :targetType,
                        :targetId, :occurredAt, :correlationId, :outcome, cast(:safeDetails as jsonb))
                """, p);
    }

    private static String sourceSelect() {
        return """
                select source_id, tenant_id, source_key, display_name, status, auth_type, credential_reference,
                       max_pdf_bytes, max_pages_per_pdf, max_documents_per_submission,
                       max_aggregate_submission_bytes, incomplete_attention_hours, created_at, updated_at
                  from ingestion_sources
                """;
    }

    private static String metadataSelect() {
        return """
                select definition_id, tenant_id, source_id, field_key, version, display_label, field_type,
                       required, business_identifier, searchable, normalization, search_mode,
                       validation_rules::text validation_rules, active, effective_at, retired_at, created_at
                  from metadata_field_definitions
                """;
    }

    private static MapSqlParameterSource sourceParams(IngestionSource source) {
        SourceLimits l = source.limits();
        return new MapSqlParameterSource()
                .addValue("sourceId", source.sourceId())
                .addValue("tenantId", source.tenantId())
                .addValue("sourceKey", source.sourceKey())
                .addValue("displayName", source.displayName())
                .addValue("status", source.status().name())
                .addValue("authType", source.authType().name())
                .addValue("credentialReference", source.credentialReference())
                .addValue("maxPdfBytes", l == null ? null : l.maxPdfBytes())
                .addValue("maxPages", l == null ? null : l.maxPagesPerPdf())
                .addValue("maxDocuments", l == null ? null : l.maxDocumentsPerSubmission())
                .addValue("maxAggregateBytes", l == null ? null : l.maxAggregateSubmissionBytes())
                .addValue("attentionHours", l == null ? null : l.incompleteAttentionHours())
                .addValue("createdAt", Timestamp.from(source.createdAt()))
                .addValue("updatedAt", Timestamp.from(source.updatedAt()));
    }

    private static MapSqlParameterSource params(String key, Object value) {
        return new MapSqlParameterSource().addValue(key, value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static RowMapper<Tenant> tenantMapper() {
        return (rs, rowNum) -> new Tenant(
                rs.getObject("tenant_id", UUID.class), rs.getString("tenant_key"), rs.getString("display_name"),
                TenantStatus.valueOf(rs.getString("status")),
                new TenantGuardrails(rs.getLong("max_pdf_bytes"), rs.getInt("max_pages_per_pdf"),
                        rs.getInt("max_documents_per_submission"), rs.getLong("max_aggregate_submission_bytes"),
                        rs.getInt("incomplete_attention_hours"), rs.getInt("storage_warning_percent"),
                        rs.getInt("storage_stop_write_percent"), nullableInt(rs, "default_retention_days")),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static RowMapper<IngestionSource> sourceMapper() {
        return (rs, rowNum) -> new IngestionSource(
                rs.getObject("source_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("source_key"), rs.getString("display_name"),
                IngestionSourceStatus.valueOf(rs.getString("status")),
                IngestionSourceAuthType.valueOf(rs.getString("auth_type")), rs.getString("credential_reference"),
                new SourceLimits(nullableLong(rs, "max_pdf_bytes"), nullableInt(rs, "max_pages_per_pdf"),
                        nullableInt(rs, "max_documents_per_submission"), nullableLong(rs, "max_aggregate_submission_bytes"),
                        nullableInt(rs, "incomplete_attention_hours")),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static RowMapper<MetadataFieldDefinition> metadataMapper() {
        return (rs, rowNum) -> new MetadataFieldDefinition(
                rs.getObject("definition_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("source_id", UUID.class), rs.getString("field_key"), rs.getInt("version"),
                rs.getString("display_label"), MetadataFieldType.valueOf(rs.getString("field_type")),
                rs.getBoolean("required"), rs.getBoolean("business_identifier"), rs.getBoolean("searchable"),
                MetadataNormalization.valueOf(rs.getString("normalization")),
                MetadataSearchMode.valueOf(rs.getString("search_mode")), rs.getString("validation_rules"),
                rs.getBoolean("active"), instant(rs, "effective_at"), instant(rs, "retired_at"), instant(rs, "created_at"));
    }
}
