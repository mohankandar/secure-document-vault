package com.sdv.platform.persistence.reports;

import com.sdv.platform.application.port.ActivityReportRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 0054: the Activity report's reads. From audit_events only these are read: the action, outcome, time, the
 * actor's ID and name (opens only), and from the details the level, the roles, the refusal's attempt and code, and an
 * access request's kind. Never a target, title, file name, content or reason.
 * <p>
 * Each audit event counts as one kind:
 * <ul>
 *   <li>OPENED, PRINTED, DOWNLOADED: DOCUMENT_VIEW_OPENED, DOCUMENT_PRINTED, DOCUMENT_DOWNLOADED that succeeded, with
 *       their level and roles (roles recorded since task 0054);</li>
 *   <li>REFUSED_LEVEL, REFUSED_QUARANTINE: DOCUMENT_ACCESS_REFUSED when opening, with SDV-SEN-008 or SDV-QUA-001;</li>
 *   <li>REQUESTED, APPROVED, DECLINED, EXPIRED: the access requests that a Highly Confidential print or download needs
 *       (kind DOWNLOAD or PRINT); GRANT_USED, GRANT_RETURNED: their one-time grants.</li>
 * </ul>
 * A day's audit is read through idx_audit_events_tenant_action_time; the kept days through
 * idx_report_activity_counts_day.
 */
@Repository
public class JdbcActivityReportRepository implements ActivityReportRepository {
    static final String ACTIONS = "('DOCUMENT_VIEW_OPENED', 'DOCUMENT_PRINTED', 'DOCUMENT_DOWNLOADED', 'DOCUMENT_ACCESS_REFUSED', "
            + "'ACCESS_REQUEST_CREATED', 'ACCESS_REQUEST_APPROVED', 'ACCESS_REQUEST_DECLINED', 'ACCESS_REQUEST_EXPIRED', "
            + "'SENSITIVE_ACCESS_GRANT_USED', 'SENSITIVE_ACCESS_GRANT_RETURNED')";
    private static final String LEVELS = "('STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL')";

