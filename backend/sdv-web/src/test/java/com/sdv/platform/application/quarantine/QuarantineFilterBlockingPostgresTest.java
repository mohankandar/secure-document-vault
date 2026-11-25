package com.sdv.platform.application.quarantine;

import com.sdv.platform.persistence.fields.CaseRows;
import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.approvals.ApprovalSettings;
import com.sdv.platform.application.port.QuarantineRepository.Preparation;
import com.sdv.platform.application.retention.RetentionDeletionService;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.persistence.business.JdbcBusinessRecordRepository;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.ingestion.JdbcSecureIngestionRepository;
import com.sdv.platform.persistence.quarantine.JdbcQuarantineRepository;
import com.sdv.platform.persistence.retention.JdbcRetentionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0051: quarantine by filter without a limit, against a real PostgreSQL database (changesets 036 and 038), with
 * 20,000 matching documents. Blocking is immediate on every document path before the rows exist; a document received
 * after the cut-off is not caught; the background job writes the rows in batches, continues after a restart and writes
 * nothing twice; a single release frees one document; the whole release ends the filter; the retention cleanup skips
 * documents the filter blocks; the exposure report is complete once the job is done.
 * <p>
 * Runs only when SDV_IT_JDBC_URL is set (Test-SDV-SchemaUpgrade.ps1 and the CI backend job set it to the scratch
 * database sdv_schema_test); never against the sdv database. In the normal build it is skipped.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class QuarantineFilterBlockingPostgresTest {
    private static final UUID T = UUID.fromString("10000000-0000-0000-0000-000000005101");
    private static final UUID S = UUID.fromString("20000000-0000-0000-0000-000000005101");
    private static final UUID SUB = UUID.fromString("30000000-0000-0000-0000-000000005101");
    private static final String CONTRACT = "300000005101";
    private static final int N = 20_000;
    /** The last 100 documents (by received time) are past their retention date. */
    private static final int DUE = 100;
    private static final Clearance RESTRICTED = new Clearance(true, true, List.of("RESTRICTED_STEWARD"), true);
    private static final Clearance ADMIN = new Clearance(false, false, List.of("BUSINESS_ADMIN"), false);
    private static final Clearance BUSINESS_USER = new Clearance(false, false);

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
    private final JdbcFoundationRepository foundation = new JdbcFoundationRepository(jdbc);
    private final JdbcQuarantineRepository repository = new JdbcQuarantineRepository(jdbc);
    private final DocumentAccessGuard guard = new DocumentAccessGuard(new JdbcBusinessRecordRepository(jdbc), foundation,
            com.sdv.platform.application.access.SensitiveAccessGrants.NONE, repository);
    private final JdbcSecureIngestionRepository ingestion = new JdbcSecureIngestionRepository(jdbc);
    private final RetentionDeletionService retention = new RetentionDeletionService(
            new JdbcRetentionRepository(jdbc), ingestion, ingestion, List.of(), foundation, tx);

    private QuarantineService service() {
        return new QuarantineService(repository, guard, new com.sdv.platform.persistence.fields.PlatformFieldRepository(jdbc.getJdbcTemplate().getDataSource()), foundation, ApprovalSettings.defaults(), tx, com.sdv.platform.application.port.RecordValuesPort.NONE);
    }

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private long count(String query) {
        Long value = jdbc.queryForObject(query, new MapSqlParameterSource(), Long.class);
        return value == null ? 0 : value;
    }

    private static QuarantineService.Actor as(String who, Clearance clearance) {
        return new QuarantineService.Actor(T, who, who + " Person", clearance, "corr-" + who);
    }

    /** Yesterday (UTC) at midnight, plus {@code ms}: every document is received yesterday, 2 ms apart. */
    private static final String YESTERDAY = "((date_trunc('day', now() at time zone 'utc') - interval '1 day') at time zone 'utc')";

    private void seed() {
        sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + T + "', 'quarantine-0051', 'Quarantine 0051', 'ACTIVE')");
        sql("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values ('"
                + S + "', '" + T + "', 'loans', 'Loans', 'ACTIVE', 'JWT_BEARER')");
        sql("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) values ('"
                + SUB + "', '" + T + "', '" + S + "', 'sub-0051', " + N + ", 'COMPLETE')");
        CaseRows.field(jdbc.getJdbcTemplate(), T, S, "contractNumber", "Contract number", true, "DIGITS_ONLY", 0);
        CaseRows.value(jdbc.getJdbcTemplate(), T, S, SUB, "contractNumber", CONTRACT);
        sql("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, "
                + "content_sha256, content_size_bytes, status, available_at, ingested_at, page_count, retention_days_snapshot, expires_at, "
                + "sensitivity_level) select gen_random_uuid(), '" + T + "', '" + SUB + "', '" + S + "', 'doc-' || g, 'secret-name-' || g || '.pdf', "
                + "'application/pdf', repeat('a', 64), 100, 'AVAILABLE', now(), " + YESTERDAY + " + g * interval '2 milliseconds', 3, 1, "
                + "case when g > " + (N - DUE) + " then now() - interval '1 day' else now() + interval '30 days' end, "
                + "case when g % 10 = 0 then 'HIGHLY_CONFIDENTIAL' when g % 3 = 0 then 'CONFIDENTIAL' else 'STANDARD' end "
                + "from generate_series(1, " + N + ") g");
        // The last document was opened before the quarantine (as the viewer writes it).
        sql("insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, actor_display_name, action, target_type, target_id, "
                + "occurred_at, correlation_id, outcome, safe_details) select gen_random_uuid(), '" + T + "', 'USER', 'uma', 'Uma User', "
                + "'DOCUMENT_VIEW_OPENED', 'DOCUMENT', document_id::text, now() - interval '1 hour', 'c', 'SUCCESS', '{}' from documents "
                + "where tenant_id='" + T + "' and source_document_id='doc-" + N + "'");
    }

    private List<UUID> documents() {
        return jdbc.query("select document_id from documents where tenant_id=:t and source_document_id like 'doc-%' order by ingested_at",
                new MapSqlParameterSource("t", T), (rs, row) -> rs.getObject(1, UUID.class));
    }

    private String status(UUID document) {
        return jdbc.queryForObject("select status from documents where document_id=:id", new MapSqlParameterSource("id", document), String.class);
    }

    @Test
    void twentyThousandDocumentsAreQuarantinedAtOnceAndTheirRowsFollow() {
        seed();
        List<UUID> ids = documents();
        assertEquals(N, ids.size());
        String day = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
        QuarantineService.FilterRequest filter = new QuarantineService.FilterRequest(day, day, S.toString(),
                Map.of("contractNumber", "3000-0000-5101"));
        QuarantineService.Actor restricted = as("rita", RESTRICTED);
        QuarantineService quarantine = service();

        // 1. No limit: the count is the whole match, and confirming it quarantines them all.
        assertEquals(N, quarantine.countMatching(restricted, filter).count());
        QuarantineService.IncidentView incident = quarantine.quarantineByFilter(restricted, filter, (long) N, "Leak", "Suspected leak");
        assertTrue(incident.preparing());
        assertEquals(QuarantineService.BATCH_SIZE, incident.preparedCount(), "one batch is written at once");
        assertEquals(N, incident.expectedCount());
        assertEquals(QuarantineService.BATCH_SIZE, count("select count(*) from quarantined_documents where tenant_id='" + T + "'"));

        // 2. Blocked at once on every document path, before the rows exist: the lookup the record views and search
        //    rows use, the one-document check, the guard for open, print and download, and the retention cleanup.
        assertEquals(N, repository.quarantined(T, ids).size());
        UUID last = ids.getLast();
        assertTrue(repository.isQuarantined(T, last));
        com.sdv.platform.domain.document.Document lastDocument = ingestion.findDocumentByIdForTenant(T, last).orElseThrow();
        for (DocumentAccessGuard.Attempt attempt : List.of(DocumentAccessGuard.Attempt.OPEN, DocumentAccessGuard.Attempt.PRINT,
                DocumentAccessGuard.Attempt.DOWNLOAD)) {
            assertEquals("SDV-QUA-001", assertThrows(CodedRefusal.Forbidden.class, () -> guard.requireOpenable(lastDocument,
                    BUSINESS_USER, attempt, "bob", "c")).errorCode().code(), attempt.name());
        }
        assertEquals(0, quarantine.countMatching(restricted, filter).count(), "nothing left to quarantine");
        retention.sweep(500);
        for (UUID due : ids.subList(N - DUE, N)) assertEquals("AVAILABLE", status(due), "the cleanup skips blocked documents");

        // 3. A document received after the cut-off (within the filter's day) is not caught.
        sql("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, "
                + "content_sha256, content_size_bytes, status, available_at, ingested_at, page_count, retention_days_snapshot, expires_at, "
                + "sensitivity_level) values (gen_random_uuid(), '" + T + "', '" + SUB + "', '" + S + "', 'late', 'late.pdf', 'application/pdf', "
                + "repeat('a', 64), 100, 'AVAILABLE', now(), " + YESTERDAY + " + interval '2 minutes', 3, 1, now() + interval '30 days', 'STANDARD')");
        UUID late = jdbc.queryForObject("select document_id from documents where tenant_id=:t and source_document_id='late'",
                new MapSqlParameterSource("t", T), UUID.class);
        assertFalse(repository.isQuarantined(T, late));
        assertEquals(1, quarantine.countMatching(restricted, filter).count());

        // 4. The job: a few batches, then a restart (a new service), then to the end. A batch run again writes nothing.
        assertEquals(3, quarantine.prepare(3));
        Preparation p = repository.findPreparation(T, incident.incidentId()).orElseThrow();
        assertEquals(4L * QuarantineService.BATCH_SIZE, p.preparedCount());
        Preparation fromTheStart = new Preparation(p.incidentId(), p.tenantId(), p.filter(), p.cutoffReceivedAt(),
                p.expectedCount(), 0, null, null, null, p.createdBy(), p.createdByName(), p.createdAt(), p.status());
        assertTrue(repository.prepareBatch(fromTheStart, QuarantineService.BATCH_SIZE, "rita", "Rita", Instant.now(),
                docs -> Map.of()).placed().isEmpty(),
                "the first batch again: every one already has its row");
        QuarantineService restarted = service();
        while (restarted.prepare(5) > 0) { /* until done */ }
        p = repository.findPreparation(T, incident.incidentId()).orElseThrow();
        assertFalse(p.preparing());
        assertEquals(N, count("select count(*) from quarantined_documents where tenant_id='" + T + "' and released_at is null"));
        assertEquals(N, count("select count(distinct document_id) from quarantined_documents where tenant_id='" + T + "'"), "no duplicates");
        assertFalse(repository.isQuarantined(T, late), "the late document has no row either");
        assertEquals(N, count("select count(*) from audit_events where tenant_id='" + T + "' and action='DOCUMENT_QUARANTINED'"));

        // 5. The exposure report is complete once the job is done; the view lists at most REVIEW_LIMIT.
        QuarantineService.ExposureReport exposure = quarantine.exposure(restricted, incident.incidentId().toString());
        assertEquals(N, exposure.documentCount());
        assertEquals(N, exposure.standard() + exposure.confidential() + exposure.highlyConfidential());
        assertEquals(N / 10, exposure.highlyConfidential());
        assertEquals(QuarantineService.PAGE, exposure.documents().size());
        assertNotNull(exposure.next());
        QuarantineService.Export export = quarantine.exportExposure(restricted, incident.incidentId().toString());
        assertEquals(N, export.rowCount(), "one row per document (the last one opened once, before)");
        assertTrue(new String(export.csv(), java.nio.charset.StandardCharsets.UTF_8).contains("Uma User"));
        assertFalse(new String(export.csv(), java.nio.charset.StandardCharsets.UTF_8).contains("secret-name"));

        // 6. A single release frees one document although the filter still matches it.
        quarantine.releaseDocument(restricted, ids.getFirst().toString(), "Checked, fine");
        assertFalse(repository.isQuarantined(T, ids.getFirst()));
        assertTrue(repository.isQuarantined(T, ids.get(1)));

        // 7. The whole release (request, another person approves) releases every row and ends the filter.
        quarantine.requestIncidentRelease(restricted, incident.incidentId().toString(), "Investigation closed");
        UUID request = quarantine.pendingReleaseRequests(as("ada", ADMIN)).getFirst().requestId();
        assertEquals(N - 1, quarantine.pendingReleaseRequests(as("ada", ADMIN)).getFirst().documentCount());
        quarantine.decide(as("ada", ADMIN), request.toString(), true);
        while (quarantine.release(5) > 0) { /* the background release, to the end */ }
        assertTrue(repository.quarantined(T, ids).isEmpty());
        assertEquals("RELEASED", jdbc.queryForObject("select status from quarantine_incidents where incident_id=:id",
                new MapSqlParameterSource("id", incident.incidentId()), String.class));
        assertEquals(N, count("select count(*) from audit_events where tenant_id='" + T + "' and action='DOCUMENT_QUARANTINE_RELEASED'"));

        // Leave nothing due, so the other PostgreSQL tests' counts of the cleanup (same database) hold.
        sql("update documents set expires_at = now() + interval '30 days' where tenant_id='" + T + "' and status <> 'EXPIRED_DELETED'");
    }
}
