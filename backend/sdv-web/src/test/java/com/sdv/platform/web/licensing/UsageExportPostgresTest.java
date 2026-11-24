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
    private final UsageExports exports = new UsageExports(licence, db, e -> db.update(
            "insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, action, target_type, target_id, occurred_at, outcome, safe_details) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, 'SUCCESS', ?::jsonb)", e.auditEventId(), e.tenantId(), e.actorType(), e.actorId(), e.action(), e.targetType(),
            e.targetId(), java.sql.Timestamp.from(e.occurredAt()), e.safeDetailsJson()));

    private String export(boolean startAgain) {
        return tx.execute(s -> exports.export(it, "R-0075", startAgain)).text();
    }

    private void refused() {
        CodedRefusal.Conflict ex = assertThrows(CodedRefusal.Conflict.class, () -> export(false));
        assertEquals(SdvErrorCode.LIC_003, ex.errorCode());
    }

    @Test
    void filesContinueAndABrokenAnchorIsSaidAndNeverRepaired() {
        db.update("insert into tenants(tenant_id, tenant_key, display_name, status) values (?, 'usage-export-test', 'Usage export test', 'ACTIVE')", TENANT);
        licence.recordUsage(LicensingTest.SLOT, AllowanceType.STORAGE, 3, true);
        String first = export(false);
        licence.recordUsage(LicensingTest.SLOT, AllowanceType.STORAGE, 4, true);
        String second = export(false);
        assertTrue(UsageExportVerifier.verifySequence(List.of(first, second), ExportAnchor.first()).ok());
        assertFalse(first.contains("Jonas") || first.contains("it-admin") || first.contains("price"), first);
        long rows = db.queryForObject("select count(*) from usage_export_anchor", Long.class);

        // Replaced: refused, nothing changed, until somebody says so.
        String kept = db.queryForObject("select final_chain_value from usage_export_anchor order by sequence desc limit 1", String.class);
        db.update("update usage_export_anchor set final_chain_value = repeat('0', 64) where final_chain_value = ?", kept);
        refused();
        assertEquals(rows, db.queryForObject("select count(*) from usage_export_anchor", Long.class));
        db.update("update usage_export_anchor set final_chain_value = ? where final_chain_value = repeat('0', 64)", kept);
        export(false);
        // Rolled back to an older anchor (a restored backup), then missing.
        db.update("delete from usage_export_anchor where sequence = (select max(sequence) from usage_export_anchor)");
        refused();
        db.update("delete from usage_export_anchor");
        refused();
        String again = export(true);
        assertTrue(UsageExportVerifier.verify(again, ExportAnchor.first()).ok());
        assertEquals("true", db.queryForObject("select safe_details->>'startedAgain' from audit_events where action = 'USAGE_EXPORTED' order by occurred_at desc limit 1", String.class));
        export(false);
    }
}