    /**
     * The audit events of {@code [:liveFrom, :liveTo)}, counted as the kept days are: kind, roles, level, actor, events.
     * Roles are kept as recorded (the text of the event's JSON array, '' when none); the service reads them, so no
     * event's details are unpacked here.
     */
    static final String AUDIT_ROWS = """
            select k.kind,
                   coalesce((a.safe_details->'roles')::text, '') as roles,
                   case when k.kind in ('OPENED', 'PRINTED', 'DOWNLOADED') and a.safe_details->>'sensitivityLevel' in\s""" + LEVELS + """
             then a.safe_details->>'sensitivityLevel'
                        when k.kind in ('REFUSED_LEVEL', 'REFUSED_QUARANTINE') and a.safe_details->>'level' in\s""" + LEVELS + """
             then a.safe_details->>'level' end as level,
                   case when k.kind = 'OPENED' then a.actor_id end as actor_id,
                   max(case when k.kind = 'OPENED' then a.actor_display_name end) as actor_name,
                   count(*) as events
              from audit_events a
             cross join lateral (select case
                    when a.outcome = 'SUCCESS' and a.action = 'DOCUMENT_VIEW_OPENED' then 'OPENED'
                    when a.outcome = 'SUCCESS' and a.action = 'DOCUMENT_PRINTED' then 'PRINTED'
                    when a.outcome = 'SUCCESS' and a.action = 'DOCUMENT_DOWNLOADED' then 'DOWNLOADED'
                    when a.action = 'DOCUMENT_ACCESS_REFUSED' and a.safe_details->>'attempt' = 'OPEN'
                         and a.safe_details->>'code' = 'SDV-SEN-008' then 'REFUSED_LEVEL'
                    when a.action = 'DOCUMENT_ACCESS_REFUSED' and a.safe_details->>'attempt' = 'OPEN'
                         and a.safe_details->>'code' = 'SDV-QUA-001' then 'REFUSED_QUARANTINE'
                    when a.action = 'ACCESS_REQUEST_CREATED' and a.safe_details->>'kind' in ('DOWNLOAD', 'PRINT') then 'REQUESTED'
                    when a.action = 'ACCESS_REQUEST_APPROVED' and a.safe_details->>'kind' in ('DOWNLOAD', 'PRINT') then 'APPROVED'
                    when a.action = 'ACCESS_REQUEST_DECLINED' and a.safe_details->>'kind' in ('DOWNLOAD', 'PRINT') then 'DECLINED'
                    when a.action = 'ACCESS_REQUEST_EXPIRED' and a.safe_details->>'kind' in ('DOWNLOAD', 'PRINT') then 'EXPIRED'
                    when a.action = 'SENSITIVE_ACCESS_GRANT_USED' then 'GRANT_USED'
                    when a.action = 'SENSITIVE_ACCESS_GRANT_RETURNED' then 'GRANT_RETURNED'
                  end as kind) k
             where a.tenant_id = :tenantId and a.action in\s""" + ACTIONS + """

               and a.occurred_at >= :liveFrom and a.occurred_at < :liveTo and k.kind is not null
             group by 1, 2, 3, 4
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcActivityReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<LocalDate, String> findKeptDays(UUID tenantId, LocalDate from, LocalDate to) {
        Map<LocalDate, String> kept = new LinkedHashMap<>();
        jdbc.query("select day, time_zone from report_activity_days where tenant_id=:tenantId and day between :from and :to order by day",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("from", Date.valueOf(from))
                        .addValue("to", Date.valueOf(to)),
                rs -> { kept.put(rs.getDate("day").toLocalDate(), rs.getString("time_zone")); });
        return kept;
    }

    @Override
    public long keepDay(UUID tenantId, LocalDate day, String timeZone, Instant start, Instant end, Instant countedAt) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("day", Date.valueOf(day))
                .addValue("liveFrom", Timestamp.from(start)).addValue("liveTo", Timestamp.from(end))
                .addValue("zone", timeZone).addValue("at", Timestamp.from(countedAt));
        // One day at a time, under the day's row lock (two counts of the same day never mix).
        jdbc.update("""
                insert into report_activity_days(tenant_id, day, time_zone, counted_at, events) values (:tenantId, :day, :zone, :at, 0)
                on conflict (tenant_id, day) do update set time_zone=excluded.time_zone, counted_at=excluded.counted_at
                """, p);
        jdbc.update("delete from report_activity_counts where tenant_id=:tenantId and day=:day", p);
        jdbc.update("delete from report_activity_people where tenant_id=:tenantId and day=:day", p);
        jdbc.update("insert into report_activity_counts(tenant_id, day, kind, roles, level, events) "
                + "select :tenantId, :day, kind, roles, level, sum(events) from (" + AUDIT_ROWS + ") x group by kind, roles, level", p);
        jdbc.update("insert into report_activity_people(tenant_id, day, actor_id, actor_name, roles, standard, confidential, "
                + "highly_confidential, level_not_recorded, total) select :tenantId, :day, actor_id, actor_name, roles, standard, "
                + "confidential, highly, not_recorded, total from (" + PEOPLE_ROWS + ") x", p);
        Long events = jdbc.queryForObject("select coalesce(sum(events), 0) from report_activity_counts where tenant_id=:tenantId and day=:day",
                p, Long.class);
        jdbc.update("update report_activity_days set events=:events where tenant_id=:tenantId and day=:day",
                p.addValue("events", events == null ? 0 : events));
        return events == null ? 0 : events;
    }

    /**
     * A day's opens per person from the audit rows of {@code [:liveFrom, :liveTo)}: sign-in ID, name, roles (one of
     * them when a person opened with several), opens by level.
     */
    static final String PEOPLE_ROWS = """
            select actor_id, max(actor_name) as actor_name, max(roles) as roles,
                   coalesce(sum(events) filter (where level = 'STANDARD'), 0) as standard,
                   coalesce(sum(events) filter (where level = 'CONFIDENTIAL'), 0) as confidential,
                   coalesce(sum(events) filter (where level = 'HIGHLY_CONFIDENTIAL'), 0) as highly,
                   coalesce(sum(events) filter (where level is null), 0) as not_recorded,
                   sum(events) as total
              from (""" + AUDIT_ROWS + """
            ) o where kind = 'OPENED' and actor_id is not null group by actor_id
            """;

    private static boolean kept(Window w) {
        return w.lastKeptDay() != null && !w.lastKeptDay().isBefore(w.firstDay());
    }

    private static void keptDays(Window w, MapSqlParameterSource p) {
        p.addValue("firstDay", Date.valueOf(w.firstDay())).addValue("lastKeptDay", Date.valueOf(w.lastKeptDay()));
    }

    @Override
    public List<Row> live(UUID tenantId, Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) return List.of();
        return jdbc.query(AUDIT_ROWS, new MapSqlParameterSource().addValue("tenantId", tenantId)
                        .addValue("liveFrom", Timestamp.from(from)).addValue("liveTo", Timestamp.from(to)),
                (rs, row) -> new Row(rs.getString("kind"), rs.getString("roles"), rs.getString("level"), rs.getString("actor_id"),
                        rs.getString("actor_name"), rs.getLong("events")));
    }

    @Override
    public List<Count> count(UUID tenantId, Window window) {
        if (!kept(window)) return List.of();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenantId", tenantId);
        keptDays(window, p);
        return jdbc.query("select kind, roles, level, sum(events) as events from report_activity_counts"
                        + " where tenant_id=:tenantId and day between :firstDay and :lastKeptDay group by 1, 2, 3 order by 1, 2, 3", p,
                (rs, row) -> new Count(rs.getString("kind"), rs.getString("roles"), rs.getString("level"), rs.getLong("events")));
    }

    @Override
    public List<Person> busiest(UUID tenantId, Window window, List<Person> live, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("limit", limit)
                .addValue("live", livePeople(live));
        List<String> parts = new java.util.ArrayList<>();
        if (kept(window)) {
            keptDays(window, p);
            parts.add("select actor_id, actor_name, roles, standard, confidential, highly_confidential as highly, total"
                    + " from report_activity_people where tenant_id=:tenantId and day between :firstDay and :lastKeptDay");
        }
        // Today's people, worked out once by the service from the audit part, as one parameter.
        parts.add("select actor_id, actor_name, roles, standard, confidential, highly, total from jsonb_to_recordset(cast(:live as jsonb))"
                + " as x(actor_id text, actor_name text, roles text, standard bigint, confidential bigint, highly bigint, total bigint)");
        // Roles: the smallest and largest seen (a person rarely opens with more than one set of roles).
        return jdbc.query("with rows as (" + String.join(" union all ", parts) + """
                ) select actor_id, max(actor_name) as actor_name, min(roles) || ';' || max(roles) as roles,
                         sum(standard) as standard, sum(confidential) as confidential, sum(highly) as highly,
                         sum(total) as total
                    from rows group by actor_id order by total desc, actor_id limit :limit
                """, p, (rs, row) -> new Person(rs.getString("actor_id"), rs.getString("actor_name"), rs.getString("roles"),
                rs.getLong("standard"), rs.getLong("confidential"), rs.getLong("highly"), rs.getLong("total")));
    }

    private static String livePeople(List<Person> live) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Person x : live == null ? List.<Person>of() : live) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("actor_id", x.actorId());
            r.put("actor_name", x.actorName());
            r.put("roles", x.roles() == null ? "" : x.roles());
            r.put("standard", x.standard());
            r.put("confidential", x.confidential());
            r.put("highly", x.highlyConfidential());
            r.put("total", x.total());
            rows.add(r);
        }
        try {
            return JSON.writeValueAsString(rows);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new com.sdv.platform.domain.error.InternalCheckFailed("Today's people could not be passed to the busiest list.");
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public List<UUID> findActiveTenants() {
        return jdbc.query("select tenant_id from tenants where status='ACTIVE' order by tenant_id",
                new MapSqlParameterSource(), (rs, row) -> rs.getObject(1, UUID.class));
    }
}
