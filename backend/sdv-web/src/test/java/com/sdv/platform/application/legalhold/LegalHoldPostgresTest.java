package com.sdv.platform.application.legalhold;

import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.retention.RetentionDeletionService;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.persistence.business.JdbcBusinessRecordRepository;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.ingestion.JdbcSecureIngestionRepository;
import com.sdv.platform.persistence.legalhold.JdbcLegalHoldRepository;
import com.sdv.platform.persistence.redaction.JdbcDocumentRedactionRepository;
import com.sdv.platform.persistence.retention.JdbcRetentionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0027: legal holds and the retention cleanup against a real PostgreSQL database, with the application's own
 * repositories and services (only file storage is left out: these documents have no stored copy).
 * <p>
 * Runs only when SDV_IT_JDBC_URL is set. Test-SDV-SchemaUpgrade.ps1 sets it to its scratch database (sdv_schema_test)
 * after the schema step, with the scratch owner's random password in SDV_IT_JDBC_PASSWORD; it never points at the sdv
 * database. In the normal build this test is skipped.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test")
class LegalHoldPostgresTest {
    private static final UUID T1 = UUID.fromString("10000000-0000-0000-0000-0000000000c1");
    private static final UUID T2 = UUID.fromString("10000000-0000-0000-0000-0000000000c2");
    private static final UUID S1 = UUID.fromString("20000000-0000-0000-0000-0000000000c1");
    private static final UUID S2 = UUID.fromString("20000000-0000-0000-0000-0000000000c2");
    private static final UUID SUB1 = UUID.fromString("30000000-0000-0000-0000-0000000000c1");
    private static final UUID SUB2 = UUID.fromString("30000000-0000-0000-0000-0000000000c2");
    /** Past its retention date; held, then released. */
    private static final UUID A = UUID.fromString("40000000-0000-0000-0000-0000000000a1");
    /** Past its retention date; never held. */
    private static final UUID B = UUID.fromString("40000000-0000-0000-0000-0000000000b1");
    /** Not due; 9 pages; held while a correction runs. */
    private static final UUID C = UUID.fromString("40000000-0000-0000-0000-0000000000c1");
    /** Past its retention date: a hold placed while the cleanup waits. */
    private static final UUID D = UUID.fromString("40000000-0000-0000-0000-0000000000d1");
    /** Past its retention date: the cleanup claims it while a hold waits. */
    private static final UUID E = UUID.fromString("40000000-0000-0000-0000-0000000000e1");
    /** Tenant 2's document, on a record with the same identifier value. */
    private static final UUID X = UUID.fromString("40000000-0000-0000-0000-0000000000f2");
    private static final String CONTRACT = "300000000001";

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
    private final JdbcFoundationRepository foundation = new JdbcFoundationRepository(jdbc);
    private final JdbcRetentionRepository retentionRepository = new JdbcRetentionRepository(jdbc);
    private final JdbcLegalHoldRepository holdRepository = new JdbcLegalHoldRepository(jdbc);
    private final LegalHoldService holds = new LegalHoldService(holdRepository,
            new DocumentAccessGuard(new JdbcBusinessRecordRepository(jdbc), foundation), foundation, tx);
    private final JdbcSecureIngestionRepository ingestion = new JdbcSecureIngestionRepository(jdbc);
    private final RetentionDeletionService retention = new RetentionDeletionService(
            retentionRepository, ingestion, ingestion, List.of(), foundation, tx);

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private String status(UUID document) {
        return jdbc.queryForObject("select status from documents where document_id=:id",
                new MapSqlParameterSource("id", document), String.class);
    }

    private long count(String query) {
        Long value = jdbc.queryForObject(query, new MapSqlParameterSource(), Long.class);
        return value == null ? 0 : value;
    }

