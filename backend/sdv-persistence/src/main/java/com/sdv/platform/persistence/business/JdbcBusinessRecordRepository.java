package com.sdv.platform.persistence.business;

import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcBusinessRecordRepository implements BusinessRecordRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBusinessRecordRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Submission> findMatchingSubmissions(
            UUID tenantId,
            UUID sourceId,
            List<SearchCriterion> criteria,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            int offset,
            int limit) {
        StringBuilder sql = new StringBuilder(submissionSelect())
                .append(" where s.tenant_id=:tenantId and s.source_id=:sourceId");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("offset", offset)
                .addValue("limit", limit);

        if (createdFrom != null) { sql.append(" and s.created_at>=:createdFrom"); params.addValue("createdFrom", Timestamp.from(createdFrom)); }
        if (createdTo != null) { sql.append(" and s.created_at<=:createdTo"); params.addValue("createdTo", Timestamp.from(createdTo)); }
        if (updatedFrom != null) { sql.append(" and s.last_activity_at>=:updatedFrom"); params.addValue("updatedFrom", Timestamp.from(updatedFrom)); }
        if (updatedTo != null) { sql.append(" and s.last_activity_at<=:updatedTo"); params.addValue("updatedTo", Timestamp.from(updatedTo)); }

        for (int i = 0; i < criteria.size(); i++) {
            SearchCriterion criterion = criteria.get(i);
            String prefix = "c" + i;
            params.addValue(prefix + "Field", criterion.fieldKey());

            sql.append("""
                     and exists (
                         select 1
                           from business_metadata_values v
                          where v.tenant_id=s.tenant_id
                            and v.source_id=s.source_id
                            and v.submission_id=s.submission_id
                            and v.field_key=
                    """).append(":").append(prefix).append("Field and (");

            List<String> variants = criterion.normalizedVariants();
            for (int v = 0; v < variants.size(); v++) {
                if (v > 0) sql.append(" or ");
                String valueParam = prefix + "Value" + v;
                params.addValue(valueParam, variants.get(v));
                if (criterion.searchMode() == MetadataSearchMode.EXACT) {
                    sql.append("v.normalized_value=:").append(valueParam);
                } else if (criterion.searchMode() == MetadataSearchMode.PREFIX) {
                    sql.append("position(:").append(valueParam).append(" in v.normalized_value)=1");
                } else {
                    sql.append("position(:").append(valueParam).append(" in v.normalized_value)>0");
                }
            }
            sql.append("))");
        }

        sql.append(" order by s.created_at desc, s.submission_id offset :offset limit :limit");
        return jdbc.query(sql.toString(), params, submissionMapper());
    }

    @Override
    public List<Submission> findSubmissionsByIds(
            UUID tenantId,
            UUID sourceId,
            List<UUID> submissionIds) {
        if (submissionIds.isEmpty()) return List.of();
        return jdbc.query(submissionSelect() + """
                 where s.tenant_id=:tenantId
                   and s.source_id=:sourceId
                   and s.submission_id in (:submissionIds)
                 order by s.created_at desc, s.submission_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("submissionIds", submissionIds), submissionMapper());
    }

    @Override
    public List<UUID> findSubmissionIdsByRecordIdentity(
            UUID tenantId,
            UUID sourceId,
            List<IdentifierCriterion> identifiers) {
        if (identifiers.isEmpty()) return List.of();

        StringBuilder sql = new StringBuilder("""
                select s.submission_id
                  from submissions s
                 where s.tenant_id=:tenantId
                   and s.source_id=:sourceId
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("identityCount", identifiers.size());

        for (int i = 0; i < identifiers.size(); i++) {
            IdentifierCriterion identifier = identifiers.get(i);
            String prefix = "i" + i;
            params.addValue(prefix + "Field", identifier.fieldKey());
            params.addValue(prefix + "Value", identifier.normalizedValue());
            sql.append("""
                     and exists (
                         select 1
                           from business_metadata_values v
                           join metadata_field_definitions d
                             on d.tenant_id=v.tenant_id
                            and d.source_id=v.source_id
                            and d.definition_id=v.definition_id
                            and d.field_key=v.field_key
                            and d.version=v.definition_version
                          where v.tenant_id=s.tenant_id
                            and v.source_id=s.source_id
                            and v.submission_id=s.submission_id
                            and d.business_identifier=true
                            and v.field_key=
                    """).append(":").append(prefix).append("Field")
                    .append(" and v.normalized_value=:").append(prefix).append("Value)");
        }

        sql.append("""
                 and (
                     select count(*)
                       from business_metadata_values iv
                       join metadata_field_definitions idf
                         on idf.tenant_id=iv.tenant_id
                        and idf.source_id=iv.source_id
                        and idf.definition_id=iv.definition_id
                        and idf.field_key=iv.field_key
                        and idf.version=iv.definition_version
                      where iv.tenant_id=s.tenant_id
                        and iv.source_id=s.source_id
                        and iv.submission_id=s.submission_id
                        and idf.business_identifier=true
                 )=:identityCount
                 order by s.created_at desc, s.submission_id
                """);

        return jdbc.query(sql.toString(), params,
                (rs, rowNum) -> rs.getObject("submission_id", UUID.class));
    }

    @Override
    public List<MetadataSnapshot> findMetadataSnapshots(
            UUID tenantId,
            UUID sourceId,
            List<UUID> submissionIds) {
        if (submissionIds.isEmpty()) return List.of();
        return jdbc.query("""
                select v.submission_id, v.field_key, d.display_label, v.stored_value,
                       v.normalized_value, d.business_identifier
                  from business_metadata_values v
                  join metadata_field_definitions d
                    on d.tenant_id=v.tenant_id
                   and d.source_id=v.source_id
                   and d.definition_id=v.definition_id
                   and d.field_key=v.field_key
                   and d.version=v.definition_version
                 where v.tenant_id=:tenantId
                   and v.source_id=:sourceId
                   and v.submission_id in (:submissionIds)
                 order by v.submission_id, v.field_key
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("submissionIds", submissionIds),
                (rs, rowNum) -> new MetadataSnapshot(
                        rs.getObject("submission_id", UUID.class),
                        rs.getString("field_key"),
                        rs.getString("display_label"),
                        rs.getString("stored_value"),
                        rs.getString("normalized_value"),
                        rs.getBoolean("business_identifier")));
    }

    @Override
    public List<AvailableDocumentSnapshot> findAvailableDocuments(
            UUID tenantId,
            UUID sourceId,
            List<UUID> submissionIds) {
        if (submissionIds.isEmpty()) return List.of();
        return jdbc.query("""
                select document_id, submission_id, source_document_id, original_filename,
                       ingested_at, available_at, expires_at
                  from documents
                 where tenant_id=:tenantId
                   and source_id=:sourceId
                   and submission_id in (:submissionIds)
                   and status='AVAILABLE'
                 order by available_at desc nulls last, document_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("submissionIds", submissionIds),
                (rs, rowNum) -> new AvailableDocumentSnapshot(
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("submission_id", UUID.class),
                        rs.getString("source_document_id"),
                        rs.getString("original_filename"),
                        instant(rs, "ingested_at"),
                        instant(rs, "available_at"),
                        instant(rs, "expires_at")));
    }

    @Override
    public List<ExpectedDocumentSnapshot> findExpectedDocuments(
            UUID tenantId,
            List<UUID> submissionIds) {
        if (submissionIds.isEmpty()) return List.of();
        return jdbc.query("""
                select submission_id, source_document_id, state
                  from submission_expected_documents
                 where tenant_id=:tenantId
                   and submission_id in (:submissionIds)
                 order by submission_id, source_document_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("submissionIds", submissionIds),
                (rs, rowNum) -> new ExpectedDocumentSnapshot(
                        rs.getObject("submission_id", UUID.class),
                        rs.getString("source_document_id"),
                        rs.getString("state")));
    }

    @Override
    public List<DocumentCount> countDocuments(
            UUID tenantId,
            UUID sourceId,
            List<UUID> submissionIds) {
        if (submissionIds.isEmpty()) return List.of();
        return jdbc.query("""
                select submission_id, count(*) as document_count
                  from documents
                 where tenant_id=:tenantId
                   and source_id=:sourceId
                   and submission_id in (:submissionIds)
                 group by submission_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("submissionIds", submissionIds),
                (rs, rowNum) -> new DocumentCount(
                        rs.getObject("submission_id", UUID.class),
                        rs.getInt("document_count")));
    }

    private static String submissionSelect() {
        return """
                select s.submission_id, s.tenant_id, s.source_id, s.source_submission_id,
                       s.expected_document_count, s.create_request_sha256, s.status,
                       s.created_at, s.last_activity_at, s.completed_at
                  from submissions s
                """;
    }

    private static RowMapper<Submission> submissionMapper() {
        return (rs, rowNum) -> new Submission(
                rs.getObject("submission_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("source_id", UUID.class),
                rs.getString("source_submission_id"),
                rs.getInt("expected_document_count"),
                rs.getString("create_request_sha256"),
                SubmissionStatus.valueOf(rs.getString("status")),
                instant(rs, "created_at"),
                instant(rs, "last_activity_at"),
                instant(rs, "completed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
