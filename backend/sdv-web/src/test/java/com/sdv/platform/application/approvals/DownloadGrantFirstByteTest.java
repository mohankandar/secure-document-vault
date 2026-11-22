package com.sdv.platform.application.approvals;

import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.approvals.AccessRequestService.Decision;
import com.sdv.platform.application.document.DocumentActionService;
import com.sdv.platform.application.download.ControlledDownloadService;
import com.sdv.platform.application.download.DocumentDownloadUnavailableException;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessActivityQueryPort;
import com.sdv.platform.application.port.DocumentCommentRepository;
import com.sdv.platform.application.port.DocumentTagRepository;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStorageRef;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sdv.platform.application.approvals.ApprovalsFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0043, item 3 (owner answer 4 to report 0034): a Highly Confidential one-time download grant counts as used when
 * the first byte is sent. Through the real download service, guard, grant store and audit, with storage and decryption
 * faked so each can fail at a chosen moment. Cases: success; failure before the first byte (grant back, still usable
 * once); failure after part of the file (grant used); two downloads racing on one grant (exactly one gets it); every
 * case audited. Item 4: print follows the same rule.
 */
class DownloadGrantFirstByteTest {
    private static final UUID HD = UUID.fromString("40000000-0000-0000-0000-0000000000f1");
    private static final UUID VOLUME = UUID.fromString("eeeeeeee-0000-0000-0000-0000000000f1");
    private static final byte[] PLAIN = pdf();
    private static final byte[] CIPHER = "stored ciphertext for the test".getBytes(StandardCharsets.UTF_8);

    private final ApprovalsFixture w = new ApprovalsFixture();
    private final StorageVolumeRepository volumes = mock(StorageVolumeRepository.class);
    private final FakeStorage storage = new FakeStorage();
    private final FakeCrypto crypto = new FakeCrypto();
    private final ControlledDownloadService downloads;

    DownloadGrantFirstByteTest() {
        Document h1 = w.documents.get(H1);
        w.documents.put(HD, new Document(HD, TENANT, h1.submissionId(), h1.sourceId(), "src-hd", null, "hd.pdf",
                "application/pdf", sha(PLAIN), (long) PLAIN.length, h1.status(), 365, h1.ingestedAt(), null,
                h1.availableAt(), 1, null, null, SensitivityLevel.HIGHLY_CONFIDENTIAL));
        w.pages.put(HD, 1);
        when(w.intake.findStorageRef(TENANT, HD)).thenReturn(Optional.of(
                new DocumentStorageRef(HD, TENANT, "TEST", VOLUME, "obj", CIPHER.length, sha(CIPHER))));
        when(w.intake.findCryptoMetadata(TENANT, HD)).thenReturn(Optional.of(
                new DocumentCryptoMetadata(HD, TENANT, "SDV-ENC-001", 1, "vault", "ref", "1")));
        when(volumes.findById(eq(TENANT), eq(VOLUME))).thenReturn(Optional.of(new StorageVolume(VOLUME, TENANT, "TEST",
                "v", "/tmp", StorageVolumeState.ACTIVE, 1, 80, 95, START, START)));
        downloads = new ControlledDownloadService(w.intake, volumes, List.of(storage), crypto, w.audit, w.guard);
    }

    private static byte[] pdf() {
        byte[] body = new byte[3000];
        byte[] head = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head, 0, body, 0, head.length);
        for (int i = head.length; i < body.length; i++) body[i] = (byte) ('a' + i % 26);
        return body;
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private UUID approvedDownload() {
        UUID request = w.approvals.requestPrintOrDownload(TENANT, RS, "Rae Restricted", RESTRICTED, HD.toString(),
                "DOWNLOAD", "Court bundle", "c").requestId();
        w.approvals.decide(TENANT, ADMIN, "Bea Admin", request.toString(), Decision.APPROVE, "c");
        return request;
    }

    private byte[] download(String correlationId) throws IOException {
        ControlledDownloadService.PreparedDownload prepared = downloads.prepare(TENANT, HD, RESTRICTED, RS, correlationId);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        downloads.stream(prepared, out, RS, correlationId);
        return out.toByteArray();
    }

    private AccessRequestStatus status(UUID request) {
        return w.requests.get(request).status();
    }

    private AuditEvent failure() {
        return w.last("DOCUMENT_DOWNLOAD_FAILED");
    }

