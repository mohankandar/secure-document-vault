package com.sdv.platform.web.licensing;

import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.usage.ExportAnchor;
import com.documentplatform.licensing.usage.UsageExportVerifier;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.SdvPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0075, on a real PostgreSQL (changeset 045): files go on from one another; a missing or replaced anchor is said. */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class UsageExportPostgresTest {
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000007501");
    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final JdbcTemplate db = new JdbcTemplate(dataSource);
    private final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    private final SdvPrincipal it = new SdvPrincipal(TENANT, "it-admin", Set.of(SdvRole.IT_ADMIN), "Jonas Weber", null);
    private final LicenceService licence = LicensingTest.service(LocalDate.now(ZoneOffset.UTC), "SDV", new LicensingTest.Licences());
    private final LicensingTest.Moving clock = new LicensingTest.Moving();
    private final UsageExports exports = new UsageExports(licence, db, e -> db.update(
            "insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, action, target_type, target_id, occurred_at, outcome, safe_details) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, 'SUCCESS', ?::jsonb)", e.auditEventId(), e.tenantId(), e.actorType(), e.actorId(), e.action(), e.targetType(),
            e.targetId(), java.sql.Timestamp.from(e.occurredAt()), e.safeDetailsJson()), clock);

    private void use(int month, long gigabytes) {
        licence.usage().record(LicensingTest.SLOT, "SDV", AllowanceType.STORAGE, java.time.YearMonth.of(2027, month), gigabytes, true);
    }

    private String export(boolean startAgain) {
        return tx.execute(s -> exports.export(it, "R-0075", startAgain)).text();
    }

    private static String say(com.documentplatform.licensing.usage.ExportCheck check) {
        return check.ok() ? "ok" : check.problem() + " " + check.message();
    }

    private void refused() {
        CodedRefusal.Conflict ex = assertThrows(CodedRefusal.Conflict.class, () -> export(false));
        assertEquals(SdvErrorCode.LIC_003, ex.errorCode());
    }

    @Test
    void filesContinueAndABrokenAnchorIsSaidAndNeverRepaired() {
        db.update("insert into tenants(tenant_id, tenant_key, display_name, status) values (?, 'usage-export-test', 'Usage export test', 'ACTIVE')", TENANT);
        use(2, 3);
        use(3, 4);
        // 30 March: February only; the same again has nothing new; in April, March.
        String first = export(false);
        CodedRefusal.BadRequest nothing = assertThrows(CodedRefusal.BadRequest.class, () -> export(false));
        assertEquals(SdvErrorCode.LIC_004, nothing.errorCode());
        clock.now = java.time.Instant.parse("2027-04-01T09:00:00Z");
        String second = export(false);
        assertTrue(first.contains("\"2027-02\"") && !first.contains("\"2027-03\""), first);
        assertEquals("ok", say(UsageExportVerifier.verifySequence(List.of(first, second), ExportAnchor.first())));
        assertFalse(first.contains("Jonas") || first.contains("it-admin") || first.contains("price"), first);
        long rows = db.queryForObject("select count(*) from usage_export_anchor", Long.class);

        // Replaced: refused, nothing changed, until somebody says so.
        String kept = db.queryForObject("select final_chain_value from usage_export_anchor order by sequence desc limit 1", String.class);
        db.update("update usage_export_anchor set final_chain_value = repeat('0', 64) where final_chain_value = ?", kept);
        refused();
        assertEquals(rows, db.queryForObject("select count(*) from usage_export_anchor", Long.class));
        db.update("update usage_export_anchor set final_chain_value = ? where final_chain_value = repeat('0', 64)", kept);
        clock.now = java.time.Instant.parse("2027-06-15T09:00:00Z"); // two months at once
        String third = export(false);
        assertTrue(UsageExportVerifier.verifySequence(List.of(first, second, third), ExportAnchor.first()).ok());
        // Rolled back to an older anchor (a restored backup), then missing.
        db.update("delete from usage_export_anchor where sequence = (select max(sequence) from usage_export_anchor)");
        refused();
        db.update("delete from usage_export_anchor");
        refused();
        clock.now = clock.now.plusSeconds(60);
        String again = export(true);
        assertTrue(UsageExportVerifier.verify(again, ExportAnchor.first()).ok());
        assertEquals("true", db.queryForObject("select safe_details->>'startedAgain' from audit_events where action = 'USAGE_EXPORTED' order by occurred_at desc limit 1", String.class));
        clock.now = java.time.Instant.parse("2027-07-01T09:00:00Z");
        export(false);
    }
}
