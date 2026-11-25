package com.sdv.platform.persistence.business;

import com.sdv.platform.domain.identity.VeroProduct;
import com.sdv.platform.persistence.fields.CaseFieldSql;
import com.sdv.platform.domain.document.SensitivityLevel;
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

    /** Rank of an anywhere match (task 0022): lower is better. Exact on an identifier is 0, exact elsewhere 1. */
    static final int RANK_STARTS_WITH = 2;
    static final int RANK_CONTAINS = 3;

    @Override
    public List<Submission> findMatchingSubmissions(
            UUID tenantId,
            UUID sourceId,
            List<SearchCriterion> criteria,
            AnywhereCriterion anywhere,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            boolean incompleteOnly,
            int offset,
            int limit) {
        StringBuilder sql = new StringBuilder(submissionSelect());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("recordType", CaseFieldSql.recordType(sourceId))
                .addValue("offset", offset)
                .addValue("limit", limit);
        if (anywhere != null) {
            sql.append(CaseFieldSql.record("s"));
            appendAnywhereRank(sql, params, anywhere);
        }
        sql.append(" where s.tenant_id=:tenantId and s.source_id=:sourceId");
        // A submission none of whose fields matches gets no rank and is left out.
        if (anywhere != null) sql.append(" and m.match_rank is not null");

        if (createdFrom != null) { sql.append(" and s.created_at>=:createdFrom"); params.addValue("createdFrom", Timestamp.from(createdFrom)); }
        if (createdTo != null) { sql.append(" and s.created_at<=:createdTo"); params.addValue("createdTo", Timestamp.from(createdTo)); }
        if (updatedFrom != null) { sql.append(" and s.last_activity_at>=:updatedFrom"); params.addValue("updatedFrom", Timestamp.from(updatedFrom)); }
        if (updatedTo != null) { sql.append(" and s.last_activity_at<=:updatedTo"); params.addValue("updatedTo", Timestamp.from(updatedTo)); }
        // Same meaning as RecordSummary.incomplete: a submission that is not COMPLETE still misses documents.
        if (incompleteOnly) sql.append(" and s.status<>'COMPLETE'");

        for (int i = 0; i < criteria.size(); i++) {
            SearchCriterion criterion = criteria.get(i);
            String prefix = "c" + i;
            params.addValue(prefix + "Field", criterion.fieldKey());

            // The record's values, by the value index when the planner prefers it (a selective search), else per record.
            sql.append(" and exists (select 1").append(anywhere != null ? " from fld_value v where v.record_uuid=fr.record_uuid"
                    : " from fld_record fr join fld_value v on v.record_uuid=fr.record_uuid where fr.entity_uuid=s.tenant_id and fr.product_code='" + VeroProduct.CODE
                    + "' and fr.record_type=:recordType and fr.record_id=s.record_id::text")
                    .append(" and v.entity_uuid=:tenantId and v.product_code='" + VeroProduct.CODE + "' and v.record_type=:recordType and v.field_key=:").append(prefix).append("Field and (");
            if (!criterion.fingerprints().isEmpty()) {
                sql.append("v.fingerprint in (:").append(prefix).append("Prints)))");
                params.addValue(prefix + "Prints", criterion.fingerprints());
                continue;
            }
            if (criterion.normalizedVariants().isEmpty()) {
                sql.append("false))"); // a value the field can't have
                continue;
            }
            List<String> variants = criterion.normalizedVariants();
            for (int v = 0; v < variants.size(); v++) {
                if (v > 0) sql.append(" or ");
                String valueParam = prefix + "Value" + v;
                // Task 0078: compared as stored for search (no capitals, single spaces); a sensitive value has no such form.
                params.addValue(valueParam, pattern(criterion.searchMode(), variants.get(v)));
                sql.append("v.search_value").append(criterion.searchMode() == MetadataSearchMode.EXACT ? "=:" : " like :").append(valueParam);
            }
            sql.append("))");
        }

        // Best match first when the text is searched anywhere; otherwise (and for ties) newest first.
        sql.append(anywhere != null ? " order by m.match_rank, " : " order by ")
                .append("s.created_at desc, s.submission_id offset :offset limit :limit");
        return jdbc.query(sql.toString(), params, submissionMapper());
    }

    /**
     * The submission's best anywhere match, as m.match_rank (null when no field matches). Each field is tried with
     * its own match: Exact compares the normalized value as configured (ignoring case when the field says so);
     * Starts with and Contains ignore case, as the field search does. Only this submission's own rows in its own tenant and source are read.
     */
    private static void appendAnywhereRank(StringBuilder sql, MapSqlParameterSource params, AnywhereCriterion anywhere) {
        List<String> keys = new ArrayList<>();
        sql.append("""
                 cross join lateral (
                     select min(case
                """);
        for (int i = 0; i < anywhere.fields().size(); i++) {
            AnywhereField field = anywhere.fields().get(i);
            String prefix = "a" + i;
            params.addValue(prefix + "Field", field.fieldKey());
            keys.add(field.fieldKey());
            List<String> equal = new ArrayList<>(), starts = new ArrayList<>(), contains = new ArrayList<>();
            for (int v = 0; v < field.normalizedVariants().size(); v++) {
                String value = ":" + prefix + "Value" + v, like = ":" + prefix + "Like" + v;
                String wanted = CaseFieldSql.searchForm(field.normalizedVariants().get(v));
                params.addValue(prefix + "Value" + v, wanted);
                equal.add("v.search_value=" + value);
                if (field.searchMode() != MetadataSearchMode.EXACT) {
                    params.addValue(prefix + "Like" + v, escape(wanted));
                    starts.add("v.search_value like " + like + "||'%'");
                    contains.add("v.search_value like '%'||" + like + "||'%'");
                }
            }
            int exactRank = field.businessIdentifier() ? 0 : 1;
            String allowed = switch (field.searchMode()) {
                case EXACT -> String.join(" or ", equal);
                case PREFIX -> String.join(" or ", starts);
                case PARTIAL -> String.join(" or ", contains);
            };
            sql.append(" when v.field_key=:").append(prefix).append("Field and (").append(allowed).append(") then ");
            if (field.searchMode() == MetadataSearchMode.EXACT) {
                sql.append(exactRank);
            } else {
                sql.append("case when ").append(String.join(" or ", equal)).append(" then ").append(exactRank)
                        .append(" when ").append(String.join(" or ", starts)).append(" then ").append(RANK_STARTS_WITH)
                        .append(" else ").append(RANK_CONTAINS).append(" end");
            }
            sql.append('\n');
        }
        params.addValue("anywhereFields", keys);
        sql.append("""
                     end) as match_rank
                       from fld_value v
                      where v.record_uuid=fr.record_uuid and not v.sensitive
                        and v.field_key in (:anywhereFields)
                 ) m
                """);
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String pattern(MetadataSearchMode mode, String text) {
        String wanted = CaseFieldSql.searchForm(text);
        return switch (mode) {
            case EXACT -> wanted;
            case PREFIX -> escape(wanted) + "%";
            case PARTIAL -> "%" + escape(wanted) + "%";
        };
    }

    @Override
    public List<RecentDocumentView> findRecentlyViewedDocuments(
            UUID tenantId,
            String actorId,
            Instant since,
            int eventScanLimit) {
        // Only this actor's own successful viewer opens in this tenant; only documents still AVAILABLE.
        return jdbc.query("""
                select d.document_id, d.submission_id, d.source_id, r.last_opened_at
                  from (select e.target_id, max(e.occurred_at) as last_opened_at
                          from (select target_id, occurred_at
                                  from audit_events
                                 where tenant_id=:tenantId
                                   and action='DOCUMENT_VIEW_OPENED'
                                   and actor_type='USER'
                                   and actor_id=:actorId
                                   and target_type='DOCUMENT'
                                   and outcome='SUCCESS'
                                   and occurred_at>=:since
                                 order by occurred_at desc
                                 limit :scanLimit) e
                         where e.target_id ~ '^[0-9a-fA-F-]{36}$'
                         group by e.target_id) r
                  join documents d
                    on d.tenant_id=:tenantId
                   and d.document_id=cast(r.target_id as uuid)
                   and d.status='AVAILABLE'
                 order by r.last_opened_at desc, d.document_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("since", Timestamp.from(since))
                .addValue("scanLimit", eventScanLimit),
                (rs, rowNum) -> new RecentDocumentView(
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("submission_id", UUID.class),
                        rs.getObject("source_id", UUID.class),
                        instant(rs, "last_opened_at")));
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
    public List<UUID> findSubmissionIdsOfRecord(UUID tenantId, UUID sourceId, UUID recordId) {
        return jdbc.queryForList("select submission_id from submissions where tenant_id=:tenantId and source_id=:sourceId and record_id=:recordId order by created_at desc, submission_id",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("sourceId", sourceId).addValue("recordId", recordId), UUID.class);
    }

    @Override
    public java.util.Map<UUID, UUID> findRecordIds(UUID tenantId, UUID sourceId, java.util.Collection<UUID> submissionIds) {
        java.util.Map<UUID, UUID> out = new java.util.HashMap<>();
        if (submissionIds.isEmpty()) return out;
        jdbc.query("select submission_id, record_id from submissions where tenant_id=:tenantId and source_id=:sourceId and submission_id in (:ids)",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("sourceId", sourceId).addValue("ids", List.copyOf(submissionIds)),
                rs -> { out.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)); });
        return out;
    }

    @Override
    public java.util.Optional<SensitivityLevel> findRecordSensitivityLevel(
            UUID tenantId,
            UUID sourceId,
            UUID recordId) {
        return jdbc.query("""
                select sensitivity_level
                  from record_sensitivity_levels
                 where tenant_id=:tenantId and source_id=:sourceId and record_id=:recordId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("recordId", recordId),
                (rs, rowNum) -> SensitivityLevel.valueOf(rs.getString("sensitivity_level")))
                .stream().findFirst();
    }

    /** Task 0026: one query for a page of records, by the unique (tenant, source, key SHA-256) index. */
    @Override
    public java.util.Map<UUID, SensitivityLevel> findRecordSensitivityLevels(
            UUID tenantId,
            UUID sourceId,
            java.util.Collection<UUID> recordIds) {
        if (recordIds.isEmpty()) return java.util.Map.of();
        java.util.Map<UUID, SensitivityLevel> levels = new java.util.HashMap<>();
        jdbc.query("""
                select record_id, sensitivity_level
                  from record_sensitivity_levels
                 where tenant_id=:tenantId and source_id=:sourceId and record_id in (:ids)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("ids", List.copyOf(new java.util.LinkedHashSet<>(recordIds))),
                rs -> {
                    levels.put(rs.getObject("record_id", UUID.class), SensitivityLevel.valueOf(rs.getString("sensitivity_level")));
                });
        return levels;
    }

    @Override
    public List<MetadataSnapshot> findMetadataSnapshots(
            UUID tenantId,
            UUID sourceId,
            List<UUID> submissionIds) {
        if (submissionIds.isEmpty()) return List.of();
        return jdbc.query("""
                select s.submission_id, v.field_key, %s as display_label, v.display_value as stored_value,
                       v.search_value as normalized_value, %s as business_identifier
                  from submissions s
                %s
                  join fld_value v on v.record_uuid=fr.record_uuid and not v.sensitive and v.position=0
                %s
                 where s.tenant_id=:tenantId
                   and s.source_id=:sourceId
                   and s.submission_id in (:submissionIds)
                 order by s.submission_id, v.field_key
                """.formatted(CaseFieldSql.LABEL, CaseFieldSql.IDENTIFIER, CaseFieldSql.record("s"), CaseFieldSql.field("s")), new MapSqlParameterSource()
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
                select d.document_id, d.submission_id, d.source_document_id, d.original_filename,
                       d.ingested_at, d.available_at, d.expires_at, d.page_count, d.sensitivity_level,
                       -- Redacted: the current content is the result of a finished correction.
                       exists (select 1 from document_redaction_operations r
                                where r.tenant_id=d.tenant_id and r.document_id=d.document_id
                                  and r.new_content_sha256=d.content_sha256) as redacted
                  from documents d
                 where d.tenant_id=:tenantId
                   and d.source_id=:sourceId
                   and d.submission_id in (:submissionIds)
                   and d.status='AVAILABLE'
                 order by d.available_at desc nulls last, d.document_id
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
                        instant(rs, "expires_at"),
                        (Integer) rs.getObject("page_count"),
                        rs.getBoolean("redacted"),
                        SensitivityLevel.valueOf(rs.getString("sensitivity_level"))));
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

    /**
     * Task 0023: one query for all of the tenant's sending systems. Each max() is read from the
     * (tenant_id, source_id, last_activity_at desc) index, so no submission is counted.
     */
    @Override
    public List<SourceActivity> findSourceActivity(UUID tenantId) {
        return jdbc.query("""
                select src.source_id,
                       (select max(s.last_activity_at)
                          from submissions s
                         where s.tenant_id=src.tenant_id
                           and s.source_id=src.source_id) as last_delivered_at
                  from ingestion_sources src
                 where src.tenant_id=:tenantId
                """, new MapSqlParameterSource().addValue("tenantId", tenantId),
                (rs, rowNum) -> new SourceActivity(
                        rs.getObject("source_id", UUID.class),
                        instant(rs, "last_delivered_at")));
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
