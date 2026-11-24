package com.sdv.platform.application.audit;

import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.access.SensitiveAccessGrants;
import com.sdv.platform.persistence.audit.JdbcAuditReportRepository;
import com.sdv.platform.persistence.business.JdbcBusinessRecordRepository;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.operations.JdbcOperationalRepository;
import com.sdv.platform.persistence.quarantine.JdbcQuarantineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0050, on a real PostgreSQL (changeset 037; runs in Test-SDV-SchemaUpgrade.ps1 step 4f and the CI backend job).
 * The audit trail and the report register are append-only: UPDATE, DELETE and TRUNCATE are refused by the database,
 * for the owner too. A report generated through the real tables is registered with the fingerprint of its CSV, its
 * generation is audited, and verify matches the unchanged file only.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test")
class SealedAuditPostgresTest {
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000005001");

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final JdbcFoundationRepository foundation = new JdbcFoundationRepository(jdbc);
    private final JdbcAuditReportRepository register = new JdbcAuditReportRepository(jdbc);
    private final DocumentAccessGuard guard = new DocumentAccessGuard(new JdbcBusinessRecordRepository(jdbc), foundation,
            SensitiveAccessGrants.NONE, new JdbcQuarantineRepository(jdbc));
    private final SealedAuditReportService reports = new SealedAuditReportService(
            new AuditQueryService(new JdbcOperationalRepository(jdbc), foundation, guard), register, foundation,
            new DataSourceTransactionManager(dataSource));

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private long count(String query) {
        Long value = jdbc.queryForObject(query, new MapSqlParameterSource(), Long.class);
        return value == null ? 0 : value;
    }

    private static void refused(Runnable change) {
        DataAccessException ex = assertThrows(DataAccessException.class, change::run);
        assertTrue(String.valueOf(ex.getMostSpecificCause().getMessage()).contains("append-only"), ex.getMostSpecificCause().getMessage());
    }

    @Test
    void theAuditTrailAndTheRegisterAreAppendOnlyAndAReportSealsWhatTheyHold() {
        sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + TENANT + "', 'sealed-audit-test', 'Sealed audit test', 'ACTIVE')");
        UUID event = UUID.randomUUID();
        sql("insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, action, target_type, target_id, occurred_at, outcome, safe_details, actor_display_name) "
                + "values ('" + event + "', '" + TENANT + "', 'USER', 'local-business-user', 'BUSINESS_SEARCH', 'TENANT', '" + TENANT + "', "
                + "timestamptz '2026-11-20 10:00:00+00', 'SUCCESS', '{\"resultCount\":3,\"reason\":\"Top secret reason\"}'::jsonb, 'Maya Okafor')");

        // Task 0062: a redaction's reason and regions of an older event never leave the table, on either way out.
        UUID document = UUID.randomUUID();
        for (String action : java.util.List.of("DOCUMENT_PERMANENTLY_REDACTED", "DOCUMENT_REDACTION_FAILED")) {
            sql("insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, action, target_type, target_id, occurred_at, outcome, safe_details) "
                    + "values ('" + UUID.randomUUID() + "', '" + TENANT + "', 'USER', 'steward', '" + action + "', 'DOCUMENT', '" + document + "', "
                    + "timestamptz '2026-11-19 10:00:00+00', 'SUCCESS', '{\"regionCount\":1,\"reason\":\"Top secret reason\",\"regions\":[{\"x\":0.1}]}'::jsonb)");
        }
        var out = new JdbcOperationalRepository(jdbc).find(TENANT, new com.sdv.platform.application.port.AuditQueryPort.Filter(
                null, null, null, null, "DOCUMENT", document.toString(), null, null, null, null, null, null, null, null, null), 10);
        var activity = new com.sdv.platform.persistence.business.JdbcDocumentActionRepository(jdbc).findDocumentActivity(TENANT, document, null, null, null, null, true, 10);
        for (var events : java.util.List.of(out, activity)) {
            assertEquals(2, events.size());
            for (var e : events) assertEquals("{\"regionCount\": 1}", e.safeDetailsJson());
        }

        // Append-only: nobody changes or removes an audit row, the owner included.
        refused(() -> sql("update audit_events set action = 'CHANGED' where audit_event_id = '" + event + "'"));
        refused(() -> sql("delete from audit_events where audit_event_id = '" + event + "'"));
        // TRUNCATE is refused too (by the trigger, or first by the steward notices' reference to audit rows).
        assertThrows(DataAccessException.class, () -> sql("truncate audit_events"));
        assertEquals(1, count("select count(*) from audit_events where audit_event_id = '" + event + "' and action = 'BUSINESS_SEARCH'"));

        // A report through the real tables: registered with its CSV's fingerprint, and its generation audited.
        SealedAuditReportService.Actor priya = new SealedAuditReportService.Actor(TENANT, "local-business-admin", "Priya Natarajan", "corr-0050");
        SealedAuditReportService.Report report = reports.generate(priya,
                new SealedAuditReportService.ReportRequest("2026-11-20", "2026-11-20", null, null, null, null));
        assertEquals(1, report.rowCount());
        byte[] csv = reports.csv(priya, report.reportId().toString()).bytes();
        assertEquals(SealedAuditReportService.sha256(csv), report.fingerprint());
        String text = new String(csv, StandardCharsets.UTF_8);
        assertTrue(text.contains("Maya Okafor"));
        assertFalse(text.contains("Top secret reason"), "never a reason");
        assertEquals(1, count("select count(*) from audit_reports where report_id = '" + report.reportId() + "' and fingerprint = '" + report.fingerprint() + "'"));
        assertEquals(1, count("select count(*) from audit_events where tenant_id = '" + TENANT + "' and action = 'AUDIT_REPORT_GENERATED' "
                + "and target_id = '" + report.reportId() + "' and safe_details ->> 'fingerprint' = '" + report.fingerprint() + "'"));

        // The register is append-only too.
        refused(() -> sql("update audit_reports set row_count = 0 where report_id = '" + report.reportId() + "'"));
        refused(() -> sql("delete from audit_reports where report_id = '" + report.reportId() + "'"));
        refused(() -> sql("truncate audit_reports"));

        // Verify: the unchanged file matches; one changed byte does not.
        assertTrue(reports.verify(priya, SealedAuditReportService.sha256(csv)).matches());
        byte[] changed = csv.clone();
        changed[changed.length - 3] = (byte) (changed[changed.length - 3] == '1' ? '2' : '1');
        assertFalse(reports.verify(priya, SealedAuditReportService.sha256(changed)).matches());
        assertEquals(2, count("select count(*) from audit_events where tenant_id = '" + TENANT + "' and action = 'AUDIT_REPORT_VERIFIED'"));
    }
}
