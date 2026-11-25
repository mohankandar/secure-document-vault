package com.sdv.platform.persistence.reports;

import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.persistence.fields.CaseFieldSql;
import com.sdv.platform.domain.error.InternalCheckFailed;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
 * record's level; a level names its record by ID (task 0078), and every stored document is counted once at
 * greatest(own level, its record's level).
 */
@Repository
public class JdbcSensitivityReportRepository implements SensitivityReportRepository {
    private static final ObjectMapper JSON = VeroJson.mapper();
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
                 where r.tenant_id=:tenantId and r.kind not in ('REDACT_HELD', 'RELEASE_HOLD') and r.requested_at>=:from and r.requested_at<:to""" + source + """

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
        String source = "like 'source.%'";
        if (sourceId != null) {
            p.addValue("sourceId", sourceId);
            source = "='source.'||cast(:sourceId as text)";
        }
        // Each sending system's primary identifier field (the one rule, task 0056); the label most of them use.
        return jdbc.query("""
                select label from (
                    select distinct on (x.name) x.name, %s as label
                      from fld_field_definition f
                      join fld_field_extension x on x.definition_id=f.definition_id and x.name %s
                     where f.entity_uuid=:tenantId and f.product_code='%s' and f.active and not f.sensitive and %s
                     order by x.name, %s nulls last, f.field_key) x
                 group by label
                 order by count(*) desc, label
                 limit 1
                """.formatted(CaseFieldSql.LABEL, source, com.sdv.platform.domain.identity.VeroProduct.CODE, CaseFieldSql.IDENTIFIER, CaseFieldSql.PLACE), p, (rs, row) -> rs.getString("label")).stream().findFirst();
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
            for (JsonNode role : node) if (role.isString()) out.add(role.asString());
            return out.stream().sorted().distinct().toList();
        } catch (JacksonException ex) {
            return List.of();
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new InternalCheckFailed("The record levels could not be passed to the count.", ex);
        }
    }

    /** Task 0053: the effective-level part of a query: "with ... submission_levels as (...)", and how many records carry a level. */
    record LevelSql(String with, int recordsWithLevel) { }

    /** A stored document {@code d}'s effective level (0 Standard, 1 Confidential, 2 Highly Confidential), joined to {@code sl}. */
    static final String LEVEL_OF_D = "greatest(case d.sensitivity_level when 'STANDARD' then 0 when 'CONFIDENTIAL' then 1 else 2 end, "
            + "coalesce(sl.lvl, 0))";

    /**
     * Task 0053, 0078: reads the tenant's record levels and returns the "with" part naming each submission's record level above Standard
     * ({@code submission_levels}), with its parameters added to {@code p} (which must hold :tenantId; and :sourceId when {@code sourceId} is set).
     */
    static LevelSql effectiveLevels(NamedParameterJdbcTemplate jdbc, UUID tenantId, UUID sourceId, MapSqlParameterSource p) {
        String recordSource = sourceId == null ? "" : " and source_id=:sourceId";
        List<String> levels = jdbc.queryForList("select sensitivity_level from record_sensitivity_levels where tenant_id=:tenantId" + recordSource, p, String.class);
        levels.forEach(JdbcSensitivityReportRepository::rank); // a level that isn't one of the three stops the report
        return new LevelSql("""
                with submission_levels as (
                    select s.submission_id, case l.sensitivity_level when 'CONFIDENTIAL' then 1 else 2 end as lvl
                      from record_sensitivity_levels l
                      join submissions s on s.tenant_id=l.tenant_id and s.source_id=l.source_id and s.record_id=l.record_id
                     where l.tenant_id=:tenantId and l.sensitivity_level<>'STANDARD'
                )
                """, levels.size());
    }
}