    private void seed() {
        sql("delete from legal_holds where tenant_id in ('" + T1 + "','" + T2 + "')");
        for (Object[] t : new Object[][]{{T1, S1, SUB1, "hold-t1"}, {T2, S2, SUB2, "hold-t2"}}) {
            sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + t[0] + "', '" + t[3] + "', 'Hold test', 'ACTIVE')");
            sql("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values ('"
                    + t[1] + "', '" + t[0] + "', 'loans', 'Loans', 'ACTIVE', 'JWT_BEARER')");
            sql("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) values ('"
                    + t[2] + "', '" + t[0] + "', '" + t[1] + "', 'sub', 6, 'COMPLETE')");
            UUID definition = UUID.randomUUID();
            sql("insert into metadata_field_definitions(definition_id, tenant_id, source_id, field_key, version, display_label, field_type, "
                    + "required, business_identifier, searchable, normalization, search_mode) values ('" + definition + "', '" + t[0]
                    + "', '" + t[1] + "', 'contractNumber', 1, 'Contract number', 'TEXT', true, true, true, 'DIGITS_ONLY', 'EXACT')");
            sql("insert into business_metadata_values(metadata_value_id, tenant_id, source_id, submission_id, definition_id, field_key, "
                    + "definition_version, stored_value, normalized_value) values ('" + UUID.randomUUID() + "', '" + t[0] + "', '"
                    + t[1] + "', '" + t[2] + "', '" + definition + "', 'contractNumber', 1, '" + CONTRACT + "', '" + CONTRACT + "')");
        }
        insertDocument(T1, S1, SUB1, A, "a-secret-name.pdf", true, null);
        insertDocument(T1, S1, SUB1, B, "b-secret-name.pdf", true, null);
        insertDocument(T1, S1, SUB1, C, "c-secret-name.pdf", false, 9);
        insertDocument(T1, S1, SUB1, D, "d-secret-name.pdf", true, null);
        insertDocument(T1, S1, SUB1, E, "e-secret-name.pdf", true, null);
        insertDocument(T2, S2, SUB2, X, "x-secret-name.pdf", true, 2);
    }

    private void insertDocument(UUID tenant, UUID source, UUID submission, UUID document, String name, boolean due, Integer pages) {
        sql("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, "
                + "content_sha256, content_size_bytes, status, available_at, page_count, retention_days_snapshot, expires_at) values ('"
                + document + "', '" + tenant + "', '" + submission + "', '" + source + "', '" + document + "', '" + name
                + "', 'application/pdf', repeat('a', 64), 100, 'AVAILABLE', now(), " + (pages == null ? "null" : pages) + ", 1, "
                + (due ? "now() - interval '1 day'" : "now() + interval '30 days'") + ")");
    }

    @Test
    void holdsKeepDocumentsFromTheRetentionCleanupUntilTheyAreReleased() throws Exception {
        seed();

        // Finding: by the primary identifier, within the tenant, never a file name.
        List<LegalHoldService.HoldDocumentView> found = holds.find(T1, CONTRACT);
        assertEquals(5, found.size(), "tenant 1's five documents; tenant 2's document has the same value but is not listed");
        assertTrue(found.stream().noneMatch(d -> d.documentId().equals(X)));
        LegalHoldService.HoldDocumentView c = found.stream().filter(d -> d.documentId().equals(C)).findFirst().orElseThrow();
        assertEquals(CONTRACT, c.primaryIdentifier());
        assertEquals("Contract number", c.primaryIdentifierLabel());
        assertEquals(9, c.pageCount());
        assertEquals(SensitivityLevel.STANDARD, c.sensitivityLevel());
        assertNotNull(c.receivedAt());
        assertFalse(found.toString().contains("secret-name"), "no file name in the lookup");
        assertEquals(1, holds.find(T2, CONTRACT).size());
        assertTrue(holds.find(T1, "999").isEmpty());

        // 1. A hold placed while the cleanup is about to claim the document: the claim waits for it and then skips it.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            TransactionStatus holding = tx.getTransaction(new DefaultTransactionDefinition());
            holdRepository.lockDocument(T1, D).orElseThrow();
            holdRepository.insert(new com.sdv.platform.application.port.LegalHoldRepository.LegalHold(UUID.randomUUID(), T1, D,
                    "Race test", "admin", "Ari Admin", java.time.Instant.now(), null, null, null));
            Future<Boolean> claim = pool.submit(() -> new TransactionTemplate(tx).execute(
                    s -> retentionRepository.claimDueDocument(T1, D, java.time.Instant.now())));
            Thread.sleep(Duration.ofMillis(800));
            assertFalse(claim.isDone(), "the claim waits for the hold's lock");
            tx.commit(holding);
            assertFalse(claim.get(10, TimeUnit.SECONDS), "after the hold commits, the claim sees it and skips the document");
            assertEquals("AVAILABLE", status(D));

            // 2. The cleanup claims a document while a hold is being placed: the hold waits and is then refused.
            TransactionStatus claiming = tx.getTransaction(new DefaultTransactionDefinition());
            assertTrue(retentionRepository.claimDueDocument(T1, E, java.time.Instant.now()));
            Future<LegalHoldService.LegalHoldView> place = pool.submit(() -> holds.place(T1, "admin", "Ari Admin", E.toString(), "Too late", "c"));
            Thread.sleep(Duration.ofMillis(800));
            assertFalse(place.isDone(), "the hold waits for the cleanup's lock");
            tx.commit(claiming);
            ExecutionException refused = assertThrows(ExecutionException.class, () -> place.get(10, TimeUnit.SECONDS));
            assertEquals("SDV-HLD-006", ((CodedRefusal) refused.getCause()).errorCode().code());
            assertEquals(0, count("select count(*) from legal_holds where document_id='" + E + "'"));
        } finally {
            pool.shutdownNow();
        }

        // 3. A is held, B is not. The run deletes B, keeps A (and D), and says how many it kept.
        LegalHoldService.LegalHoldView holdA = holds.place(T1, "admin", "Ari Admin", A.toString(), "  Litigation: case 2026-117  ", "corr-a");
        assertEquals("Litigation: case 2026-117", holdA.reason(), "trimmed");
        assertEquals("SDV-HLD-005", assertThrows(CodedRefusal.Conflict.class,
                () -> holds.place(T1, "admin", "Ari Admin", A.toString(), "Again", "c")).errorCode().code());
        RetentionDeletionService.SweepResult first = retention.sweep(100);
        assertEquals("AVAILABLE", status(A), "held: not deleted although past its retention date");
        assertEquals("EXPIRED_DELETED", status(B));
        assertEquals("AVAILABLE", status(D));
        assertEquals(2, first.heldSkipped(), "A and D were kept on hold");
        assertEquals("EXPIRED_DELETED", status(X), "tenant 2's due document, not held, goes too");
        assertEquals(2, first.completed(), "B and X");
        assertTrue(retentionRepository.findDueDocuments(100).stream().noneMatch(d -> d.documentId().equals(A) || d.documentId().equals(D)));

        // A deleted document can't be held.
        assertEquals("SDV-HLD-006", assertThrows(CodedRefusal.Conflict.class,
                () -> holds.place(T1, "admin", "Ari Admin", B.toString(), "Too late", "c")).errorCode().code());

        // 4. Tenant isolation: tenant 2 can't see, hold or release tenant 1's documents and holds.
        assertEquals("SDV-HLD-004", assertThrows(CodedRefusal.NotFound.class,
                () -> holds.place(T2, "admin2", null, A.toString(), "Not mine", "c")).errorCode().code());
        assertEquals("SDV-HLD-007", assertThrows(CodedRefusal.NotFound.class,
                () -> holds.release(T2, "admin2", null, holdA.holdId().toString(), "c")).errorCode().code());
        assertTrue(holds.active(T2).isEmpty());
        assertEquals(2, holds.active(T1).size());

        // 5. A correction is still allowed on a held document (it changes the file, not the record).
        holds.place(T1, "admin", "Ari Admin", C.toString(), "Regulator inquiry", "c");
        new JdbcDocumentRedactionRepository(jdbc).markRedactionProcessing(T1, C, "a".repeat(64));
        assertEquals("REDACTION_PROCESSING", status(C));
        assertEquals(1, count("select count(*) from legal_holds where document_id='" + C + "' and released_at is null"));

        // 6. Released: deleted on the next run. The hold stays as history.
        LegalHoldService.LegalHoldView released = holds.release(T1, "admin", "Ari Admin", holdA.holdId().toString(), "corr-r");
        assertNotNull(released.releasedAt());
        assertEquals("SDV-HLD-008", assertThrows(CodedRefusal.Conflict.class,
                () -> holds.release(T1, "admin", "Ari Admin", holdA.holdId().toString(), "c")).errorCode().code());
        RetentionDeletionService.SweepResult second = retention.sweep(100);
        assertEquals("EXPIRED_DELETED", status(A), "after release it is deleted on the next run");
        assertEquals(1, second.heldSkipped(), "D is still held");
        assertEquals(1, count("select count(*) from legal_holds where document_id='" + A + "' and released_at is not null"));

        // 7. Audited: every place and release, as the Business Admin, on the document, without the reason.
        // A and C through the service (D's hold above was written directly by the race test, without the service).
        assertEquals(2, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action='LEGAL_HOLD_PLACED' and outcome='SUCCESS'"));
        assertEquals(1, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action='LEGAL_HOLD_RELEASED' "
                + "and target_type='DOCUMENT' and target_id='" + A + "' and actor_id='admin' and correlation_id='corr-r'"));
        assertEquals(0, count("select count(*) from audit_events where action like 'LEGAL_HOLD_%' and safe_details::text like '%Litigation%'"));
        assertEquals(0, count("select count(*) from audit_events where tenant_id='" + T2 + "' and action like 'LEGAL_HOLD_%'"));

        // 8. Task 0037: two Business Admins release C's hold at the same moment. One release happens; the other is
        // refused with 409 SDV-HLD-008 and audited as LEGAL_HOLD_RELEASE_REFUSED. Never a 500.
        UUID holdC = jdbc.queryForObject("select hold_id from legal_holds where document_id='" + C + "' and released_at is null",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), UUID.class);
        java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(2);
        ExecutorService two = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<String>> outcomes = new java.util.ArrayList<>();
            for (String admin : List.of("admin", "admin2")) {
                outcomes.add(two.submit(() -> {
                    together.await();
                    try {
                        holds.release(T1, admin, null, holdC.toString(), "corr-race-" + admin);
                        return "RELEASED";
                    } catch (RuntimeException ex) {
                        return ex instanceof CodedRefusal coded ? coded.errorCode().code() : ex.toString();
                    }
                }));
            }
            List<String> got = new java.util.ArrayList<>();
            for (var f : outcomes) got.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
            java.util.Collections.sort(got);
            assertEquals(List.of("RELEASED", "SDV-HLD-008"), got);
        } finally {
            two.shutdownNow();
        }
        assertEquals(1, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action='LEGAL_HOLD_RELEASED' and target_id='" + C + "'"));
        assertEquals(1, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action='LEGAL_HOLD_RELEASE_REFUSED' "
                + "and outcome='DENIED' and target_id='" + C + "' and correlation_id like 'corr-race-%' and safe_details::text like '%SDV-HLD-008%'"));
    }
}
