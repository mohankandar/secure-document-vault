package com.sdv.platform.application.redaction;

import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.approvals.AccessRequestService;
import com.sdv.platform.application.approvals.ApprovalSettings;
import com.sdv.platform.application.approvals.SensitiveAccessGrantStore;
import com.sdv.platform.application.business.RecordReferences;
import com.sdv.platform.application.legalhold.LegalHoldService;
import com.sdv.platform.application.pdf.PdfWork;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.application.storage.StoragePlacementService;
import com.sdv.platform.application.viewer.DocumentViewerService;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.persistence.approvals.JdbcAccessRequestRepository;
import com.sdv.platform.persistence.business.JdbcBusinessRecordRepository;
import com.sdv.platform.persistence.business.JdbcPageViewAuditRepository;
import com.sdv.platform.persistence.business.JdbcRecordSensitivityRepository;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.ingestion.JdbcSecureIngestionRepository;
import com.sdv.platform.persistence.legalhold.JdbcLegalHoldRepository;
import com.sdv.platform.persistence.redaction.JdbcDocumentRedactionRepository;
import com.sdv.platform.persistence.redaction.JdbcHeldRedactionRepository;
import com.sdv.platform.web.erase.EraseKit;
import com.sdv.platform.web.ingestion.TestPdfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0066 against a real PostgreSQL database: the request, the approval, the sealed original, who opens it, and its
 * destruction after the hold. Files are kept in memory and "encrypted" by copying. Runs as LegalHoldPostgresTest does.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class HeldRedactionPostgresTest {
    private static final UUID T = UUID.fromString("10000000-0000-0000-0000-0000000000f6");
    private static final UUID S = UUID.fromString("20000000-0000-0000-0000-0000000000f6");
    private static final UUID SUB = UUID.fromString("30000000-0000-0000-0000-0000000000f6");
    private static final UUID VOL = UUID.fromString("50000000-0000-0000-0000-0000000000f6");
    private static final UUID HELD = UUID.fromString("42000000-0000-0000-0000-0000000000f1");
    private static final UUID FREE = UUID.fromString("42000000-0000-0000-0000-0000000000f2");
    private static final UUID RACE = UUID.fromString("42000000-0000-0000-0000-0000000000f3");
    private static final Clearance STEWARD = new Clearance(true, false);
    private static final List<RedactionRegion> REGIONS = List.of(new RedactionRegion(1, 0.1, 0.1, 0.2, 0.1));

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
    private final JdbcFoundationRepository foundation = new JdbcFoundationRepository(jdbc);
    private final JdbcSecureIngestionRepository ingestion = new JdbcSecureIngestionRepository(jdbc);
    private final JdbcBusinessRecordRepository records = new JdbcBusinessRecordRepository(jdbc);
    private final JdbcAccessRequestRepository requestStore = new JdbcAccessRequestRepository(jdbc);
    private final JdbcDocumentRedactionRepository redactionStore = new JdbcDocumentRedactionRepository(jdbc);
    private final JdbcHeldRedactionRepository held = new JdbcHeldRedactionRepository(jdbc);
    private final SensitiveAccessGrantStore grants = new SensitiveAccessGrantStore(requestStore, foundation, tx, Clock.systemUTC());
    private final DocumentAccessGuard guard = new DocumentAccessGuard(records, foundation, grants);
    private final LegalHoldService holds = new LegalHoldService(new JdbcLegalHoldRepository(jdbc), guard, foundation, tx);
    private final AccessRequestService approvals = new AccessRequestService(requestStore, ingestion, guard, new RecordReferences(records), records,
            new JdbcRecordSensitivityRepository(jdbc), grants, ApprovalSettings.defaults(), foundation, tx, holds);

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private Runnable afterStore = () -> { };
    /** The replacement can't be read back once, so the redaction stops after it was staged. */
    private boolean failNextOpen;
    private final StorageProvider storage = new StorageProvider() {
        @Override
        public String providerId() {
            return "memory";
        }

        @Override
        public StoredObject store(UUID tenantId, StorageVolume volume, ContentWriter writer) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.write(out);
            String key = UUID.randomUUID().toString();
            files.put(key, out.toByteArray());
            afterStore.run();
            return new StoredObject(key, out.size());
        }

        @Override
        public InputStream open(UUID tenantId, StorageVolume volume, String objectKey) throws IOException {
            if (failNextOpen && !objectKey.startsWith("original-")) {
                failNextOpen = false;
                throw new IOException("not now");
            }
            byte[] file = files.get(objectKey);
            if (file == null) throw new IOException("missing");
            return new ByteArrayInputStream(file);
        }

        @Override
        public void delete(UUID tenantId, StorageVolume volume, String objectKey) {
            files.remove(objectKey);
        }

        @Override
        public boolean exists(UUID tenantId, StorageVolume volume, String objectKey) {
            return files.containsKey(objectKey);
        }
    };
    /** What a stored copy was written as, by its key reference: the test's stand-in for the header inside the ciphertext. */
    private final Map<String, StreamingCryptoPort.Binding> bound = new java.util.HashMap<>();
    private final StreamingCryptoPort crypto = new StreamingCryptoPort() {
        @Override
        public CryptoEnvelope encrypt(Binding binding, InputStream plaintext, OutputStream ciphertext) throws IOException {
            byte[] all = plaintext.readAllBytes();
            ciphertext.write(all);
            String key = "key-" + UUID.randomUUID();
            bound.put(key, binding);
            return new CryptoEnvelope("COPY", 1, "test", key, "1", all.length, sha(all));
        }

        @Override
        public void decrypt(Binding binding, CryptoEnvelope envelope, InputStream ciphertext, OutputStream plaintext) throws IOException {
            Binding written = bound.getOrDefault(envelope.protectedDataKeyReference(), Binding.document(binding.entityId(), binding.documentId(), 1));
            if (!written.equals(binding)) throw new CheckFailed("bound to something else");
            ciphertext.transferTo(plaintext);
        }
    };
    private final EraseKit erase = new EraseKit(dataSource, tx, List.of(storage));
    private final StoragePlacementService placement = mock(StoragePlacementService.class);
    private final CorrectiveRedactionService redaction = new CorrectiveRedactionService(foundation, foundation, ingestion, redactionStore, ingestion,
            placement, List.of(storage), crypto, new PdfSanitizingRedactor(), foundation, tx, guard, PdfWork.defaults(), held, approvals, erase.port);
    private final DocumentViewerService viewer = new DocumentViewerService(ingestion, ingestion, List.of(storage), crypto, foundation,
            new JdbcPageViewAuditRepository(jdbc), guard, PdfWork.defaults(), (t, s) -> 100);
    private final SealedOriginalService sealed = new SealedOriginalService(held, ingestion, redactionStore, erase.port, erase.erased, guard, viewer, tx);

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private String one(String query) {
        return jdbc.queryForObject(query, new MapSqlParameterSource(), String.class);
    }

    private static String code(Executable call) {
        return ((CodedRefusal) assertThrows(RuntimeException.class, call)).errorCode().code();
    }

    private String key(UUID document) {
        return one("select object_key from document_storage_refs where document_id='" + document + "'");
    }

    private void seed() {
        sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + T + "', 'held-redaction', 'Held redaction', 'ACTIVE')");
        sql("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values ('" + S + "', '" + T + "', 'loans', 'Loans', 'ACTIVE', 'JWT_BEARER')");
        sql("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) values ('" + SUB + "', '" + T + "', '" + S + "', 'sub', 3, 'COMPLETE')");
        sql("insert into storage_volumes(volume_id, tenant_id, provider_id, display_name, root_path, state, priority, warning_percent, stop_write_percent) values ('"
                + VOL + "', '" + T + "', 'memory', 'Memory', 'held-redaction-test', 'ACTIVE', 1, 80, 90)");
        for (UUID d : List.of(HELD, FREE, RACE)) {
            byte[] pdf = TestPdfs.pdf(1, p -> p.getDocumentInformation().setTitle(d.toString()));
            String key = "original-" + d;
            files.put(key, pdf);
            sql("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, content_sha256, content_size_bytes, status, available_at, page_count, retention_days_snapshot, expires_at) values ('"
                    + d + "', '" + T + "', '" + SUB + "', '" + S + "', '" + d + "', 'x.pdf', 'application/pdf', '" + sha(pdf) + "', " + pdf.length + ", 'AVAILABLE', now(), 1, 1, now() + interval '30 days')");
            sql("insert into document_storage_refs(document_id, tenant_id, provider_id, storage_volume_id, object_key, encrypted_size_bytes, integrity_value) values ('"
                    + d + "', '" + T + "', 'memory', '" + VOL + "', '" + key + "', " + pdf.length + ", '" + sha(pdf) + "')");
            sql("insert into document_crypto_metadata(document_id, tenant_id, crypto_profile, crypto_version, key_provider_id, protected_data_key_reference, key_provider_version) values ('"
                    + d + "', '" + T + "', 'COPY', 1, 'test', 'key-" + d + "', '1')");
        }
        StorageVolume volume = ingestion.findById(T, VOL).orElseThrow();
        when(placement.select(any(), anyLong())).thenReturn(new StoragePlacementService.PlacementDecision(volume, null, 1, false));
    }

    private RedactionResult ask(UUID document, String reason) {
        return redaction.redact(T, document, reason, true, REGIONS, STEWARD, "stan", "Stan Steward", "c");
    }

    private void decide(UUID request, AccessRequestService.Decision decision) {
        approvals.decide(T, "ba", "Bea Admin", request.toString(), decision, "c");
    }

    @Test
    void aHeldDocumentIsRedactedOnlyWithApprovalAndItsOriginalIsKeptUntilTheHoldEnds() {
        seed();

        // 1. Not on hold: as before, at once, and the original is gone.
        RedactionResult free = ask(FREE, "Ordinary correction");
        assertEquals("AVAILABLE", free.status());
        assertNull(free.accessRequestId());
        assertFalse(files.containsKey("original-" + FREE));
        // Task 0091: the version rose by one, in the switch; the database never lets it fall.
        assertEquals("2", one("select object_version::text from document_crypto_metadata where document_id='" + FREE + "'"));
        assertThrows(RuntimeException.class, () -> sql("update document_crypto_metadata set object_version=1 where document_id='" + FREE + "'"));
        assertEquals("AVAILABLE", ask(FREE, "Once more").status());
        assertEquals("3", one("select object_version::text from document_crypto_metadata where document_id='" + FREE + "'"));
        // An earlier version's ciphertext with its key, in place of the current one: a failed check.
        bound.put("put back", StreamingCryptoPort.Binding.document(T, FREE, 2));
        sql("update document_crypto_metadata set protected_data_key_reference='put back' where document_id='" + FREE + "'");
        assertEquals("SDV-DOC-004", (assertThrows(com.sdv.platform.application.viewer.DocumentViewerUnavailableException.class, () -> viewer.metadata(T, FREE, Clearance.ALL, "rita", "c"))).errorCode().code());

        // 2. On hold: a request; nothing changes, and nothing is applied before the approval.
        LegalHoldService.LegalHoldView hold = holds.place(T, "ba", "Bea Admin", HELD.toString(), "Litigation", "c");
        RedactionResult asked = ask(HELD, "Secret reason 4711");
        assertEquals(RedactionResult.APPROVAL_REQUESTED, asked.status());
        assertEquals("original-" + HELD, key(HELD));
        assertEquals(0, redaction.applyApproved(10).taken());
        decide(asked.accessRequestId(), AccessRequestService.Decision.DECLINE);
        assertEquals(0, redaction.applyApproved(10).taken(), "declined");

        RedactionResult again = ask(HELD, "Secret reason 4711");
        decide(again.accessRequestId(), AccessRequestService.Decision.APPROVE);
        assertEquals(new CorrectiveRedactionService.ApplySweepResult(1, 1, 0), redaction.applyApproved(10));
        assertEquals(0, redaction.applyApproved(10).taken(), "once");
        assertEquals("AVAILABLE", one("select status from documents where document_id='" + HELD + "'"));
        assertNotEquals("original-" + HELD, key(HELD));
        assertTrue(files.containsKey(key(HELD)));
        // Task 0076: the original is kept as a copy of its own, bound to its sealed ID; the old object and its key are gone.
        String sealedKey = one("select object_key from sealed_originals where document_id='" + HELD + "'");
        assertTrue(files.containsKey(sealedKey), "the original is kept");
        assertFalse(files.containsKey("original-" + HELD));
        assertNotEquals("key-" + HELD, one("select protected_data_key_reference from sealed_originals where document_id='" + HELD + "'"));
        assertEquals("0", one("select count(*) from audit_events where tenant_id='" + T + "' and safe_details::text like '%4711%'"), "no reason in the audit");
        assertEquals("1", one("select count(*) from audit_events where tenant_id='" + T + "' and action='DOCUMENT_PERMANENTLY_REDACTED' and target_id='" + HELD + "' and actor_id='stan' and safe_details->>'sealedOriginalId' is not null"));

        // 3. Only a Restricted Steward opens it, by an opening of its own.
        assertEquals("SDV-HLD-011", code(() -> sealed.list(T, HELD, STEWARD, "stan", "c")));
        UUID sealedId = sealed.list(T, HELD, Clearance.ALL, "rita", "c").getFirst().sealedId();
        assertEquals("SDV-HLD-011", code(() -> sealed.open(T, HELD, sealedId, STEWARD, "stan", "c")));
        assertEquals("SDV-HLD-010", code(() -> sealed.open(T, FREE, sealedId, Clearance.ALL, "rita", "c")));
        assertEquals("SDV-HLD-010", code(() -> sealed.open(UUID.randomUUID(), HELD, sealedId, Clearance.ALL, "rita", "c")));
        UUID ordinary = viewer.metadata(T, HELD, Clearance.ALL, "rita", "c").viewingId();
        UUID opened = sealed.open(T, HELD, sealedId, Clearance.ALL, "rita", "c").viewingId();
        assertEquals("SDV-DOC-011", code(() -> sealed.page(T, HELD, sealedId, Clearance.ALL, 1, 1, ordinary, "Rita", "rita", "c")));
        assertEquals("SDV-DOC-011", code(() -> sealed.page(T, HELD, sealedId, Clearance.ALL, 1, 1, opened, "Other", "someone-else", "c")));
        assertEquals("SDV-DOC-011", code(() -> viewer.renderPage(T, HELD, Clearance.ALL, 1, 1, opened, "rita", "c")), "nor the other way round");
        assertTrue(sealed.page(T, HELD, sealedId, Clearance.ALL, 1, 1, opened, "Rita", "rita", "c").pngBytes().length > 0);
        assertEquals("1", one("select count(*) from audit_events where tenant_id='" + T + "' and action='DOCUMENT_PAGE_VIEWED' and safe_details->>'sealedOriginalId'='" + sealedId + "'"));

        // 4. While the hold is in place nothing is destroyed; once released it is, with its key, and audited.
        assertEquals(0, sealed.destroyDue(10).destroyed());
        holds.release(T, "ba", "Bea Admin", hold.holdId().toString(), "c");
        assertEquals(new SealedOriginalService.DestroySweepResult(1, 0), sealed.destroyDue(10));
        assertFalse(files.containsKey(sealedKey));
        assertTrue(files.containsKey(key(HELD)), "the redacted version stays");
        assertEquals("DESTROYED", one("select state from sealed_originals where sealed_id='" + sealedId + "' and protected_data_key_reference is null"));
        assertEquals("1", one("select count(*) from audit_events where tenant_id='" + T + "' and action='SEALED_ORIGINAL_DESTROYED' and target_id='" + HELD + "'"));
        assertTrue(sealed.list(T, HELD, Clearance.ALL, "rita", "c").isEmpty());
        assertEquals("SDV-HLD-010", code(() -> sealed.open(T, HELD, sealedId, Clearance.ALL, "rita", "c")));
        assertEquals(0, sealed.destroyDue(10).destroyed());

        // 4b. Task 0071: a hold is released by a request that another Business Admin decides, who didn't place it.
        LegalHoldService.LegalHoldView second = holds.place(T, "ba", "Bea Admin", HELD.toString(), "Again", "c");
        String holdId = second.holdId().toString();
        assertEquals("SDV-HLD-002", code(() -> approvals.requestHoldRelease(T, "ba", "Bea", holdId, "x", "c")));
        assertEquals("SDV-HLD-007", code(() -> approvals.requestHoldRelease(UUID.randomUUID(), "ba", "Bea", holdId, "Case closed", "c")));
        UUID release = approvals.requestHoldRelease(T, "ba", "Bea Admin", holdId, "Case closed 4712", "c").requestId();
        assertEquals("SDV-APR-004", code(() -> approvals.requestHoldRelease(T, "cy", "Cy Admin", holdId, "Case closed", "c")), "one at a time");
        assertEquals(second.holdId(), approvals.holdReleasesWaiting(T, "c").getFirst().holdId());
        assertEquals("SDV-APR-006", code(() -> decide(release, AccessRequestService.Decision.APPROVE)), "nobody their own");
        assertEquals("SDV-APR-009", approvals.decideMany(T, "cy", "Cy", "APPROVE", List.of(release.toString()), "c").results().getFirst().code());
        assertTrue(held.onHold(T, HELD));
        approvals.decide(T, "cy", "Cy Admin", release.toString(), AccessRequestService.Decision.APPROVE, "c");
        assertFalse(held.onHold(T, HELD));
        assertTrue(approvals.holdReleasesWaiting(T, "c").isEmpty());
        assertEquals("1", one("select count(*) from audit_events where tenant_id='" + T + "' and action='LEGAL_HOLD_RELEASED' and actor_id='cy' and safe_details->>'requestedBy'='ba'"));
        assertEquals("0", one("select count(*) from audit_events where tenant_id='" + T + "' and safe_details::text like '%4712%'"));
        // Asked by another admin: the one who placed it still doesn't approve it, but may decline.
        String third = holds.place(T, "ba", "Bea Admin", HELD.toString(), "Third", "c").holdId().toString();
        UUID byCy = approvals.requestHoldRelease(T, "cy", "Cy Admin", third, "Case closed", "c").requestId();
        assertEquals("SDV-HLD-012", code(() -> decide(byCy, AccessRequestService.Decision.APPROVE)));
        assertTrue(held.onHold(T, HELD));
        decide(byCy, AccessRequestService.Decision.DECLINE);
        // Released otherwise meanwhile: no longer approved.
        UUID stale = approvals.requestHoldRelease(T, "ba", "Bea Admin", third, "Case closed", "c").requestId();
        holds.release(T, "ba", "Bea Admin", third, "c");
        assertEquals("SDV-HLD-008", code(() -> approvals.decide(T, "cy", "Cy", stale.toString(), AccessRequestService.Decision.APPROVE, "c")));
        assertThrows(RuntimeException.class, () -> sql("update access_requests set hold_id=null where request_id='" + stale + "'"));

        // 5. A hold placed between the redaction's start and its staging: refused, the original is untouched.
        afterStore = () -> holds.place(T, "ba", "Bea Admin", RACE.toString(), "Just in time", "c");
        assertEquals("SDV-HLD-009", code(() -> ask(RACE, "Too late")));
        afterStore = () -> { };
        assertEquals("original-" + RACE, key(RACE));
        assertTrue(files.containsKey("original-" + RACE));
        assertEquals("AVAILABLE", one("select status from documents where document_id='" + RACE + "'"));

        // 5b. A hold placed while the redaction is already staged: it completes, and the original is sealed all the same.
        UUID raceHold = UUID.fromString(one("select hold_id::text from legal_holds where document_id='" + RACE + "' and released_at is null"));
        holds.release(T, "ba", "Bea Admin", raceHold.toString(), "c");
        failNextOpen = true;
        assertThrows(RedactionFailedException.class, () -> ask(RACE, "Interrupted"));
        assertEquals("REDACTION_PROCESSING", one("select status from documents where document_id='" + RACE + "'"));
        holds.place(T, "ba", "Bea Admin", RACE.toString(), "Meanwhile", "c");
        // Task 0076: the sealed copy can't be written: nothing is committed and the original is as it was; then it goes on.
        afterStore = () -> { throw new IllegalStateException("disk full"); };
        assertThrows(RedactionFailedException.class, () -> ask(RACE, "Interrupted"));
        afterStore = () -> { };
        assertTrue(files.containsKey("original-" + RACE));
        assertEquals("0", one("select count(*) from sealed_originals where document_id='" + RACE + "'"));
        assertEquals("original-" + RACE, key(RACE));
        assertEquals("AVAILABLE", ask(RACE, "Interrupted").status(), "the same request goes on");
        String raceSealed = one("select object_key from sealed_originals where document_id='" + RACE + "'");
        assertTrue(files.containsKey(raceSealed));
        assertFalse(files.containsKey("original-" + RACE));
        assertEquals("1", one("select count(*) from sealed_originals where document_id='" + RACE + "' and request_id is null and state='SEALED'"));

        // 6. A document that changed since the request: not approved.
        UUID late = ask(RACE, "Asked again").accessRequestId();
        sql("update documents set content_sha256=repeat('b', 64) where document_id='" + RACE + "'");
        assertEquals("SDV-APR-017", code(() -> decide(late, AccessRequestService.Decision.APPROVE)));
        assertTrue(files.containsKey(raceSealed));

        // 7. The tables refuse rows that make no sense.
        assertThrows(RuntimeException.class, () -> sql("update sealed_originals set state='SEALED' where sealed_id='" + sealedId + "'"));
        assertThrows(RuntimeException.class, () -> sql("update held_redactions set state='APPLIED' where request_id='" + late + "'"));
        assertThrows(RuntimeException.class, () -> sql("update access_requests set requested_level='STANDARD' where request_id='" + late + "'"));
    }
}