    @Test
    void aDownloadThatFinishesUsesTheGrantOnce() throws Exception {
        UUID request = approvedDownload();
        assertArrayEquals(PLAIN, download("ok"));
        assertEquals(AccessRequestStatus.USED, status(request));
        assertTrue(w.last("DOCUMENT_DOWNLOADED").safeDetailsJson().contains(request.toString()));
        assertEquals(1, w.events(SensitiveAccessGrantStore.GRANT_USED_ACTION).size());
        assertTrue(w.events(SensitiveAccessGrantStore.GRANT_RETURNED_ACTION).isEmpty());
        assertEquals("SDV-SEN-009", code(() -> download("again")), "one download per approval");
    }

    @Test
    void aFailureBeforeTheFirstByteGivesTheGrantBackAndItStillWorksOnce() throws Exception {
        UUID request = approvedDownload();
        // The key service fails: nothing has been written yet.
        crypto.failBeforeWriting = true;
        DocumentDownloadUnavailableException keyDown = assertThrows(DocumentDownloadUnavailableException.class,
                () -> download("key-down"));
        assertEquals("SDV-DOC-007", keyDown.errorCode().code());
        assertEquals("Vero couldn't verify this document, so it wasn't downloaded.", keyDown.getMessage());
        assertTrue(keyDown.logDetail().contains("grant=given back"), keyDown.logDetail());
        assertTrue(keyDown.logDetail().contains("bytesSent=0"), keyDown.logDetail());
        assertEquals(AccessRequestStatus.APPROVED, status(request), "given back");
        assertNull(w.requests.get(request).usedAt());
        AuditEvent returned = w.last(SensitiveAccessGrantStore.GRANT_RETURNED_ACTION);
        assertEquals(HD.toString(), returned.targetId());
        assertTrue(returned.safeDetailsJson().contains(request.toString()));
        assertEquals("key-down", returned.correlationId());
        AuditEvent failed = failure();
        assertTrue(failed.safeDetailsJson().contains("\"code\":\"SDV-DOC-007\""), failed.safeDetailsJson());
        assertTrue(failed.safeDetailsJson().contains("\"grantUsed\":false"), failed.safeDetailsJson());
        assertTrue(failed.safeDetailsJson().contains("\"bytesSent\":0"), failed.safeDetailsJson());

        // Storage can't be opened on the second read: also before the first byte.
        crypto.failBeforeWriting = false;
        storage.failOpenAfter = 1;
        assertEquals("SDV-DOC-007", assertThrows(DocumentDownloadUnavailableException.class,
                () -> download("storage-down")).errorCode().code());
        assertEquals(AccessRequestStatus.APPROVED, status(request), "given back again");

        // The grant still works, once.
        storage.failOpenAfter = -1;
        assertArrayEquals(PLAIN, download("works"));
        assertEquals(AccessRequestStatus.USED, status(request));
        assertEquals("SDV-SEN-009", code(() -> download("after")), "and only once");
        assertEquals(3, w.events(SensitiveAccessGrantStore.GRANT_USED_ACTION).size(), "taken three times");
        assertEquals(2, w.events(SensitiveAccessGrantStore.GRANT_RETURNED_ACTION).size(), "given back twice");
    }

    @Test
    void aFailureAfterPartOfTheFileLeftKeepsTheGrantUsed() {
        UUID request = approvedDownload();
        crypto.failAfterFirstChunk = true;
        DocumentDownloadUnavailableException stopped = assertThrows(DocumentDownloadUnavailableException.class,
                () -> download("part-way"));
        assertEquals("SDV-DOC-009", stopped.errorCode().code());
        assertEquals("The download didn't finish.", stopped.getMessage());
        assertTrue(stopped.logDetail().contains("grant=used"), stopped.logDetail());
        assertEquals(AccessRequestStatus.USED, status(request), "part of the file left: the grant is used");
        assertTrue(w.events(SensitiveAccessGrantStore.GRANT_RETURNED_ACTION).isEmpty());
        AuditEvent failed = failure();
        assertTrue(failed.safeDetailsJson().contains("\"code\":\"SDV-DOC-009\""), failed.safeDetailsJson());
        assertTrue(failed.safeDetailsJson().contains("\"grantUsed\":true"), failed.safeDetailsJson());
        assertTrue(failed.safeDetailsJson().contains("\"bytesSent\":" + FakeCrypto.CHUNK), failed.safeDetailsJson());
        assertTrue(failed.safeDetailsJson().contains(request.toString()));
        assertEquals("SDV-SEN-009", code(() -> download("again")), "a new approval is needed");
    }

