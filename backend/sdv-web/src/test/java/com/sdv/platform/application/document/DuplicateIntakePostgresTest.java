package com.sdv.platform.application.document;

import com.sdv.platform.application.business.RecordReferences;
import com.sdv.platform.application.port.DocumentIntakeRepository.DuplicateReceipt;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.application.storage.StoragePlacementService;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import com.sdv.platform.persistence.approvals.JdbcAccessRequestRepository;
import com.sdv.platform.persistence.business.JdbcBusinessRecordRepository;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.ingestion.JdbcSecureIngestionRepository;
import com.sdv.platform.persistence.operations.JdbcOperationalRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0031: exact duplicates against a real PostgreSQL database, with the application's own repositories and the
 * intake service: which documents count as "the same file for the same record" (tenant, sending system, the record's
 * submissions, not deleted or being deleted), the duplicate row and its checks, a retry of a duplicate delivery, the
 * manifest slot and the received counts ("2 of 4"), and a higher level raising the existing document (level history
 * and audit, whose checks the rows must pass).
 * <p>
 * Runs only when SDV_IT_JDBC_URL is set. Test-SDV-SchemaUpgrade.ps1 sets it to its scratch database (sdv_schema_test)
 * after the schema step; it never points at the sdv database. In the normal build this test is skipped.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class DuplicateIntakePostgresTest {
    private static final UUID T1 = UUID.fromString("10000000-0000-0000-0000-0000000000e1");
    private static final UUID T2 = UUID.fromString("10000000-0000-0000-0000-0000000000e2");
    /** Tenant 1's two sending systems, and tenant 2's. */
    private static final UUID S1 = UUID.fromString("20000000-0000-0000-0000-0000000000e1");
    private static final UUID S1B = UUID.fromString("20000000-0000-0000-0000-0000000000e3");
    private static final UUID S2 = UUID.fromString("20000000-0000-0000-0000-0000000000e2");
    /** S1: the first delivery of contract A (the original), a new delivery of contract A (4 expected, a manifest of
     *  d1 to d4), a delivery of contract B. S1B and tenant 2: contract A too. */
    private static final UUID FIRST_A = UUID.fromString("30000000-0000-0000-0000-0000000000e1");
    private static final UUID NEW_A = UUID.fromString("30000000-0000-0000-0000-0000000000e2");
    private static final UUID B = UUID.fromString("30000000-0000-0000-0000-0000000000e3");
    private static final UUID OTHER_SOURCE_A = UUID.fromString("30000000-0000-0000-0000-0000000000e4");
    private static final UUID OTHER_TENANT_A = UUID.fromString("30000000-0000-0000-0000-0000000000e5");
    private static final UUID ORIGINAL = UUID.fromString("41000000-0000-0000-0000-0000000000e1");
    private static final UUID ON_B = UUID.fromString("41000000-0000-0000-0000-0000000000e2");
    private static final UUID ON_OTHER_SOURCE = UUID.fromString("41000000-0000-0000-0000-0000000000e3");
    private static final UUID ON_OTHER_TENANT = UUID.fromString("41000000-0000-0000-0000-0000000000e4");
    private static final String CONTRACT_A = "300000000091";
    private static final String CONTRACT_B = "300000000092";
    private static final byte[] PDF = "%PDF-1.7\nduplicate test\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final String SHA = sha(PDF);

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final JdbcFoundationRepository foundation = new JdbcFoundationRepository(jdbc);
    private final JdbcSecureIngestionRepository intake = new JdbcSecureIngestionRepository(jdbc);
    private final JdbcBusinessRecordRepository records = new JdbcBusinessRecordRepository(jdbc);
    private final JdbcAccessRequestRepository levels = new JdbcAccessRequestRepository(jdbc);
    private final StorageProvider storage = mock(StorageProvider.class);
    private final StoragePlacementService placement = mock(StoragePlacementService.class);
    private final StreamingCryptoPort crypto = mock(StreamingCryptoPort.class);
    private final DocumentIntakeService service = new DocumentIntakeService(foundation, foundation, intake, intake, placement,
            List.of(storage), crypto, mock(DocumentFinalizationService.class), foundation, new RecordReferences(records), levels,
            new org.springframework.transaction.support.TransactionTemplate(tx), Clock.fixed(now, ZoneOffset.UTC),
            com.sdv.platform.web.ingestion.TestPdfs.UNCHECKED);

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private String one(String query) {
        return jdbc.queryForObject(query, new MapSqlParameterSource(), String.class);
    }

    private long count(String query) {
        Long value = jdbc.queryForObject(query, new MapSqlParameterSource(), Long.class);
        return value == null ? 0 : value;
    }

    private void seed() throws Exception {
        sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + T1 + "', 'dup-t1', 'Duplicates', 'ACTIVE')");
        sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + T2 + "', 'dup-t2', 'Duplicates 2', 'ACTIVE')");
        source(T1, S1, "loans");
        source(T1, S1B, "cards");
        source(T2, S2, "loans");
        submission(T1, S1, FIRST_A, "first-a", 1, "COMPLETE", CONTRACT_A);
        submission(T1, S1, NEW_A, "new-a", 4, "OPEN", CONTRACT_A);
        submission(T1, S1, B, "b", 1, "COMPLETE", CONTRACT_B);
        submission(T1, S1B, OTHER_SOURCE_A, "other-source-a", 1, "COMPLETE", CONTRACT_A);
        submission(T2, S2, OTHER_TENANT_A, "other-tenant-a", 1, "COMPLETE", CONTRACT_A);
        for (int i = 1; i <= 4; i++) {
            sql("insert into submission_expected_documents(expected_document_id, tenant_id, submission_id, source_document_id, state) values ('"
                    + UUID.randomUUID() + "', '" + T1 + "', '" + NEW_A + "', 'd" + i + "', 'EXPECTED')");
        }
        document(T1, S1, FIRST_A, ORIGINAL, "CONFIDENTIAL", "2026-11-01 09:00:00+00");
        document(T1, S1, B, ON_B, "STANDARD", "2026-11-01 09:00:00+00");
        document(T1, S1B, OTHER_SOURCE_A, ON_OTHER_SOURCE, "STANDARD", "2026-11-01 09:00:00+00");
        document(T2, S2, OTHER_TENANT_A, ON_OTHER_TENANT, "STANDARD", "2026-11-01 09:00:00+00");

        StorageVolume volume = new StorageVolume(UUID.randomUUID(), T1, "LOCAL_FILESYSTEM", "primary-a", "/tmp",
                StorageVolumeState.values()[0], 1, 80, 95, now, now);
        StoragePlacementService.PlacementDecision decision = mock(StoragePlacementService.PlacementDecision.class);
        when(decision.volume()).thenReturn(volume);
        when(placement.select(eq(T1), anyLong())).thenReturn(decision);
        when(storage.providerId()).thenReturn("LOCAL_FILESYSTEM");
        when(storage.store(eq(T1), eq(volume), any())).thenAnswer(call -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            call.getArgument(2, StorageProvider.ContentWriter.class).write(out);
            return new StorageProvider.StoredObject("tmp-object", out.size());
        });
        when(crypto.encrypt(org.mockito.ArgumentMatchers.argThat(b -> b != null && b.entityId().equals(T1) && b.sealedId() == null), any(), any())).thenAnswer(call -> {
            byte[] plain = call.getArgument(1, InputStream.class).readAllBytes();
            call.getArgument(2, OutputStream.class).write(plain);
            return new StreamingCryptoPort.CryptoEnvelope("p", 1, "k", "ref", "v1", plain.length, "c");
        });
    }

    private void source(UUID tenant, UUID source, String key) {
        sql("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values ('"
                + source + "', '" + tenant + "', '" + key + "', '" + key + "', 'ACTIVE', 'JWT_BEARER')");
        sql("insert into metadata_field_definitions(definition_id, tenant_id, source_id, field_key, version, display_label, field_type, "
                + "required, business_identifier, searchable, normalization, search_mode) values ('" + definition(source) + "', '"
                + tenant + "', '" + source + "', 'contractNumber', 1, 'Contract number', 'TEXT', true, true, true, 'DIGITS_ONLY', 'EXACT')");
    }

    private static UUID definition(UUID source) {
        return UUID.nameUUIDFromBytes(("definition-" + source).getBytes(StandardCharsets.UTF_8));
    }

    private void submission(UUID tenant, UUID source, UUID submission, String key, int expected, String status, String contract) {
        sql("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) values ('"
                + submission + "', '" + tenant + "', '" + source + "', '" + key + "', " + expected + ", '" + status + "')");
        sql("insert into business_metadata_values(metadata_value_id, tenant_id, source_id, submission_id, definition_id, field_key, "
                + "definition_version, stored_value, normalized_value) values ('" + UUID.randomUUID() + "', '" + tenant + "', '"
                + source + "', '" + submission + "', '" + definition(source) + "', 'contractNumber', 1, '" + contract + "', '" + contract + "')");
    }

    private void document(UUID tenant, UUID source, UUID submission, UUID document, String level, String received) {
        sql("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, "
                + "content_sha256, content_size_bytes, status, ingested_at, available_at, page_count, sensitivity_level) values ('"
                + document + "', '" + tenant + "', '" + submission + "', '" + source + "', 'first', 'secret-name.pdf', 'application/pdf', '"
                + SHA + "', " + PDF.length + ", 'AVAILABLE', '" + received + "', now(), 3, '" + level + "')");
    }

    private DocumentReceipt upload(String sourceDocumentId, String key, String level) throws Exception {
        return service.receive(T1, S1, NEW_A, sourceDocumentId, key, "again.pdf", "application/pdf", level, PDF.length,
                new ByteArrayInputStream(PDF), "loans-producer", "corr-dup");
    }

    private static String code(org.junit.jupiter.api.function.Executable call) {
        Throwable thrown = assertThrows(RuntimeException.class, call);
        assertInstanceOf(CodedRefusal.class, thrown, thrown.toString());
        return ((CodedRefusal) thrown).errorCode().code();
    }

    private void refused(String statement, String why) {
        assertThrows(RuntimeException.class, () -> sql(statement), why);
    }

    @Test
    void exactDuplicatesOnPostgres() throws Exception {
        seed();

        // ---- Which documents are "the same file for the same record".
        assertEquals(ORIGINAL, intake.findDuplicateOf(T1, S1, List.of(FIRST_A, NEW_A), SHA).orElseThrow().documentId());
        assertTrue(intake.findDuplicateOf(T1, S1, List.of(NEW_A), SHA).isEmpty(), "another record's submissions only");
        assertTrue(intake.findDuplicateOf(T1, S1, List.of(B), "b".repeat(64)).isEmpty(), "another checksum");
        assertTrue(intake.findDuplicateOf(T1, S1B, List.of(FIRST_A, NEW_A), SHA).isEmpty(), "another sending system");
        assertTrue(intake.findDuplicateOf(T2, S1, List.of(FIRST_A, NEW_A), SHA).isEmpty(), "another tenant");
        for (String gone : List.of("status='EXPIRED_DELETED', deleted_at=now(), deletion_reason='RETENTION_EXPIRED'",
                "status='RETENTION_DELETING'", "deleted_at=now()")) {
            sql("update documents set " + gone + " where document_id='" + ORIGINAL + "'");
            assertTrue(intake.findDuplicateOf(T1, S1, List.of(FIRST_A, NEW_A), SHA).isEmpty(), gone);
            sql("update documents set status='AVAILABLE', deleted_at=null, deletion_reason=null where document_id='" + ORIGINAL + "'");
        }

        // ---- Case 2 through the service: nothing stored, the existing document answered, recorded.
        long documentsBefore = count("select count(*) from documents");
        DocumentReceipt first = upload("d1", null, "CONFIDENTIAL");
        assertEquals(ORIGINAL, first.document().documentId());
        assertEquals(DocumentIntakeService.DUPLICATE_ALREADY_STORED, first.receiptAcknowledgement());
        assertTrue(first.exactDuplicateContent());
        assertFalse(first.idempotentReplay());
        assertEquals("d1", first.deliverySourceDocumentId());
        assertEquals(documentsBefore, count("select count(*) from documents"), "no second copy");
        assertEquals(0, count("select count(*) from document_storage_refs where tenant_id='" + T1 + "'"));
        verify(storage).delete(eq(T1), any(), eq("tmp-object"));
        assertEquals("1:" + ORIGINAL + ":" + SHA + ":CONFIDENTIAL:KEPT", one("select count(*) || ':' || max(existing_document_id::text) || ':' "
                + "|| max(content_sha256) || ':' || max(sent_level) || ':' || max(level_outcome) from document_duplicate_receipts "
                + "where tenant_id='" + T1 + "' and submission_id='" + NEW_A + "' and source_document_id='d1'"));
        assertEquals(1, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action='DOCUMENT_DUPLICATE_RECEIVED' "
                + "and target_id='" + ORIGINAL + "' and safe_details->>'requestId'='d1' and safe_details->>'contentSha256'='" + SHA + "'"));

        // The manifest slot is filled and the submission counts it: 1 of 4 received.
        assertEquals("RECEIVED:null", one("select state || ':' || coalesce(received_document_id::text, 'null') "
                + "from submission_expected_documents where submission_id='" + NEW_A + "' and source_document_id='d1'"));
        assertEquals(1, intake.countReceived(T1, NEW_A));
        assertEquals("RECEIVING", one("select status from submissions where submission_id='" + NEW_A + "'"));

        // ---- Case 1 for the duplicate delivery: same answer, nothing new, not counted again.
        DocumentReceipt retry = upload("d1", null, "CONFIDENTIAL");
        assertTrue(retry.idempotentReplay() && retry.exactDuplicateContent());
        assertEquals(ORIGINAL, retry.document().documentId());
        assertEquals("SDV-SEN-003", code(() -> upload("d1", null, "HIGHLY_CONFIDENTIAL")));
        assertEquals(1, count("select count(*) from document_duplicate_receipts where submission_id='" + NEW_A + "'"));
        assertEquals(1, count("select count(*) from audit_events where action='DOCUMENT_DUPLICATE_RECEIVED' and tenant_id='" + T1 + "'"));

        // ---- A higher level raises the existing document: level history and audit, as a source-system raise.
        DocumentReceipt raised = upload("d2", null, "HIGHLY_CONFIDENTIAL");
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, raised.document().sensitivityLevel());
        assertEquals("HIGHLY_CONFIDENTIAL", one("select sensitivity_level from documents where document_id='" + ORIGINAL + "'"));
        assertEquals("RAISED:CONFIDENTIAL:HIGHLY_CONFIDENTIAL:loans-producer", one("select direction || ':' || from_level || ':' || to_level "
                + "|| ':' || changed_by from sensitivity_level_changes where document_id='" + ORIGINAL + "'"));
        assertEquals(1, count("select count(*) from audit_events where action='DOCUMENT_SENSITIVITY_RAISED' and actor_type='SOURCE' "
                + "and target_id='" + ORIGINAL + "' and safe_details->>'to'='HIGHLY_CONFIDENTIAL'"));
        // A lower one is ignored.
        DocumentReceipt lower = upload("d3", null, "STANDARD");
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, lower.document().sensitivityLevel());
        assertEquals("KEPT", one("select level_outcome from document_duplicate_receipts where source_document_id='d3'"));
        assertEquals(1, count("select count(*) from sensitivity_level_changes where document_id='" + ORIGINAL + "'"));

        // ---- The last slot: 4 of 4, and the submission is complete.
        upload("d4", null, "STANDARD");
        assertEquals(4, intake.countReceived(T1, NEW_A));
        assertEquals("COMPLETE", one("select status from submissions where submission_id='" + NEW_A + "'"));
        assertEquals(0, count("select count(*) from submission_expected_documents where submission_id='" + NEW_A + "' and state='EXPECTED'"));

        // ---- Received counts elsewhere add duplicates: a due submission with its place taken is not incomplete.
        sql("update submissions set status='RECEIVING', last_activity_at=now() - interval '2 days' where submission_id='" + NEW_A + "'");
        intake.markSubmissionIncompleteIfDue(T1, S1, NEW_A, now);
        assertEquals("RECEIVING", one("select status from submissions where submission_id='" + NEW_A + "'"));
        JdbcOperationalRepository operations = new JdbcOperationalRepository(jdbc);
        assertTrue(operations.findAttentionSubmissions(T1, now, 50).stream().noneMatch(s -> s.submissionId().equals(NEW_A)),
                "4 of 4 received is not a submission needing attention");

        // ---- The table's checks.
        String columns = "insert into document_duplicate_receipts(duplicate_id, tenant_id, source_id, submission_id, source_document_id, "
                + "producer_idempotency_key, existing_document_id, content_sha256, content_size_bytes, sent_level, level_outcome, received_at) values ('";
        String rest = "', '" + ORIGINAL + "', '" + SHA + "', 10, 'STANDARD', 'KEPT', now())";
        refused(columns + UUID.randomUUID() + "', '" + T1 + "', '" + S1 + "', '" + NEW_A + "', 'd1', null" + rest,
                "one row per delivery (unique)");
        refused(columns + UUID.randomUUID() + "', '" + T1 + "', '" + S1 + "', '" + NEW_A + "', null, null" + rest, "no identity");
        refused(columns + UUID.randomUUID() + "', '" + T1 + "', '" + S1 + "', '" + NEW_A + "', 'x', 'y'" + rest, "two identities");
        refused(columns + UUID.randomUUID() + "', '" + T1 + "', '" + S1 + "', '" + NEW_A + "', ' ', null" + rest, "blank identity");
        refused(columns + UUID.randomUUID() + "', '" + T1 + "', '" + S1 + "', '" + NEW_A + "', 'z1', null', '" + ORIGINAL
                + "', 'ABC', 10, 'STANDARD', 'KEPT', now())", "bad checksum");
        refused(columns + UUID.randomUUID() + "', '" + T1 + "', '" + S1 + "', '" + NEW_A + "', 'z2', null', '" + ORIGINAL + "', '" + SHA
                + "', 10, 'standard', 'KEPT', now())", "bad level");
        refused(columns + UUID.randomUUID() + "', '" + T1 + "', '" + S1 + "', '" + NEW_A + "', 'z3', null', '" + ORIGINAL + "', '" + SHA
                + "', 10, 'STANDARD', 'LOWERED', now())", "bad outcome");
        refused(columns + UUID.randomUUID() + "', '" + T1 + "', '" + S1B + "', '" + NEW_A + "', 'z4', null" + rest, "submission of another sending system");
        refused(columns + UUID.randomUUID() + "', '" + T1 + "', '" + S1 + "', '" + NEW_A + "', 'z5', null', '" + ON_OTHER_TENANT + "', '" + SHA
                + "', 10, 'STANDARD', 'KEPT', now())", "another tenant's document");
        DuplicateReceipt stored = intake.findDuplicateReceipt(T1, NEW_A, "d2", null).orElseThrow();
        assertEquals(DuplicateReceipt.LevelOutcome.RAISED, stored.levelOutcome());
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, stored.sentLevel());
        assertTrue(intake.findDuplicateReceipt(T2, NEW_A, "d2", null).isEmpty(), "tenants are kept apart");

        // Nothing touched the other tenant, the other sending system or the other record.
        assertEquals("STANDARD:STANDARD:STANDARD", one("select string_agg(sensitivity_level, ':' order by document_id) from documents "
                + "where document_id in ('" + ON_B + "', '" + ON_OTHER_SOURCE + "', '" + ON_OTHER_TENANT + "')"));
    }
}
