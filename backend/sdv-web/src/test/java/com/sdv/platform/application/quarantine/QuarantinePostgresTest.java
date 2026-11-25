package com.sdv.platform.application.quarantine;

import com.sdv.platform.persistence.fields.CaseRows;
import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.approvals.ApprovalSettings;
import com.sdv.platform.application.port.QuarantineRepository.ReleaseRequest;
import com.sdv.platform.application.retention.RetentionDeletionService;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.persistence.business.JdbcBusinessRecordRepository;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.ingestion.JdbcSecureIngestionRepository;
import com.sdv.platform.persistence.quarantine.JdbcQuarantineRepository;
import com.sdv.platform.persistence.retention.JdbcRetentionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0048: quarantine against a real PostgreSQL database (changeset 036), with the application's own repositories
 * and services: the tables and their checks, the filter count, the exposure from the audit, the access refusal, and
 * the retention cleanup leaving quarantined documents alone.
 * <p>
 * Runs only when SDV_IT_JDBC_URL is set (Test-SDV-SchemaUpgrade.ps1 and the CI backend job set it to the scratch
 * database sdv_schema_test); never against the sdv database. In the normal build it is skipped.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class QuarantinePostgresTest {
    private static final UUID T1 = UUID.fromString("10000000-0000-0000-0000-000000004801");
    private static final UUID T2 = UUID.fromString("10000000-0000-0000-0000-000000004802");
    private static final UUID S1 = UUID.fromString("20000000-0000-0000-0000-000000004801");
    private static final UUID S2 = UUID.fromString("20000000-0000-0000-0000-000000004802");
    private static final UUID SUB1 = UUID.fromString("30000000-0000-0000-0000-000000004801");
    private static final UUID SUB2 = UUID.fromString("30000000-0000-0000-0000-000000004802");
    /** Past its retention date, Standard: quarantined, so the cleanup keeps it. */
    private static final UUID A = UUID.fromString("40000000-0000-0000-0000-0000000048a1");
    /** Past its retention date, never quarantined: deleted. */
    private static final UUID B = UUID.fromString("40000000-0000-0000-0000-0000000048b1");
    /** Highly Confidential, 7 pages. */
    private static final UUID C = UUID.fromString("40000000-0000-0000-0000-0000000048c1");
    /** Tenant 2's document on a record with the same identifier. */
    private static final UUID X = UUID.fromString("40000000-0000-0000-0000-0000000048f2");
    private static final String CONTRACT = "300000004801";
    private static final Clearance RESTRICTED = new Clearance(true, true, List.of("RESTRICTED_STEWARD"), true);
    private static final Clearance STEWARD = new Clearance(true, false, List.of("DOCUMENT_STEWARD"), true);
    private static final Clearance ADMIN = new Clearance(false, false, List.of("BUSINESS_ADMIN"), false);

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
    private final JdbcFoundationRepository foundation = new JdbcFoundationRepository(jdbc);
    private final JdbcQuarantineRepository repository = new JdbcQuarantineRepository(jdbc);
    private final DocumentAccessGuard guard = new DocumentAccessGuard(new JdbcBusinessRecordRepository(jdbc), foundation,
            com.sdv.platform.application.access.SensitiveAccessGrants.NONE, repository);
    private final QuarantineService quarantine = new QuarantineService(repository, guard,
            new com.sdv.platform.persistence.fields.PlatformFieldRepository(jdbc.getJdbcTemplate().getDataSource()), foundation, ApprovalSettings.defaults(), tx, com.sdv.platform.application.port.RecordValuesPort.NONE);
    private final JdbcSecureIngestionRepository ingestion = new JdbcSecureIngestionRepository(jdbc);
    private final RetentionDeletionService retention = new RetentionDeletionService(
            new JdbcRetentionRepository(jdbc), ingestion, ingestion, List.of(), foundation, tx);

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private long count(String query) {
        Long value = jdbc.queryForObject(query, new MapSqlParameterSource(), Long.class);
        return value == null ? 0 : value;
    }

    private String status(UUID document) {
        return jdbc.queryForObject("select status from documents where document_id=:id", new MapSqlParameterSource("id", document), String.class);
    }

    private static QuarantineService.Actor as(UUID tenant, String who, Clearance clearance) {
        return new QuarantineService.Actor(tenant, who, who + " Person", clearance, "corr-" + who);
    }

    private void seed() {
        for (Object[] t : new Object[][]{{T1, S1, SUB1, "quarantine-t1"}, {T2, S2, SUB2, "quarantine-t2"}}) {
            sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + t[0] + "', '" + t[3] + "', 'Quarantine test', 'ACTIVE')");
            sql("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values ('"
                    + t[1] + "', '" + t[0] + "', 'loans', 'Loans', 'ACTIVE', 'JWT_BEARER')");
            sql("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) values ('"
                    + t[2] + "', '" + t[0] + "', '" + t[1] + "', 'sub', 3, 'COMPLETE')");
            CaseRows.field(jdbc.getJdbcTemplate(), (UUID) t[0], (UUID) t[1], "contractNumber", "Contract number", true, "DIGITS_ONLY", 0);
            CaseRows.value(jdbc.getJdbcTemplate(), (UUID) t[0], (UUID) t[1], (UUID) t[2], "contractNumber", CONTRACT);
        }
        insertDocument(T1, S1, SUB1, A, "a-secret-name.pdf", true, null, "STANDARD");
        insertDocument(T1, S1, SUB1, B, "b-secret-name.pdf", true, null, "STANDARD");
        insertDocument(T1, S1, SUB1, C, "c-secret-name.pdf", false, 7, "HIGHLY_CONFIDENTIAL");
        insertDocument(T2, S2, SUB2, X, "x-secret-name.pdf", false, 2, "STANDARD");
        // A was opened and printed before its quarantine (as the viewer and print paths write it).
        for (String action : List.of("DOCUMENT_VIEW_OPENED", "DOCUMENT_PRINTED")) {
            sql("insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, actor_display_name, action, target_type, target_id, "
                    + "occurred_at, correlation_id, outcome, safe_details) values ('" + UUID.randomUUID() + "', '" + T1
                    + "', 'USER', 'uma', 'Uma User', '" + action + "', 'DOCUMENT', '" + A + "', now() - interval '1 hour', 'c', 'SUCCESS', '{}')");
        }
    }

    private void insertDocument(UUID tenant, UUID source, UUID submission, UUID document, String name, boolean due, Integer pages,
                                String level) {
        sql("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, "
                + "content_sha256, content_size_bytes, status, available_at, page_count, retention_days_snapshot, expires_at, sensitivity_level) values ('"
                + document + "', '" + tenant + "', '" + submission + "', '" + source + "', '" + document + "', '" + name
                + "', 'application/pdf', repeat('a', 64), 100, 'AVAILABLE', now(), " + (pages == null ? "null" : pages) + ", 1, "
                + (due ? "now() - interval '1 day'" : "now() + interval '30 days'") + ", '" + level + "')");
    }

    @Test
    void quarantineWorksOnTheRealTables() {
        seed();
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        String yesterday = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1).toString();
        QuarantineService.FilterRequest filter = new QuarantineService.FilterRequest(yesterday, today, S1.toString(),
                Map.of("contractNumber", "3000-0000-4801"));

        // 1. The server's count, within the entity, normalized as search stores the value.
        QuarantineService.Actor restricted = as(T1, "rita", RESTRICTED);
        assertEquals(3, quarantine.countMatching(restricted, filter).count(), "A, B and C; tenant 2's X is not counted");
        assertEquals("SDV-QUA-009", assertThrows(CodedRefusal.Conflict.class,
                () -> quarantine.quarantineByFilter(restricted, filter, 2L, "Leak", "Suspected leak")).errorCode().code());
        assertEquals(0, count("select count(*) from quarantined_documents where tenant_id='" + T1 + "'"));

        // 2. Quarantine A alone (Steward), then C by filter would need all three: quarantine the two left by filter.
        QuarantineService.IncidentView single = quarantine.quarantineDocument(as(T1, "stan", STEWARD), A.toString(), null,
                "Wrong delivery", "Sent to the wrong branch");
        assertEquals("SDV-QUA-002", assertThrows(CodedRefusal.Forbidden.class, () -> quarantine.quarantineDocument(
                as(T1, "stan", STEWARD), C.toString(), null, "Leak", "Suspected leak")).errorCode().code());
        assertEquals(2, quarantine.countMatching(restricted, filter).count(), "A is already quarantined");
        QuarantineService.IncidentView byFilter = quarantine.quarantineByFilter(restricted, filter, 2L, "Leak", "Suspected leak");
        assertEquals(2, byFilter.documentsInForce());
        assertTrue(repository.isQuarantined(T1, A) && repository.isQuarantined(T1, B) && repository.isQuarantined(T1, C));
        assertFalse(repository.isQuarantined(T2, X));

        // 3. The database itself keeps one quarantine in force per document.
        assertThrows(DataIntegrityViolationException.class, () -> repository.insertQuarantine(UUID.randomUUID(), T1,
                byFilter.incidentId(), A, "rita", null, Instant.now(), com.sdv.platform.domain.document.SensitivityLevel.STANDARD, Instant.now()));

        // 4. The guard refuses others, never saying the level.
        com.sdv.platform.domain.document.Document a = ingestion.findDocumentByIdForTenant(T1, A).orElseThrow();
        assertEquals("SDV-QUA-001", assertThrows(CodedRefusal.Forbidden.class, () -> guard.requireOpenable(a,
                new Clearance(false, false), DocumentAccessGuard.Attempt.OPEN, "bob", "c")).errorCode().code());
        assertEquals(1, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action='DOCUMENT_ACCESS_REFUSED' "
                + "and target_id='" + A + "' and safe_details::text like '%SDV-QUA-001%'"));

        // 5. The retention cleanup keeps quarantined documents (A and B are past their date), like a legal hold.
        RetentionDeletionService.SweepResult sweep = retention.sweep(100);
        assertEquals("AVAILABLE", status(A));
        assertEquals("AVAILABLE", status(B));
        assertTrue(sweep.heldSkipped() >= 2);

        // 6. Exposure from the audit, before the quarantine; never a file name.
        // Task 0063: pages, previews and searches of someone who is not in the report by an opening count too, once per kind.
        for (String action : List.of("DOCUMENT_PAGE_VIEWED", "DOCUMENT_PAGE_VIEWED", "DOCUMENT_PREVIEWED", "DOCUMENT_TEXT_SEARCHED")) {
            jdbc.update("insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, action, target_type, target_id, occurred_at, outcome, safe_details) "
                            + "select gen_random_uuid(), tenant_id, 'USER', 'reader-0063', :action, 'DOCUMENT', document_id::text, quarantined_at - interval '1 minute', 'SUCCESS', '{}'::jsonb "
                            + "from quarantined_documents where incident_id=:id limit 1",
                    new MapSqlParameterSource("id", single.incidentId()).addValue("action", action));
        }
        QuarantineService.ExposureReport exposure = quarantine.exposure(as(T1, "stan", STEWARD), single.incidentId().toString());
        assertEquals(List.of("Read pages (first time)", "Saw page previews (first time)", "Searched the text (first time)"),
                exposure.documents().stream().flatMap(d -> d.exposure().stream()).filter(e -> "reader-0063".equals(e.person()))
                        .map(QuarantineService.ExposureEntry::what).sorted().toList());
        assertEquals(List.of("Opened", "Printed"), exposure.documents().getFirst().exposure().stream().filter(e -> !"reader-0063".equals(e.person()))
                .map(QuarantineService.ExposureEntry::what).toList());
        assertEquals(CONTRACT, exposure.documents().getFirst().primaryIdentifier());
        QuarantineService.ExposureReport hidden = quarantine.exposure(as(T1, "stan", STEWARD), byFilter.incidentId().toString());
        assertEquals(1, hidden.hiddenCount(), "C is Highly Confidential: counted, not shown to a Steward");
        String csv = new String(quarantine.exportExposure(restricted, byFilter.incidentId().toString()).csv(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(csv.contains("secret-name"));

        // 7. Whole-incident release: request, own decision refused (also by the table's check), approval releases all.
        quarantine.requestIncidentRelease(restricted, byFilter.incidentId().toString(), "Investigation closed");
        ReleaseRequest pending = repository.findPendingRequest(T1, byFilter.incidentId()).orElseThrow();
        assertThrows(DataIntegrityViolationException.class, () -> repository.decideRequest(T1, pending.requestId(), "APPROVED",
                "rita", null, Instant.now()));
        assertEquals("SDV-QUA-010", assertThrows(CodedRefusal.Conflict.class,
                () -> quarantine.decide(as(T1, "rita", ADMIN), pending.requestId().toString(), true)).errorCode().code());
        List<QuarantineService.ReleaseRequestView> forAdmin = quarantine.pendingReleaseRequests(as(T1, "ada", ADMIN));
        assertEquals(1, forAdmin.size());
        assertEquals(1, forAdmin.getFirst().highlyConfidential());
        assertEquals(1, forAdmin.getFirst().standard());
        assertTrue(quarantine.pendingReleaseRequests(as(T2, "ada2", ADMIN)).isEmpty(), "another entity sees nothing");
        quarantine.decide(as(T1, "ada", ADMIN), pending.requestId().toString(), true);
        assertFalse(repository.isQuarantined(T1, B));
        assertFalse(repository.isQuarantined(T1, C));
        assertTrue(repository.isQuarantined(T1, A), "A is in its own incident");
        assertEquals("RELEASED", jdbc.queryForObject("select status from quarantine_incidents where incident_id=:id",
                new MapSqlParameterSource("id", byFilter.incidentId()), String.class));

        // 8. Released: B is deleted on the next run; A stays.
        retention.sweep(100);
        assertEquals("EXPIRED_DELETED", status(B));
        assertEquals("AVAILABLE", status(A));

        // 9. Audit: who, what, when, the incident; never the reason.
        assertEquals(3, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action='DOCUMENT_QUARANTINED'"));
        assertEquals(2, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action='DOCUMENT_QUARANTINE_RELEASED'"));
        assertEquals(0, count("select count(*) from audit_events where tenant_id='" + T1 + "' and (safe_details::text like '%Suspected%' "
                + "or safe_details::text like '%wrong branch%' or safe_details::text like '%Investigation%')"));
        assertEquals(1, count("select count(*) from quarantine_release_requests where tenant_id='" + T1
                + "' and status='APPROVED' and decided_by='ada'"));

        // Leave nothing due or quarantined, so LegalHoldPostgresTest's own counts of the cleanup (same database) hold.
        UUID last = repository.findActive(T1, A).orElseThrow().incidentId();
        Instant dayAgo = Instant.now().minus(java.time.Duration.ofDays(1));
        long before = repository.countSingleReleases(T1, last, "stan", dayAgo);
        quarantine.releaseDocument(as(T1, "stan", STEWARD), A.toString(), "Checked, fine");
        // Task 0071: single releases are counted and listed.
        assertEquals(before + 1, repository.countSingleReleases(T1, last, "stan", dayAgo));
        assertEquals(0, repository.countSingleReleases(T1, last, "nobody", dayAgo));
        assertEquals(A, repository.findSingleReleases(T1, last, 5).getFirst().document().documentId());
        sql("update documents set expires_at = now() + interval '30 days' where tenant_id in ('" + T1 + "','" + T2 + "')"
                + " and status <> 'EXPIRED_DELETED'");
    }
}
