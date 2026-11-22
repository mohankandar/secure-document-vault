package com.sdv.platform.application.reports;

import com.sdv.platform.application.port.IntakeReportRepository.Item;
import com.sdv.platform.application.port.UnidentifiedIntakeAttemptRepository.Attempt;
import com.sdv.platform.persistence.reports.JdbcIntakeReportRepository;
import com.sdv.platform.persistence.reports.JdbcUnidentifiedIntakeAttemptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.sdv.platform.application.port.UnidentifiedIntakeAttemptRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0039 against a real PostgreSQL database (changeset 033), with the application's own repositories:
 * <ol>
 *   <li>unidentified_intake_attempts: an attempt with a tenant, one naming a tenant Vero doesn't have (kept without
 *       one), one without; a tenant reads its own and those without a tenant, never another tenant's; attempts older
 *       than 30 days are removed when a new one is written.</li>
 *   <li>The Daily intake report's SQL: a refused (INTAKE_REFUSED) and a failed (INTAKE_FAILED) intake request of a
 *       known sending system come back as REFUSED and FAILED with their codes and the delivery's request ID.</li>
 * </ol>
 * Runs only when SDV_IT_JDBC_URL is set. Test-SDV-SchemaUpgrade.ps1 sets it to its scratch database (sdv_schema_test)
 * after the schema step; it never points at the sdv database. In the normal build this test is skipped.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test")
class UnidentifiedIntakeAttemptPostgresTest {
    private static final UUID TA = UUID.fromString("10000000-0000-0000-0000-0000000039a1");
    private static final UUID TB = UUID.fromString("10000000-0000-0000-0000-0000000039b1");
    private static final UUID SA = UUID.fromString("20000000-0000-0000-0000-0000000039a1");

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final JdbcUnidentifiedIntakeAttemptRepository attempts = new JdbcUnidentifiedIntakeAttemptRepository(jdbc);
    private final JdbcIntakeReportRepository reports = new JdbcIntakeReportRepository(jdbc);
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private Attempt attempt(UUID tenant, Instant at, String code) {
        return Attempt.one(UUID.randomUUID(), tenant, at, "203.0.113.9", "POST", "/api/v1/ingestion/submissions/{id}/documents",
                401, code, "ref-" + code);
    }

