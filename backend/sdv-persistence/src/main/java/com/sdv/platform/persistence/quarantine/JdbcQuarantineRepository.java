package com.sdv.platform.persistence.quarantine;

import com.sdv.platform.application.port.QuarantineRepository;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.InternalCheckFailed;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Task 0048: quarantine tables (changeset 036). Every statement is limited to one entity (tenant_id). */
@Repository
public class JdbcQuarantineRepository implements QuarantineRepository {
    /** The record's primary identifier, as for legal holds: its first business identifier in display order. */
    static final String PRIMARY_IDENTIFIER = """
            left join lateral (
                select v.stored_value, f.display_label
                  from business_metadata_values v
                  join metadata_field_definitions f on f.definition_id=v.definition_id and f.business_identifier
                  left join metadata_field_display_order o
                    on o.tenant_id=v.tenant_id and o.source_id=f.source_id and o.field_key=v.field_key
                 where v.tenant_id=d.tenant_id and v.submission_id=d.submission_id and btrim(v.stored_value)<>''
                 order by o.display_position nulls last, v.field_key
                 limit 1) pid on true
            """;
    static final String DOCUMENT_COLUMNS = """
            d.document_id, d.source_id, d.submission_id, d.status, d.sensitivity_level, d.ingested_at, d.page_count,
            pid.stored_value as primary_identifier, pid.display_label as primary_identifier_label
            """;
    static final String NOT_DELETED = "d.status not in ('EXPIRED_DELETED', 'RETENTION_DELETING')";
    static final String IN_FORCE = """
            exists (select 1 from quarantined_documents q
                     where q.tenant_id=d.tenant_id and q.document_id=d.document_id and q.released_at is null)""";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcQuarantineRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isQuarantined(UUID tenantId, UUID documentId) {
        if (tenantId == null || documentId == null) return false;
        Boolean any = jdbc.queryForObject("""
                select exists(select 1 from quarantined_documents
                               where tenant_id=:tenantId and document_id=:documentId and released_at is null)
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("documentId", documentId),
                Boolean.class);
        return Boolean.TRUE.equals(any);
    }

    @Override
    public Set<UUID> quarantined(UUID tenantId, Collection<UUID> documentIds) {
        if (tenantId == null || documentIds == null || documentIds.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.query("""
                select document_id from quarantined_documents
                 where tenant_id=:tenantId and document_id in (:ids) and released_at is null
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("ids", List.copyOf(documentIds)),
                (rs, row) -> rs.getObject(1, UUID.class)));
    }

    @Override
    public Optional<DocumentFacts> lockDocument(UUID tenantId, UUID documentId) {
        List<UUID> locked = jdbc.query("select document_id from documents where tenant_id=:tenantId and document_id=:documentId for update",
                document(tenantId, documentId), (rs, row) -> rs.getObject(1, UUID.class));
        if (locked.isEmpty()) return Optional.empty();
        return jdbc.query("select " + DOCUMENT_COLUMNS + " from documents d " + PRIMARY_IDENTIFIER
                + " where d.tenant_id=:tenantId and d.document_id=:documentId", document(tenantId, documentId),
                (rs, row) -> facts(rs, "")).stream().findFirst();
    }

    @Override
    public void insertIncident(Incident i) {
        jdbc.update("""
                insert into quarantine_incidents(incident_id, tenant_id, name, reason, scope, created_by,
                    created_by_display_name, created_at, status)
                values (:id, :tenantId, :name, :reason, :scope, :by, :byName, :at, 'OPEN')
                """, new MapSqlParameterSource().addValue("id", i.incidentId()).addValue("tenantId", i.tenantId())
                .addValue("name", i.name()).addValue("reason", i.reason()).addValue("scope", i.scope())
                .addValue("by", i.createdBy()).addValue("byName", i.createdByName())
                .addValue("at", Timestamp.from(i.createdAt())));
    }

    private static final String INCIDENT = """
            select incident_id, tenant_id, name, reason, scope, created_by, created_by_display_name, created_at, status,
                   closed_at from quarantine_incidents
            """;

    @Override
    public Optional<Incident> findIncident(UUID tenantId, UUID incidentId) {
        return jdbc.query(INCIDENT + " where tenant_id=:tenantId and incident_id=:id", incident(tenantId, incidentId),
                (rs, row) -> incident(rs)).stream().findFirst();
    }

    @Override
    public Optional<Incident> lockIncident(UUID tenantId, UUID incidentId) {
        return jdbc.query(INCIDENT + " where tenant_id=:tenantId and incident_id=:id for update",
                incident(tenantId, incidentId), (rs, row) -> incident(rs)).stream().findFirst();
    }

    @Override
    public List<Incident> findIncidents(UUID tenantId, int limit) {
        return jdbc.query(INCIDENT + " where tenant_id=:tenantId order by status, created_at desc, incident_id limit :limit",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("limit", limit), (rs, row) -> incident(rs));
    }

    @Override
    public void closeIncident(UUID tenantId, UUID incidentId, Instant closedAt) {
        jdbc.update("update quarantine_incidents set status='RELEASED', closed_at=:at where tenant_id=:tenantId and incident_id=:id and status='OPEN'",
                incident(tenantId, incidentId).addValue("at", Timestamp.from(closedAt)));
    }

    @Override
    public void insertQuarantine(UUID quarantineId, UUID tenantId, UUID incidentId, UUID documentId, String by,
                                 String byName, Instant at) {
        jdbc.update("""
                insert into quarantined_documents(quarantine_id, tenant_id, incident_id, document_id, quarantined_by,
                    quarantined_by_display_name, quarantined_at)
                values (:id, :tenantId, :incidentId, :documentId, :by, :byName, :at)
                """, new MapSqlParameterSource().addValue("id", quarantineId).addValue("tenantId", tenantId)
                .addValue("incidentId", incidentId).addValue("documentId", documentId).addValue("by", by)
                .addValue("byName", byName).addValue("at", Timestamp.from(at)));
    }

    private static final String QUARANTINED = "select q.quarantine_id, q.incident_id, q.quarantined_by, "
            + "q.quarantined_by_display_name, q.quarantined_at, q.released_by, q.released_by_display_name, q.released_at, "
            + DOCUMENT_COLUMNS + " from quarantined_documents q join documents d on d.tenant_id=q.tenant_id "
            + "and d.document_id=q.document_id " + PRIMARY_IDENTIFIER;

    @Override
    public Optional<Quarantined> findActive(UUID tenantId, UUID documentId) {
        return jdbc.query(QUARANTINED + " where q.tenant_id=:tenantId and q.document_id=:documentId and q.released_at is null",
                document(tenantId, documentId), (rs, row) -> quarantined(rs)).stream().findFirst();
    }

    @Override
    public List<Quarantined> findInIncident(UUID tenantId, UUID incidentId) {
        return jdbc.query(QUARANTINED + " where q.tenant_id=:tenantId and q.incident_id=:id "
                        + "order by (q.released_at is not null), q.quarantined_at, d.ingested_at, d.document_id",
                incident(tenantId, incidentId), (rs, row) -> quarantined(rs));
    }

    @Override
    public Map<UUID, Integer> countInForce(UUID tenantId, List<UUID> incidentIds) {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        if (incidentIds.isEmpty()) return out;
        jdbc.query("""
                select incident_id, count(*) from quarantined_documents
                 where tenant_id=:tenantId and incident_id in (:ids) and released_at is null group by incident_id
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("ids", incidentIds),
                rs -> { out.put(rs.getObject(1, UUID.class), rs.getInt(2)); });
        return out;
    }

    @Override
    public boolean release(UUID tenantId, UUID quarantineId, String by, String byName, String reason, UUID requestId,
                           Instant at) {
        int updated = jdbc.update("""
                update quarantined_documents
                   set released_by=:by, released_by_display_name=:byName, released_at=:at, release_reason=:reason,
                       release_request_id=:requestId
                 where tenant_id=:tenantId and quarantine_id=:id and released_at is null
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", quarantineId)
                .addValue("by", by).addValue("byName", byName).addValue("reason", reason)
                .addValue("requestId", requestId).addValue("at", Timestamp.from(at)));
        if (updated > 1) throw new InternalCheckFailed("A quarantine release changed more than one row.");
        return updated == 1;
    }

    /** The filter as SQL: received dates, sending system, field values (normalized, as search compares them). */
    private static String where(Filter f, MapSqlParameterSource p) {
        StringBuilder sql = new StringBuilder(" where d.tenant_id=:tenantId and " + NOT_DELETED + " and not " + IN_FORCE);
        if (f.receivedFrom() != null) { sql.append(" and d.ingested_at >= :from"); p.addValue("from", Timestamp.from(f.receivedFrom())); }
        if (f.receivedTo() != null) { sql.append(" and d.ingested_at < :to"); p.addValue("to", Timestamp.from(f.receivedTo())); }
        if (f.sourceId() != null) { sql.append(" and d.source_id = :sourceId"); p.addValue("sourceId", f.sourceId()); }
        int i = 0;
        for (Map.Entry<String, String> field : f.fields().entrySet()) {
            String k = "fk" + i, v = "fv" + i;
            sql.append(" and exists (select 1 from business_metadata_values v where v.tenant_id=d.tenant_id")
                    .append(" and v.submission_id=d.submission_id and v.field_key=:").append(k)
                    .append(" and coalesce(v.normalized_value, v.stored_value)=:").append(v).append(")");
            p.addValue(k, field.getKey()).addValue(v, field.getValue());
            i++;
        }
        return sql.toString();
    }

    @Override
    public List<DocumentFacts> findMatching(UUID tenantId, Filter filter, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("limit", limit);
        return jdbc.query("select " + DOCUMENT_COLUMNS + " from documents d " + PRIMARY_IDENTIFIER + where(filter, p)
                + " order by d.ingested_at, d.document_id limit :limit", p, (rs, row) -> facts(rs, ""));
    }

    @Override
    public long countMatching(UUID tenantId, Filter filter) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenantId", tenantId);
        Long n = jdbc.queryForObject("select count(*) from documents d" + where(filter, p), p, Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public void insertRequest(ReleaseRequest r) {
        jdbc.update("""
                insert into quarantine_release_requests(request_id, tenant_id, incident_id, reason, requested_by,
                    requested_by_display_name, requested_at, status, expires_at)
                values (:id, :tenantId, :incidentId, :reason, :by, :byName, :at, 'PENDING', :expires)
                """, new MapSqlParameterSource().addValue("id", r.requestId()).addValue("tenantId", r.tenantId())
                .addValue("incidentId", r.incidentId()).addValue("reason", r.reason()).addValue("by", r.requestedBy())
                .addValue("byName", r.requestedByName()).addValue("at", Timestamp.from(r.requestedAt()))
                .addValue("expires", Timestamp.from(r.expiresAt())));
    }

    private static final String REQUEST = """
            select request_id, tenant_id, incident_id, reason, requested_by, requested_by_display_name, requested_at,
                   status, decided_by, decided_by_display_name, decided_at, expires_at from quarantine_release_requests
            """;

    @Override
    public Optional<ReleaseRequest> lockRequest(UUID tenantId, UUID requestId) {
        return jdbc.query(REQUEST + " where tenant_id=:tenantId and request_id=:id for update",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", requestId),
                (rs, row) -> request(rs)).stream().findFirst();
    }

    @Override
    public Optional<ReleaseRequest> findPendingRequest(UUID tenantId, UUID incidentId) {
        return jdbc.query(REQUEST + " where tenant_id=:tenantId and incident_id=:id and status='PENDING'",
                incident(tenantId, incidentId), (rs, row) -> request(rs)).stream().findFirst();
    }

    @Override
    public List<ReleaseRequest> findPendingRequests(UUID tenantId, int limit) {
        return jdbc.query(REQUEST + " where tenant_id=:tenantId and status='PENDING' order by requested_at, request_id limit :limit",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("limit", limit), (rs, row) -> request(rs));
    }

    @Override
    public boolean decideRequest(UUID tenantId, UUID requestId, String status, String by, String byName, Instant at) {
        return jdbc.update("""
                update quarantine_release_requests
                   set status=:status, decided_by=:by, decided_by_display_name=:byName, decided_at=:at
                 where tenant_id=:tenantId and request_id=:id and status='PENDING'
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", requestId)
                .addValue("status", status).addValue("by", by).addValue("byName", byName)
                .addValue("at", Timestamp.from(at))) == 1;
    }

    @Override
    public List<ReleaseRequest> expireRequests(UUID tenantId, Instant now) {
        return jdbc.query("""
                update quarantine_release_requests set status='EXPIRED'
                 where tenant_id=:tenantId and status='PENDING' and expires_at <= :now
                returning request_id, tenant_id, incident_id, reason, requested_by, requested_by_display_name,
                          requested_at, status, decided_by, decided_by_display_name, decided_at, expires_at
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("now", Timestamp.from(now)),
                (rs, row) -> request(rs));
    }

    @Override
    public List<ReleaseRequest> expirePending(UUID tenantId, UUID incidentId) {
        return jdbc.query("""
                update quarantine_release_requests set status='EXPIRED'
                 where tenant_id=:tenantId and incident_id=:id and status='PENDING'
                returning request_id, tenant_id, incident_id, reason, requested_by, requested_by_display_name,
                          requested_at, status, decided_by, decided_by_display_name, decided_at, expires_at
                """, incident(tenantId, incidentId), (rs, row) -> request(rs));
    }

    @Override
    public List<Exposure> findExposure(UUID tenantId, Map<UUID, Instant> before) {
        List<Exposure> out = new ArrayList<>();
        if (before.isEmpty()) return out;
        for (Map.Entry<UUID, Instant> e : before.entrySet()) {
            out.addAll(jdbc.query("""
                    select target_id, actor_id, actor_display_name, action, occurred_at from audit_events
                     where tenant_id=:tenantId and target_type='DOCUMENT' and target_id=:documentId
                       and action in ('DOCUMENT_VIEW_OPENED', 'DOCUMENT_PRINTED', 'DOCUMENT_DOWNLOADED')
                       and outcome='SUCCESS' and occurred_at < :before
                     order by occurred_at, audit_event_id
                    """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                    .addValue("documentId", e.getKey().toString()).addValue("before", Timestamp.from(e.getValue())),
                    (rs, row) -> new Exposure(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getTimestamp(5).toInstant())));
        }
        return out;
    }

    // ------------------------------------------------------------------ mapping

    private static MapSqlParameterSource document(UUID tenantId, UUID documentId) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("documentId", documentId);
    }

    private static MapSqlParameterSource incident(UUID tenantId, UUID incidentId) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", incidentId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    static DocumentFacts facts(ResultSet rs, String prefix) throws SQLException {
        String level = rs.getString("sensitivity_level");
        int pages = rs.getInt("page_count");
        Integer pageCount = rs.wasNull() ? null : pages;
        return new DocumentFacts(rs.getObject("document_id", UUID.class), rs.getObject("source_id", UUID.class),
                rs.getObject("submission_id", UUID.class), rs.getString("status"),
                level == null ? null : SensitivityLevel.valueOf(level), instant(rs, "ingested_at"),
                pageCount, rs.getString("primary_identifier"), rs.getString("primary_identifier_label"));
    }

    private static Incident incident(ResultSet rs) throws SQLException {
        return new Incident(rs.getObject("incident_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("name"), rs.getString("reason"), rs.getString("scope"), rs.getString("created_by"),
                rs.getString("created_by_display_name"), instant(rs, "created_at"), rs.getString("status"),
                instant(rs, "closed_at"));
    }

    private static Quarantined quarantined(ResultSet rs) throws SQLException {
        return new Quarantined(rs.getObject("quarantine_id", UUID.class), rs.getObject("incident_id", UUID.class),
                facts(rs, ""), rs.getString("quarantined_by"), rs.getString("quarantined_by_display_name"),
                instant(rs, "quarantined_at"), rs.getString("released_by"), rs.getString("released_by_display_name"),
                instant(rs, "released_at"));
    }

    private static ReleaseRequest request(ResultSet rs) throws SQLException {
        return new ReleaseRequest(rs.getObject("request_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("incident_id", UUID.class), rs.getString("reason"), rs.getString("requested_by"),
                rs.getString("requested_by_display_name"), instant(rs, "requested_at"), rs.getString("status"),
                rs.getString("decided_by"), rs.getString("decided_by_display_name"), instant(rs, "decided_at"),
                instant(rs, "expires_at"));
    }
}
