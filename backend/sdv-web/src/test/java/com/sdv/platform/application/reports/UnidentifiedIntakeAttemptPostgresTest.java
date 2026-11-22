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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

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
        return new Attempt(UUID.randomUUID(), tenant, at, "203.0.113.9", "POST", "/api/v1/ingestion/submissions/{id}/documents",
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
}