    private void audit(String action, String outcome, String details, Instant at) {
        jdbc.update("""
                insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, action, target_type, target_id,
                                         occurred_at, correlation_id, outcome, safe_details)
                values (:id, :tenant, 'SOURCE', 'producer', :action, 'INGESTION_SOURCE', :source, :at, 'corr', :outcome,
                        cast(:details as jsonb))
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("tenant", TA)
                .addValue("action", action).addValue("source", SA.toString()).addValue("at", java.sql.Timestamp.from(at))
                .addValue("outcome", outcome).addValue("details", details));
    }

    @Test
    void attemptsArePerTenantOrForEveryoneAndTheReportCountsIntakeFailures() {
        for (UUID t : List.of(TA, TB)) {
            sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + t + "', 'k" + t.toString().substring(30)
                    + "', 'T', 'ACTIVE')");
        }
        sql("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values ('"
                + SA + "', '" + TA + "', 'loans-0039', 'Loans', 'ACTIVE', 'JWT_BEARER')");

        // 1. Attempts.
        attempts.record(attempt(null, now.minus(40, ChronoUnit.DAYS), "SDV-AUT-001")); // old: removed by the next write
        attempts.record(attempt(TA, now.minusSeconds(30), "SDV-INT-005"));
        attempts.record(attempt(TB, now.minusSeconds(20), "SDV-AUT-009"));
        attempts.record(attempt(UUID.randomUUID(), now.minusSeconds(10), "SDV-AUT-002")); // a tenant Vero doesn't have
        attempts.record(attempt(null, now, "SDV-AUT-003"));

        Instant week = now.minus(7, ChronoUnit.DAYS);
        List<Attempt> seenByA = attempts.findRecent(TA, week, 50);
        assertEquals(List.of("SDV-AUT-003", "SDV-AUT-002", "SDV-INT-005"), seenByA.stream().map(Attempt::code).toList(),
                "newest first: its own and those without a tenant, never TB's");
        assertNull(seenByA.get(1).tenantId(), "a tenant Vero doesn't have is kept as none");
        assertEquals(TA, seenByA.get(2).tenantId());
        assertEquals("/api/v1/ingestion/submissions/{id}/documents", seenByA.get(0).address());
        assertEquals("203.0.113.9", seenByA.get(0).callerAddress());
        assertEquals("ref-SDV-AUT-003", seenByA.get(0).reference());
        assertEquals(3, attempts.count(TA, week));
        assertEquals(List.of("SDV-AUT-003", "SDV-AUT-002", "SDV-AUT-009"), attempts.findRecent(TB, week, 50).stream().map(Attempt::code).toList());
        assertEquals(1, attempts.findRecent(TA, week, 1).size());
        Long old = jdbc.queryForObject("select count(*) from unidentified_intake_attempts where code='SDV-AUT-001'",
                new MapSqlParameterSource(), Long.class);
        assertEquals(0L, old, "attempts older than 30 days are removed");

        // 2. The report's SQL: refused and failed intake requests of a known sending system.
        audit("INTAKE_REFUSED", "DENIED", "{\"stage\":\"SUBMISSION\",\"sourceId\":\"" + SA + "\",\"submissionId\":null,"
                + "\"requestId\":\"req-0039-a\",\"documentRequestId\":null,\"status\":400,\"code\":\"SDV-SYS-015\"}", now.minusSeconds(5));
        audit("INTAKE_FAILED", "FAILED", "{\"stage\":\"SUBMISSION\",\"sourceId\":\"" + SA + "\",\"submissionId\":null,"
                + "\"requestId\":\"req-0039-b\",\"documentRequestId\":null,\"status\":500,\"code\":\"SDV-INT-010\"}", now.minusSeconds(4));
        List<Item> items = reports.findItems(TA, SA, now.minus(1, ChronoUnit.HOURS), now.plusSeconds(1), 100);
        assertEquals(List.of(Item.Kind.REFUSED, Item.Kind.FAILED), items.stream().map(Item::kind).toList());
        assertEquals(List.of("SDV-SYS-015", "SDV-INT-010"), items.stream().map(Item::refusalCode).toList());
        assertEquals(List.of("req-0039-a", "req-0039-b"), items.stream().map(Item::requestId).toList());
        assertEquals(List.of(SA, SA), items.stream().map(Item::sourceId).toList());
    }

    private static final UUID TC = UUID.fromString("10000000-0000-0000-0000-0000000039c1");
    private static final UUID TD = UUID.fromString("10000000-0000-0000-0000-0000000039d1");

    private long rows(String where) {
        Long n = jdbc.queryForObject("select count(*) from unidentified_intake_attempts where " + where,
                new MapSqlParameterSource(), Long.class);
        return n == null ? 0 : n;
    }

    /**
     * Task 0039 part 2 (owner answer 2 to report 0039, changeset 034): 1,000 requests in a minute from one caller to
     * one address with one code are one row with the count 1,000 and the last time, also when they arrive at once
     * (8 threads, the real "insert ... on conflict"). Another minute, another tenant: another row. Past 100 rows in a
     * minute, new callers are counted together, so the table can't grow without bound.
     */
    @Test
    void aFloodIsOneRowPerCallerAddressAndCodePerMinuteAndTheMinuteHasALimit() throws Exception {
        for (UUID t : List.of(TC, TD)) {
            sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + t + "', 'k" + t.toString().substring(30)
                    + "', 'T', 'ACTIVE')");
        }
        // 1. 1,000 requests in one minute, 50 ms apart, sent by 8 threads at once.
        Instant minute = now.minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getenv("SDV_IT_JDBC_URL"));
        config.setUsername(System.getenv("SDV_IT_JDBC_USER"));
        config.setPassword(System.getenv("SDV_IT_JDBC_PASSWORD"));
        config.setMaximumPoolSize(8);
        try (HikariDataSource pool = new HikariDataSource(config)) {
            var flood = new JdbcUnidentifiedIntakeAttemptRepository(new NamedParameterJdbcTemplate(pool));
            ExecutorService threads = Executors.newFixedThreadPool(8);
            try {
                List<Future<?>> sent = new ArrayList<>();
                for (int i = 0; i < 1000; i++) {
                    Instant at = minute.plusMillis(50L * i);
                    int n = i;
                    sent.add(threads.submit(() -> flood.record(Attempt.one(UUID.randomUUID(), TC, at, "198.51.100.7", "GET",
                            "/api/v1/ingestion/submissions/{id}", 401, "SDV-AUT-001", "ref-flood-" + n))));
                }
                for (Future<?> f : sent) f.get();
            } finally {
                threads.shutdownNow();
            }
        }
        assertEquals(1, rows("tenant_id='" + TC + "'"), "1,000 requests in a minute are one row");
        List<Attempt> seen = attempts.findRecent(TC, minute.minusSeconds(1), 50).stream()
                .filter(a -> TC.equals(a.tenantId())).toList();
        assertEquals(1, seen.size());
        Attempt flood = seen.getFirst();
        assertEquals(1000, flood.count());
        assertEquals(minute.plusMillis(50L * 999), flood.lastSeenAt(), "the last time is the last request's");
        assertFalse(flood.occurredAt().isBefore(minute));
        assertTrue(flood.occurredAt().isBefore(minute.plus(1, ChronoUnit.MINUTES)));
        assertTrue(flood.reference().startsWith("ref-flood-"), "the first request's reference");

        // 2. The next minute is a new row; the same request of another tenant is never added to this tenant's row.
        attempts.record(Attempt.one(UUID.randomUUID(), TC, minute.plusSeconds(61), "198.51.100.7", "GET",
                "/api/v1/ingestion/submissions/{id}", 401, "SDV-AUT-001", "ref-next"));
        attempts.record(Attempt.one(UUID.randomUUID(), TD, minute.plusSeconds(5), "198.51.100.7", "GET",
                "/api/v1/ingestion/submissions/{id}", 401, "SDV-AUT-001", "ref-other-tenant"));
        assertEquals(2, rows("tenant_id='" + TC + "'"));
        assertEquals(1, rows("tenant_id='" + TD + "'"));
        long tcCount = jdbc.queryForObject("select sum(attempt_count) from unidentified_intake_attempts where tenant_id='" + TC + "'",
                new MapSqlParameterSource(), Long.class);
        assertEquals(1001L, tcCount);

        // 3. 150 callers in one minute: 100 rows, then one row for the rest ("(many addresses)", count 50).
        Instant busy = now.minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
        for (int i = 0; i < 150; i++) {
            attempts.record(Attempt.one(UUID.randomUUID(), TD, busy.plusMillis(100L * i), "203.0.113." + i,
                    "POST", "/api/v1/ingestion/submissions", 401, "SDV-AUT-006", "ref-busy-" + i));
        }
        String inBusyMinute = "occurred_at >= '" + busy + "' and occurred_at < '" + busy.plus(1, ChronoUnit.MINUTES) + "'";
        assertEquals(UnidentifiedIntakeAttemptRepository.MAX_ROWS_PER_MINUTE + 1L, rows(inBusyMinute));
        Integer many = jdbc.queryForObject("select attempt_count from unidentified_intake_attempts where " + inBusyMinute
                + " and caller_address='" + UnidentifiedIntakeAttemptRepository.MANY_CALLERS + "'", new MapSqlParameterSource(), Integer.class);
        assertEquals(50, many);
        assertEquals(151L, jdbc.queryForObject("select sum(attempt_count) from unidentified_intake_attempts where tenant_id='" + TD + "'",
                new MapSqlParameterSource(), Long.class), "every request is counted (150 + the one above)");

        // 4. The table refuses a second row for the same key and minute, a count below 1 and a last time before the first.
        assertThrows(Exception.class, () -> sql("insert into unidentified_intake_attempts(attempt_id, tenant_id, occurred_at,"
                + " last_seen_at, attempt_count, caller_address, method, address, status, code, reference) values (gen_random_uuid(), '"
                + TC + "', '" + minute.plusSeconds(1) + "', '" + minute.plusSeconds(1) + "', 1, '198.51.100.7', 'GET',"
                + " '/api/v1/ingestion/submissions/{id}', 401, 'SDV-AUT-001', 'r')"));
        assertThrows(Exception.class, () -> sql("insert into unidentified_intake_attempts(attempt_id, occurred_at, last_seen_at,"
                + " attempt_count, method, address, status, code, reference) values (gen_random_uuid(), now(), now(), 0, 'GET', '/a', 401, 'c', 'r')"));
        assertThrows(Exception.class, () -> sql("insert into unidentified_intake_attempts(attempt_id, occurred_at, last_seen_at,"
                + " attempt_count, method, address, status, code, reference) values (gen_random_uuid(), now(), now() - interval '1 second', 1, 'GET', '/a', 401, 'c', 'r')"));
    }
}
