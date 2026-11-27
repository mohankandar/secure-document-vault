package com.sdv.platform.application.reports;

import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.reports.JdbcActivityReportRepository;
import com.sdv.platform.persistence.reports.JdbcIntakeReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0054: the Activity report against a real PostgreSQL database (changeset 041), from the audit trail.
 * <ol>
 *   <li>Exact counts on a small entity (America/New_York): opens, prints and downloads by role and level, an old open
 *       without roles; opens refused for the level or a quarantine (a refused print isn't an open); the busiest people;
 *       Highly Confidential requests and grants (a lowering request isn't one); a failed open and another entity's
 *       events never count. Yesterday comes from the kept day, today from the audit trail.</li>
 *   <li>A busy month: {@link #AGENTS} agents, {@link #PER_DAY} opens each a day for 30 days (SDV_IT_ACTIVITY_PER_DAY
 *       sets another number). The days are kept in the background; the report for the month and for the last 7 days
 *       answers within {@link #SCREEN_MS}.</li>
 * </ol>
 * Runs only when SDV_IT_JDBC_URL is set to the scratch database sdv_schema_test; never against the sdv database.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class ActivityReportPostgresTest {
    private static final UUID T = UUID.fromString("10000000-0000-0000-0000-000000005401");
    private static final UUID TX = UUID.fromString("10000000-0000-0000-0000-000000005402");
    private static final UUID TB = UUID.fromString("10000000-0000-0000-0000-000000005403");
    private static final ZoneId NY = ZoneId.of("America/New_York");
    static final int AGENTS = 10_000;
    private static final int PER_DAY = Integer.parseInt(System.getenv().getOrDefault("SDV_IT_ACTIVITY_PER_DAY", "5"));
    /** The agreed time for a screen's API (task 0054, as tasks 0052 and 0053). */
    static final long SCREEN_MS = 1000;

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final ActivityReportService service = new ActivityReportService(new JdbcActivityReportRepository(jdbc),
            new JdbcIntakeReportRepository(jdbc), new JdbcFoundationRepository(jdbc), new DataSourceTransactionManager(dataSource),
            Clock.systemUTC());

    /** Task 0081: no zone rows are left for the other tests on this database. */
    @org.junit.jupiter.api.AfterEach
    void removeZoneRows() {
        IdentityZones.removeAll(jdbc.getJdbcTemplate());
    }

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private void tenant(UUID tenant, String key, String zone) {
        sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('"
                + tenant + "', '" + key + "', '" + key + "', 'ACTIVE')");
        // Task 0081: the entity's own zone is identity's.
        if (zone != null) IdentityZones.set(jdbc.getJdbcTemplate(), tenant, zone, null);
    }

    /** One audit event of the entity: who, action, outcome, when, details (JSON). */
    private void event(UUID tenant, String who, String action, String outcome, Instant at, String details) {
        jdbc.update("insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, actor_display_name, action, target_type, "
                        + "target_id, occurred_at, correlation_id, outcome, safe_details) values (gen_random_uuid(), :t, 'USER', :who, :name, "
                        + ":action, 'DOCUMENT', :target, :at, 'c', :outcome, cast(:details as jsonb))",
                new MapSqlParameterSource().addValue("t", tenant).addValue("who", who).addValue("name", who.substring(0, 1).toUpperCase() + who.substring(1) + " Person")
                        .addValue("action", action).addValue("target", UUID.randomUUID().toString()).addValue("at", java.sql.Timestamp.from(at))
                        .addValue("outcome", outcome).addValue("details", details));
    }

    private static String opened(String level, String... roles) {
        return "{\"pageCount\":2,\"sensitivityLevel\":\"" + level + "\"" + (roles.length == 0 ? "" : ",\"roles\":[\"" + String.join("\",\"", roles) + "\"]") + "}";
    }

    @Test
    void theCountsAreExactAndABusyMonthAnswersQuickly() throws Exception {
        // ------------------------------------------------------------ 1. exact counts
        tenant(T, "activity-0054", "America/New_York");
        tenant(TX, "activity-0054-other", null);
        Instant yesterday = LocalDate.now(NY).minusDays(1).atTime(12, 0).atZone(NY).toInstant();
        Instant today = Instant.now().minusSeconds(30);
        for (int i = 0; i < 3; i++) event(T, "bob", "DOCUMENT_VIEW_OPENED", "SUCCESS", yesterday, opened("STANDARD", "BUSINESS_USER"));
        event(T, "bob", "DOCUMENT_VIEW_OPENED", "SUCCESS", today, opened("CONFIDENTIAL", "BUSINESS_USER"));
        event(T, "dee", "DOCUMENT_VIEW_OPENED", "SUCCESS", today, opened("HIGHLY_CONFIDENTIAL", "DOCUMENT_STEWARD", "RESTRICTED_STEWARD"));
        event(T, "old", "DOCUMENT_VIEW_OPENED", "SUCCESS", yesterday, "{\"pageCount\":1,\"sensitivityLevel\":\"STANDARD\"}");
        event(T, "bob", "DOCUMENT_VIEW_OPENED", "FAILED", today, opened("STANDARD", "BUSINESS_USER"));
        event(T, "dee", "DOCUMENT_PRINTED", "SUCCESS", yesterday, "{\"currentApproved\":true,\"sensitivityLevel\":\"HIGHLY_CONFIDENTIAL\",\"roles\":[\"DOCUMENT_STEWARD\"]}");
        event(T, "bob", "DOCUMENT_DOWNLOADED", "SUCCESS", today, "{\"sensitivityLevel\":\"STANDARD\",\"roles\":[\"BUSINESS_USER\"]}");
        for (int i = 0; i < 2; i++) event(T, "bob", "DOCUMENT_ACCESS_REFUSED", "DENIED", yesterday,
                "{\"level\":\"HIGHLY_CONFIDENTIAL\",\"attempt\":\"OPEN\",\"code\":\"SDV-SEN-008\",\"roles\":[\"BUSINESS_USER\"]}");
        event(T, "bob", "DOCUMENT_ACCESS_REFUSED", "DENIED", today, "{\"level\":\"STANDARD\",\"attempt\":\"OPEN\",\"code\":\"SDV-QUA-001\",\"roles\":[\"BUSINESS_USER\"]}");
        event(T, "bob", "DOCUMENT_ACCESS_REFUSED", "DENIED", today, "{\"level\":\"HIGHLY_CONFIDENTIAL\",\"attempt\":\"PRINT\",\"code\":\"SDV-SEN-008\",\"roles\":[\"BUSINESS_USER\"]}");
        for (int i = 0; i < 2; i++) event(T, "dee", "ACCESS_REQUEST_CREATED", "SUCCESS", yesterday, "{\"kind\":\"DOWNLOAD\",\"requestedLevel\":\"HIGHLY_CONFIDENTIAL\"}");
        event(T, "dee", "ACCESS_REQUEST_CREATED", "SUCCESS", yesterday, "{\"kind\":\"LOWER_LEVEL\",\"requestedLevel\":\"STANDARD\"}");
        event(T, "ada", "ACCESS_REQUEST_APPROVED", "SUCCESS", today, "{\"kind\":\"DOWNLOAD\"}");
        event(T, "ada", "ACCESS_REQUEST_DECLINED", "SUCCESS", today, "{\"kind\":\"PRINT\"}");
        event(T, "dee", "SENSITIVE_ACCESS_GRANT_USED", "SUCCESS", today, "{\"kind\":\"DOWNLOAD\"}");
        event(T, "dee", "SENSITIVE_ACCESS_GRANT_RETURNED", "SUCCESS", today, "{\"kind\":\"DOWNLOAD\"}");
        event(TX, "eve", "DOCUMENT_VIEW_OPENED", "SUCCESS", today, opened("STANDARD", "BUSINESS_USER"));

        ActivityReportService.ActivityReport r = service.report(T, null, null);
        assertEquals("America/New_York", r.timeZone());
        assertEquals(LocalDate.now(NY).minusDays(6).toString(), r.from(), "the last 7 days");
        assertEquals(LocalDate.now(NY).minusDays(1).toString(), r.keptThrough(), "yesterday from the kept day, today from the audit");
        assertEquals(1L, jdbc.queryForObject("select count(*) from report_activity_days where tenant_id=:t and day=:d",
                new MapSqlParameterSource("t", T).addValue("d", java.sql.Date.valueOf(LocalDate.now(NY).minusDays(1))), Long.class));
        ActivityReportService.AccessTotal opened = r.accessTotals().get(0);
        assertEquals(6, opened.total(), "3 + 1 + 1 + 1 (the failed one doesn't count)");
        assertEquals(4, opened.standard());
        assertEquals(1, opened.confidential());
        assertEquals(1, opened.highlyConfidential());
        assertTrue(r.access().stream().anyMatch(a -> a.kind().equals("OPENED") && a.roles().isEmpty() && a.total() == 1), "role not recorded");
        assertTrue(r.access().stream().anyMatch(a -> a.kind().equals("OPENED") && a.roles().equals(List.of("BUSINESS_USER")) && a.total() == 4));
        assertEquals(1, r.accessTotals().get(1).highlyConfidential(), "printed");
        assertEquals(1, r.accessTotals().get(2).standard(), "downloaded");
        ActivityReportService.RefusalRow refused = r.refusals().getFirst();
        assertEquals(List.of("BUSINESS_USER"), refused.roles());
        assertEquals(2, refused.level());
        assertEquals(1, refused.quarantine(), "a refused print isn't an open");
        assertEquals("Bob Person", r.busiest().getFirst().name());
        assertEquals(4, r.busiest().getFirst().total());
        assertEquals(3, r.busiest().size(), "bob, dee and old");
        ActivityReportService.Approvals a = r.approvals();
        assertEquals(2, a.requested(), "a lowering request isn't one");
        assertEquals(1, a.approved());
        assertEquals(1, a.declined());
        assertEquals(0, a.expired());
        assertEquals(1, a.grantsUsed());
        assertEquals(1, a.grantsReturned());
        ActivityReportService.Export export = service.export(T, "ada", "corr", null, null);
        String csv = new String(export.csv(), StandardCharsets.UTF_8).replace("\"", "");
        assertTrue(csv.contains("Busiest people,1. Bob Person (Business user),Standard,Opens,3,"));
        assertFalse(csv.contains("Eve Person"), "another entity");
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_events where tenant_id=:t and action='ACTIVITY_REPORT_EXPORTED' "
                + "and safe_details->>'fingerprint'=:f", new MapSqlParameterSource("t", T).addValue("f", export.fingerprint()), Long.class));

        // The other PostgreSQL tests (same database) expect every entity on the install default time zone.
        IdentityZones.clear(jdbc.getJdbcTemplate(), T);

        // ------------------------------------------------------------ 2. a busy month at 10,000 agents
        tenant(TB, "activity-0054-busy", null);
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        String dayStart = "'" + todayUtc + "T00:00:00Z'::timestamptz";
        long seedStart = System.nanoTime();
        sql("insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, actor_display_name, action, target_type, target_id, "
                + "occurred_at, correlation_id, outcome, safe_details) select gen_random_uuid(), '" + TB + "', 'USER', 'agent-' || a, "
                + "'Agent ' || a, 'DOCUMENT_VIEW_OPENED', 'DOCUMENT', md5(a || '-' || d || '-' || e), " + dayStart
                + " - (d * 86400) * interval '1 second' + ((a * 7 + e * 977) % 86000) * interval '1 second', 'c', 'SUCCESS', "
                + "jsonb_build_object('pageCount', 2, 'sensitivityLevel', case when e % 10 = 0 then 'HIGHLY_CONFIDENTIAL' when e % 3 = 0 "
                + "then 'CONFIDENTIAL' else 'STANDARD' end, 'roles', jsonb_build_array(case when a % 50 = 0 then 'DOCUMENT_STEWARD' else 'BUSINESS_USER' end)) "
                + "from generate_series(1, " + AGENTS + ") a, generate_series(1, 30) d, generate_series(1, " + PER_DAY + ") e");
        // Some of today, from the audit trail.
        sql("insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, actor_display_name, action, target_type, target_id, "
                + "occurred_at, correlation_id, outcome, safe_details) select gen_random_uuid(), '" + TB + "', 'USER', 'agent-' || a, "
                + "'Agent ' || a, 'DOCUMENT_VIEW_OPENED', 'DOCUMENT', md5('today-' || a), least(now() - interval '1 second', " + dayStart
                + " + (a % 3600) * interval '1 second'), 'c', 'SUCCESS', '{\"pageCount\":1,\"sensitivityLevel\":\"STANDARD\",\"roles\":[\"BUSINESS_USER\"]}'::jsonb "
                + "from generate_series(1, " + AGENTS + ") a");
        sql("analyze audit_events");
        long seedMs = (System.nanoTime() - seedStart) / 1_000_000;
        long events = (long) AGENTS * 30 * PER_DAY + AGENTS;

        long keepStart = System.nanoTime();
        int kept = 0;
        while (true) {
            int n = service.keepClosedDays(14);
            kept += n;
            if (n == 0) break;
        }
        long keepMs = (System.nanoTime() - keepStart) / 1_000_000;
        assertTrue(kept >= 30, "the 30 closed days were kept (" + kept + ")");

        String monthFrom = todayUtc.minusDays(30).toString();
        String monthTo = todayUtc.toString();
        ActivityReportService.ActivityReport month = timed(() -> service.report(TB, monthFrom, monthTo));
        long monthMs = lastMs;
        // Each kept day holds exactly what the audit trail holds for that day.
        List<String> mismatches = jdbc.query("""
                select d.day, d.events, (select count(*) from audit_events a where a.tenant_id=d.tenant_id
                    and a.action='DOCUMENT_VIEW_OPENED' and a.occurred_at >= d.day::timestamp at time zone 'UTC'
                    and a.occurred_at < (d.day + 1)::timestamp at time zone 'UTC') as audit
                  from report_activity_days d where d.tenant_id=:t order by d.day
                """, new MapSqlParameterSource("t", TB), (rs, row) -> rs.getLong("events") == rs.getLong("audit") ? null
                : rs.getDate("day") + ": kept " + rs.getLong("events") + ", audit " + rs.getLong("audit"))
                .stream().filter(java.util.Objects::nonNull).toList();
        assertEquals(List.of(), mismatches, "kept days against the audit trail");
        Long inAudit = jdbc.queryForObject("select count(*) from audit_events where tenant_id=:t and action='DOCUMENT_VIEW_OPENED'",
                new MapSqlParameterSource("t", TB), Long.class);
        Long keptSum = jdbc.queryForObject("select coalesce(sum(events), 0) from report_activity_days where tenant_id=:t",
                new MapSqlParameterSource("t", TB), Long.class);
        Long keptDays = jdbc.queryForObject("select count(*) from report_activity_days where tenant_id=:t",
                new MapSqlParameterSource("t", TB), Long.class);
        Long todayAudit = jdbc.queryForObject("select count(*) from audit_events where tenant_id=:t and action='DOCUMENT_VIEW_OPENED' "
                + "and occurred_at >= " + dayStart, new MapSqlParameterSource("t", TB), Long.class);
        assertEquals(events, month.accessTotals().get(0).total(), "every open of the month and today (audit " + inAudit + ", kept "
                + keptSum + " in " + keptDays + " days, today in the audit " + todayAudit + ", kept through " + month.keptThrough() + ")");
        assertEquals(ActivityReportService.BUSIEST, month.busiest().size());
        assertTrue(monthMs <= SCREEN_MS, "the month took " + monthMs + " ms (agreed: " + SCREEN_MS + " ms)");
        timed(() -> service.report(TB, null, null));
        long weekMs = lastMs;
        assertTrue(weekMs <= SCREEN_MS, "the last 7 days took " + weekMs + " ms (agreed: " + SCREEN_MS + " ms)");

        List<String> lines = new ArrayList<>();
        lines.add("Activity report (task 0054): " + AGENTS + " agents, " + PER_DAY + " opens each a day for 30 days, and " + AGENTS
                + " today (" + events + " audit events). Report APIs: median of 3 after one warm-up.");
        lines.add(String.format("%-52s %,10d", "seed (test set-up)", seedMs));
        lines.add(String.format("%-52s %,10d", "keeping the 30 closed days (background)", keepMs));
        lines.add(String.format("%-52s %,10d", "report, 31 days (API)", monthMs));
        lines.add(String.format("%-52s %,10d", "report, last 7 days (API)", weekMs));
        lines.forEach(line -> System.out.println("ACTIVITY-REPORT " + line));
        Files.write(Path.of("target", "activity-report-times.txt"), lines, StandardCharsets.UTF_8);
    }

    private long lastMs;

    private <R> R timed(java.util.function.Supplier<R> call) {
        call.get();
        long[] ms = new long[3];
        R result = null;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            result = call.get();
            ms[i] = (System.nanoTime() - start) / 1_000_000;
        }
        java.util.Arrays.sort(ms);
        lastMs = ms[1];
        return result;
    }
}