    @Test
    void aStoredCopyThatFailsItsCheckAfterSendingIsDoc009AndUsesTheGrant() {
        UUID request = approvedDownload();
        crypto.corruptLastByte = true;
        DocumentDownloadUnavailableException stopped = assertThrows(DocumentDownloadUnavailableException.class,
                () -> download("bad-copy"));
        assertEquals("SDV-DOC-009", stopped.errorCode().code());
        assertTrue(stopped.logDetail().contains("check failed: decrypted fingerprint does not match"), stopped.logDetail());
        assertEquals(AccessRequestStatus.USED, status(request));
    }

    @Test
    void twoDownloadsRacingOnOneGrantExactlyOneGetsIt() throws Exception {
        UUID request = approvedDownload();
        CountDownLatch prepared = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> tries = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String correlation = "race-" + i;
                tries.add(pool.submit(() -> {
                    ControlledDownloadService.PreparedDownload p = downloads.prepare(TENANT, HD, RESTRICTED, RS, correlation);
                    prepared.countDown();
                    assertTrue(prepared.await(10, TimeUnit.SECONDS), "both prepared before either streams");
                    try {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        downloads.stream(p, out, RS, correlation);
                        return out.size() == PLAIN.length ? "downloaded" : "short";
                    } catch (RuntimeException refused) {
                        if (refused instanceof CodedRefusal coded) return coded.errorCode().code();
                        throw refused;
                    }
                }));
            }
            List<String> outcomes = new ArrayList<>();
            for (Future<String> t : tries) outcomes.add(t.get(20, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter("downloaded"::equals).count(), outcomes.toString());
            assertEquals(1, outcomes.stream().filter("SDV-SEN-009"::equals).count(), outcomes.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(AccessRequestStatus.USED, status(request));
        assertEquals(1, w.events(SensitiveAccessGrantStore.GRANT_USED_ACTION).size());
        assertEquals(1, w.events("DOCUMENT_DOWNLOADED").size());
        assertEquals(1, w.events(DocumentAccessGuard.REFUSED_ACTION).stream()
                .filter(e -> e.safeDetailsJson().contains("SDV-SEN-009")).count(), "the loser is audited as refused");
    }

    @Test
    void aDocumentChangedBetweenPreparingAndSendingIsDoc008AndUsesNoGrant() {
        UUID request = approvedDownload();
        ControlledDownloadService.PreparedDownload prepared = downloads.prepare(TENANT, HD, RESTRICTED, RS, "c");
        when(w.intake.findStorageRef(TENANT, HD)).thenReturn(Optional.of(
                new DocumentStorageRef(HD, TENANT, "TEST", VOLUME, "obj-corrected", CIPHER.length, sha(CIPHER))));
        CodedRefusal changed = (CodedRefusal) assertThrows(RuntimeException.class,
                () -> downloads.stream(prepared, new ByteArrayOutputStream(), RS, "c"));
        assertEquals("SDV-DOC-008", changed.errorCode().code());
        assertEquals(AccessRequestStatus.APPROVED, status(request), "never taken");
        assertTrue(failure().safeDetailsJson().contains("SDV-DOC-008"));
    }

    @Test
    void aMissingOrUnavailableDocumentHasItsOwnCode() {
        assertEquals("SDV-DOC-001", code(() -> downloads.prepare(TENANT, UUID.randomUUID(), RESTRICTED, RS, "c")));
        w.withStatus(S1, com.sdv.platform.domain.document.DocumentStatus.PROCESSING);
        CodedRefusal notYet = (CodedRefusal) assertThrows(RuntimeException.class,
                () -> downloads.prepare(TENANT, S1, RESTRICTED, RS, "c"));
        assertEquals("SDV-DOC-002", notYet.errorCode().code());
        assertEquals("This document can't be opened yet: it is still being stored.",
                ((RuntimeException) notYet).getMessage());
    }

    @Test
    void aPrintThatCantBeRecordedGivesTheGrantBack() {
        UUID request = w.approvals.requestPrintOrDownload(TENANT, RS, "Rae Restricted", RESTRICTED, H1.toString(),
                "PRINT", "Court bundle", "c").requestId();
        w.approvals.decide(TENANT, ADMIN, "Bea Admin", request.toString(), Decision.APPROVE, "c");
        AtomicInteger printsRefused = new AtomicInteger(1);
        AuditEventPort printAuditDown = e -> {
            if (e.action().equals("DOCUMENT_PRINTED") && printsRefused.getAndDecrement() > 0) {
                throw new IllegalStateException("audit store down");
            }
            w.audit.append(e);
        };
        DocumentActionService actions = new DocumentActionService(w.intake, mock(DocumentTagRepository.class),
                mock(DocumentCommentRepository.class), mock(BusinessActivityQueryPort.class), printAuditDown, w.guard);
        assertThrows(IllegalStateException.class, () -> actions.recordPrint(TENANT, H1, RESTRICTED, RS, "print-1"));
        assertEquals(AccessRequestStatus.APPROVED, status(request), "the print didn't go ahead: grant back");
        assertTrue(w.last(SensitiveAccessGrantStore.GRANT_RETURNED_ACTION).safeDetailsJson().contains("PRINT"));

        actions.recordPrint(TENANT, H1, RESTRICTED, RS, "print-2");
        assertEquals(AccessRequestStatus.USED, status(request));
        assertEquals("SDV-SEN-009", code(() -> actions.recordPrint(TENANT, H1, RESTRICTED, RS, "print-3")));
    }

    /** Task 0043: the other document actions share DOC-001 and DOC-002, and a tag or comment that isn't valid is DOC-010. */
    @Test
    void documentActionsCarryTheirCodes() {
        assertEquals("SDV-DOC-010", code(() -> w.actions.addTag(TENANT, S1, " ", RESTRICTED, RS, "c")));
        assertEquals("SDV-DOC-010", code(() -> w.actions.addTag(TENANT, S1, "x".repeat(81), RESTRICTED, RS, "c")));
        assertEquals("SDV-DOC-010", code(() -> w.actions.addComment(TENANT, S1, "", RESTRICTED, RS, "c")));
        assertEquals("SDV-DOC-001", code(() -> w.actions.tags(TENANT, UUID.randomUUID(), RESTRICTED, RS, "c")));
        w.withStatus(S1, com.sdv.platform.domain.document.DocumentStatus.RETENTION_DELETING);
        assertEquals("SDV-DOC-002", code(() -> w.actions.recordPrint(TENANT, S1, RESTRICTED, RS, "c")));
    }

    private static String code(org.junit.jupiter.api.function.Executable call) {
        Throwable thrown = assertThrows(RuntimeException.class, call);
        assertInstanceOf(CodedRefusal.class, thrown, thrown.toString());
        return ((CodedRefusal) thrown).errorCode().code();
    }

    /** Storage that can refuse to open after a number of opens (prepare opens once to verify, stream once more). */
    private static final class FakeStorage implements StorageProvider {
        volatile int failOpenAfter = -1;
        private final AtomicInteger opens = new AtomicInteger();

        @Override public String providerId() { return "TEST"; }
        @Override public StoredObject store(UUID tenantId, StorageVolume volume, ContentWriter writer) {
            throw new UnsupportedOperationException();
        }
        @Override public InputStream open(UUID tenantId, StorageVolume volume, String objectKey) throws IOException {
            int n = opens.getAndIncrement();
            if (failOpenAfter >= 0 && n % 2 == failOpenAfter) throw new IOException("volume offline");
            return new ByteArrayInputStream(CIPHER);
        }
        @Override public void delete(UUID tenantId, StorageVolume volume, String objectKey) { }
        @Override public boolean exists(UUID tenantId, StorageVolume volume, String objectKey) { return true; }
    }

    /** "Decrypts" by writing the plaintext in chunks; can fail before writing, after one chunk, or corrupt it. */
    private static final class FakeCrypto implements StreamingCryptoPort {
        static final int CHUNK = 1000;
        volatile boolean failBeforeWriting;
        volatile boolean failAfterFirstChunk;
        volatile boolean corruptLastByte;

        @Override public CryptoEnvelope encrypt(UUID tenantId, InputStream plaintext, OutputStream ciphertext) {
            throw new UnsupportedOperationException();
        }

        @Override public void decrypt(UUID tenantId, CryptoEnvelope envelope, InputStream ciphertext, OutputStream plaintext)
                throws IOException {
            ciphertext.readAllBytes();
            if (failBeforeWriting) throw new IllegalStateException("key service unreachable");
            byte[] data = PLAIN.clone();
            if (corruptLastByte) data[data.length - 1] ^= 1;
            for (int off = 0; off < data.length; off += CHUNK) {
                if (off > 0 && failAfterFirstChunk) throw new IOException("storage read failed part-way");
                plaintext.write(data, off, Math.min(CHUNK, data.length - off));
            }
            plaintext.flush();
        }
    }
}
