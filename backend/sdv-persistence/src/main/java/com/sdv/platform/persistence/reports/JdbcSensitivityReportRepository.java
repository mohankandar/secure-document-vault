package com.sdv.platform.persistence.reports;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.business.RecordKey;
import com.sdv.platform.application.port.SensitivityReportRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0033: the Sensitivity report's reads. Only levels, kinds, statuses, actions, role names and counts are selected:
 * never a name, title, file name, metadata value or content. Every query is limited to one tenant.
 * <p>
 * <b>Effective levels in one statement.</b> A document's effective level is the higher of its own level and its
 * record's level. A record is named by its key (see {@link RecordKey}): its business-identifier values, or one
 * submission. The record levels above Standard are read first and their keys parsed here; the submissions of each
 * record are then found in SQL exactly as record search finds them (findSubmissionIdsByRecordIdentity: every value
 * matches and the submission has no other identifier value), and every stored document is counted once at
 * greatest(own level, its record's level). A key that can't be read stops the report (never a guessed count).
 * <p>
 * Indexes used: documents (tenant_id, ...), business_metadata_values (tenant_id, field_key, normalized_value),
 * record_sensitivity_levels (tenant_id, source_id, ...), legal_holds active (tenant_id, ...), audit_events (tenant_id,
 * action, occurred_at). No new index.
 */
@Repository
public class JdbcSensitivityReportRepository implements SensitivityReportRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Stored now: the content is kept (not failed, deleted, being deleted, or still arriving). */
    static final String STORED = "('RECEIVED', 'PROCESSING', 'AVAILABLE', 'REDACTION_PROCESSING')";
    private static final String RAISED = "('DOCUMENT_SENSITIVITY_RAISED', 'RECORD_SENSITIVITY_RAISED')";
    private static final String LOWERED = "('DOCUMENT_SENSITIVITY_LOWERED', 'RECORD_SENSITIVITY_LOWERED')";
    private static final String ABOVE_STANDARD = "('CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL')";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSensitivityReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DocumentLevels countStoredDocuments(UUID tenantId, UUID sourceId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenantId", tenantId);
        String documentSource = "";
        if (sourceId != null) {
            p.addValue("sourceId", sourceId);
            documentSource = " and d.source_id=:sourceId";
        }
        LevelSql levels = effectiveLevels(jdbc, tenantId, sourceId, p);
        long[] byLevel = new long[3];
        jdbc.query(levels.with() + """
                select\s""" + LEVEL_OF_D + """
                 as lvl, count(*) as n
                  from documents d
                  left join submission_levels sl on sl.submission_id=d.submission_id
                 where d.tenant_id=:tenantId and d.status in\s""" + STORED + documentSource + """

                 group by 1
                """, p, rs -> {
            byLevel[rs.getInt("lvl")] += rs.getLong("n");
        });
        Long held = jdbc.queryForObject("""
                select count(distinct h.document_id)
                  from legal_holds h
                  join documents d on d.tenant_id=h.tenant_id and d.document_id=h.document_id
                 where h.tenant_id=:tenantId and h.released_at is null and d.status in\s""" + STORED + documentSource,
                p, Long.class);
        return new DocumentLevels(byLevel[0], byLevel[1], byLevel[2], levels.recordsWithLevel(), held == null ? 0 : held);
    }

    @Override
    public List<RequestCount> countRequests(UUID tenantId, UUID sourceId, Instant from, Instant to, Instant now) {
        MapSqlParameterSource p = range(tenantId, from, to).addValue("now", Timestamp.from(now));
        String source = "";
        if (sourceId != null) {
            p.addValue("sourceId", sourceId);
            source = " and coalesce(r.source_id, d.source_id)=:sourceId";
        }
        return jdbc.query("""
                select r.kind,
                       case when r.status in ('APPROVED', 'USED') or (r.status='EXPIRED' and r.decided_at is not null) then 'APPROVED'
                            when r.status='DECLINED' then 'DECLINED'
                            when r.status='EXPIRED' or r.expires_at<=:now then 'EXPIRED'
                            else 'WAITING' end as outcome,
                       count(*) as n
                  from access_requests r
                  left join documents d on d.tenant_id=r.tenant_id and d.document_id=r.document_id
                 where r.tenant_id=:tenantId and r.requested_at>=:from and r.requested_at<:to""" + source + """

                 group by 1, 2
                """, p, (rs, row) -> new RequestCount(rs.getString("kind"), rs.getString("outcome"), rs.getLong("n")));
    }

    @Override
    public LevelChanges countLevelChanges(UUID tenantId, UUID sourceId, Instant from, Instant to) {
        MapSqlParameterSource p = range(tenantId, from, to);
        String join = "";
        String source = "";
        if (sourceId != null) {
            // A document change names the document; a record change names its sending system in the event.
            p.addValue("sourceId", sourceId).addValue("sourceText", sourceId.toString());
            join = " left join documents d on a.target_type='DOCUMENT' and d.tenant_id=a.tenant_id"
                    + " and d.document_id=cast(a.target_id as uuid)";
            source = " and (d.source_id=:sourceId or (a.target_type='RECORD_SENSITIVITY'"
                    + " and a.safe_details ->> 'sourceId'=:sourceText))";
        }
        // Task 0033, part 2: a sending system's count also holds a contract's first level above Standard from a
        // delivery (RECORD_SENSITIVITY_SET, the migration case). A first level at Standard changes nothing.
        return jdbc.queryForObject("""
                select count(*) filter (where a.action in\s""" + RAISED + """
                 and a.actor_type='USER') as steward,
                       count(*) filter (where a.actor_type='SOURCE' and (a.action in\s""" + RAISED + """
                 or (a.action='RECORD_SENSITIVITY_SET' and a.safe_details ->> 'level' in\s""" + ABOVE_STANDARD + """
                ))) as source,
                       count(*) filter (where a.action in\s""" + LOWERED + """
                ) as lowered
                  from audit_events a""" + join + """

                 where a.tenant_id=:tenantId and a.outcome='SUCCESS' and a.occurred_at>=:from and a.occurred_at<:to
                   and a.action in ('DOCUMENT_SENSITIVITY_RAISED', 'RECORD_SENSITIVITY_RAISED', 'RECORD_SENSITIVITY_SET',
                                    'DOCUMENT_SENSITIVITY_LOWERED', 'RECORD_SENSITIVITY_LOWERED')""" + source, p,
                (rs, row) -> new LevelChanges(rs.getLong("steward"), rs.getLong("source"), rs.getLong("lowered")));
    }

    /**
     * Task 0033, part 2: only the open action (attempt OPEN, one per attempt to open a document) is counted, not pages,
     * previews, text search, level-change requests or any other attempt a refusal can name, so each count is one try
     * to open a document. SDV-SEN-009 (approval needed) stays out.
     */
    @Override
    public List<AttemptCount> countRestrictedAttempts(UUID tenantId, UUID sourceId, Instant from, Instant to) {
        MapSqlParameterSource p = range(tenantId, from, to);
        String join = "";
        if (sourceId != null) {
            p.addValue("sourceId", sourceId);
            join = " join documents d on d.tenant_id=a.tenant_id and d.document_id=cast(a.target_id as uuid)"
                    + " and d.source_id=:sourceId";
        }
        return jdbc.query("""
                select coalesce(a.safe_details -> 'roles', '[]'::jsonb)::text as roles, count(*) as attempts,
                       count(distinct a.actor_id) as people
                  from audit_events a""" + join + """

                 where a.tenant_id=:tenantId and a.action='DOCUMENT_ACCESS_REFUSED' and a.occurred_at>=:from
                   and a.occurred_at<:to and a.safe_details ->> 'code'='SDV-SEN-008'
                   and a.safe_details ->> 'attempt'='OPEN'
                 group by 1
                """, p, (rs, row) -> new AttemptCount(roles(rs.getString("roles")), rs.getLong("attempts"),
                rs.getLong("people")));
    }

    @Override
    public Optional<String> findPrimaryIdentifierLabel(UUID tenantId, UUID sourceId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenantId", tenantId);
        String source = "";
        if (sourceId != null) {
            p.addValue("sourceId", sourceId);
            source = " and f.source_id=:sourceId";
        }
        // Each sending system's primary identifier (its first business identifier in display order); the label most
        // of them use.
        return jdbc.query("""
                select label from (
                    select distinct on (f.source_id) f.source_id, f.display_label as label
                      from metadata_field_definitions f
                      left join metadata_field_display_order o
                        on o.tenant_id=f.tenant_id and o.source_id=f.source_id and o.field_key=f.field_key
                     where f.tenant_id=:tenantId and f.business_identifier and f.active""" + source + """

                     order by f.source_id, o.display_position nulls last, f.field_key, f.version desc) x
                 group by label
                 order by count(*) desc, label
                 limit 1
                """, p, (rs, row) -> rs.getString("label")).stream().findFirst();
    }

    private static MapSqlParameterSource range(UUID tenantId, Instant from, Instant to) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to));
    }

    static int rank(String level) {
        return switch (level) {
            case "STANDARD" -> 0;
            case "CONFIDENTIAL" -> 1;
            case "HIGHLY_CONFIDENTIAL" -> 2;
            default -> throw new InternalCheckFailed("A record level isn't one of the three: " + level);
        };
    }

    /** The roles a refusal named: a JSON array of role names; anything else names none. */
    static List<String> roles(String json) {
        try {
            JsonNode node = JSON.readTree(json);
            if (node == null || !node.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode role : node) if (role.isTextual()) out.add(role.asText());
            return out.stream().sorted().distinct().toList();
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new InternalCheckFailed("The record levels could not be passed to the count.", ex);
        }
    }

    /** Task 0053: the effective-level part of a query: "with ... submission_levels as (...)", and how many records carry a level. */
    record LevelSql(String with, int recordsWithLevel) { }

    /** A stored document {@code d}'s effective level (0 Standard, 1 Confidential, 2 Highly Confidential), joined to {@code sl}. */
    static final String LEVEL_OF_D = "greatest(case d.sensitivity_level when 'STANDARD' then 0 when 'CONFIDENTIAL' then 1 else 2 end, "
            + "coalesce(sl.lvl, 0))";

    /**
     * Task 0053 (from task 0033's count, unchanged): reads the tenant's record levels above Standard, parses their keys,
     * and returns the "with" part naming each submission's record level ({@code submission_levels}), with its
     * parameters added to {@code p} (which must hold :tenantId; and :sourceId when {@code sourceId} is set).
     */
    static LevelSql effectiveLevels(NamedParameterJdbcTemplate jdbc, UUID tenantId, UUID sourceId, MapSqlParameterSource p) {
        String recordSource = sourceId == null ? "" : " and source_id=:sourceId";
        record Level(UUID sourceId, String key, String level) { }
        List<Level> levels = jdbc.query("select source_id, record_key, sensitivity_level from record_sensitivity_levels"
                        + " where tenant_id=:tenantId" + recordSource, p,
                (rs, row) -> new Level(rs.getObject("source_id", UUID.class), rs.getString("record_key"),
                        rs.getString("sensitivity_level")));

        // The records above Standard: their parts (identifier records) or their one submission.
        List<Map<String, Object>> parts = new ArrayList<>();
        List<Map<String, Object>> records = new ArrayList<>();
        int rec = 0;
        for (Level level : levels) {
            int rank = rank(level.level());
            if (rank == 0) continue;
            RecordKey.Parsed parsed;
            try {
                parsed = RecordKey.parse(level.key());
            } catch (RuntimeException ex) {
                throw new InternalCheckFailed("A record level names a record key that can't be read, so the documents "
                        + "can't be counted by level.", ex);
            }
            rec++;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rec", rec);
            r.put("lvl", rank);
            r.put("n", parsed.identifiers().size());
            r.put("submission_id", parsed.submissionId() == null ? null : parsed.submissionId().toString());
            records.add(r);
            for (RecordKey.Part part : parsed.identifiers()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("rec", rec);
                x.put("source_id", level.sourceId().toString());
                x.put("field_key", part.fieldKey());
                x.put("value", part.normalizedValue());
                parts.add(x);
            }
        }
        p.addValue("parts", json(parts)).addValue("records", json(records));
        return new LevelSql("""
                with parts as (
                    select * from jsonb_to_recordset(cast(:parts as jsonb)) as x(rec int, source_id uuid, field_key text, value text)
                ), records as (
                    select * from jsonb_to_recordset(cast(:records as jsonb)) as x(rec int, lvl int, n int, submission_id uuid)
                ), hits as (
                    select pt.rec, v.source_id, v.submission_id, count(distinct (pt.field_key, pt.value)) as n
                      from parts pt
                      join business_metadata_values v
                        on v.tenant_id=:tenantId and v.source_id=pt.source_id
                       and v.field_key=pt.field_key and v.normalized_value=pt.value
                      join metadata_field_definitions f
                        on f.tenant_id=v.tenant_id and f.source_id=v.source_id and f.definition_id=v.definition_id
                       and f.field_key=v.field_key and f.version=v.definition_version and f.business_identifier
                     group by pt.rec, v.source_id, v.submission_id
                ), matched as (
                    select h.rec, h.submission_id
                      from hits h
                      join records r on r.rec=h.rec and r.n=h.n
                     where (select count(*)
                              from business_metadata_values iv
                              join metadata_field_definitions idf
                                on idf.tenant_id=iv.tenant_id and idf.source_id=iv.source_id
                               and idf.definition_id=iv.definition_id and idf.field_key=iv.field_key
                               and idf.version=iv.definition_version and idf.business_identifier
                             where iv.tenant_id=:tenantId and iv.source_id=h.source_id and iv.submission_id=h.submission_id
                               and iv.normalized_value ~ '\\S') = r.n
                    union all
                    select r.rec, r.submission_id from records r where r.submission_id is not null
                ), submission_levels as (
                    select m.submission_id, max(r.lvl) as lvl
                      from matched m join records r on r.rec=m.rec
                     group by m.submission_id
                )
                """, levels.size());
    }
}
