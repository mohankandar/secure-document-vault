package com.sdv.platform.application.download;

import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.application.document.DocumentRefusals;
import com.sdv.platform.domain.error.CodedFault;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.SdvErrorCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.access.SensitiveAccessGrants;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.DocumentStorageRef;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.storage.StorageVolume;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ControlledDownloadService {
    private static final byte[] PDF_MAGIC = new byte[]{'%', 'P', 'D', 'F', '-'};

    private final DocumentIntakeRepository documents;
    private final StorageVolumeRepository volumes;
    private final List<StorageProvider> storageProviders;
    private final StreamingCryptoPort crypto;
    private final AuditEventPort audit;
    private final DocumentAccessGuard access;
    private final ObjectMapper json = VeroJson.mapper();
    private final Clock clock = Clock.systemUTC();

    public ControlledDownloadService(
            DocumentIntakeRepository documents,
            StorageVolumeRepository volumes,
            List<StorageProvider> storageProviders,
            StreamingCryptoPort crypto,
            AuditEventPort audit,
            DocumentAccessGuard access) {
        this.documents = documents;
        this.volumes = volumes;
        this.storageProviders = List.copyOf(storageProviders);
        this.crypto = crypto;
        this.audit = audit;
        this.access = java.util.Objects.requireNonNull(access, "access");
    }

    /**
     * Resolves and pre-validates the current AVAILABLE document before HTTP headers are committed.
     * The encrypted object is immutable and is verified by size + committed ciphertext SHA-256.
     */
    public PreparedDownload prepare(UUID tenantId, UUID documentId, Clearance clearance,
                                    String actorId, String correlationId) {
        Document document = requireAvailable(tenantId, documentId, clearance, actorId, correlationId);
        // Task 0028: a Highly Confidential document needs a valid grant; here it is only checked, so a failure before
        // anything is sent doesn't use it up. stream() uses it just before decrypting.
        SensitivityLevel level = access.requirePrintOrDownload(document, clearance,
                DocumentAccessGuard.Attempt.DOWNLOAD, actorId, correlationId, SensitiveAccessGrants.Use.CHECK).level();
        try {
            DocumentStorageRef storage = documents.findStorageRef(tenantId, documentId)
                    .orElseThrow(() -> unverified(documentId, "storage metadata missing"));
            DocumentCryptoMetadata cryptoMetadata = documents.findCryptoMetadata(tenantId, documentId)
                    .orElseThrow(() -> unverified(documentId, "crypto metadata missing"));
            StorageVolume volume = volumes.findById(tenantId, storage.storageVolumeId())
                    .orElseThrow(() -> unverified(documentId, "storage volume missing"));
            StorageProvider provider = provider(documentId, storage.providerId());

            if (document.contentSizeBytes() == null || document.contentSizeBytes() <= 0) {
                throw unverified(documentId, "size metadata missing");
            }

            verifyEncryptedObject(document, storage, volume, provider);
            return new PreparedDownload(document, storage, cryptoMetadata, volume, provider.providerId(), clearance, level);
        } catch (DocumentDownloadUnavailableException ex) {
            // Task 0043: nothing was sent and no grant was used; audited with its code.
            safeFailureAudit(document, actorId, correlationId, ex.errorCode(), ex.getCause() == null ? ex : ex.getCause(),
                    0, null, false);
            throw ex;
        }
    }

    /**
     * Streams the current protected PDF directly to the authorized caller without writing plaintext
     * to normal server files. The plan is re-checked before decrypt so a stale preparation cannot
     * silently become a historical-version download.
     * <p>
     * Task 0043 (owner answer 4 to report 0034): a Highly Confidential grant counts as used when the first byte is
     * handed to the response. It is still taken (atomically) just before decrypting, so two downloads racing on one
     * grant can't both start; but if the download then fails before its first byte (storage, the key service, a
     * check), the grant is given back, audited, and can be used once more. Once part of the file has left, it stays
     * used. Every failure is audited (DOCUMENT_DOWNLOAD_FAILED with its code, the bytes sent and the grant's state)
     * and thrown with its code: SDV-DOC-007 before the first byte (answered 422), SDV-DOC-009 after it (the answer
     * has started, so only the log line and the audit event carry the code; the screen sees a short file).
     */
    public void stream(
            PreparedDownload prepared,
            OutputStream response,
            String actorId,
            String correlationId) throws IOException {
        Objects.requireNonNull(prepared, "prepared download is required.");
        Objects.requireNonNull(response, "download response stream is required.");

        Document current = requireAvailable(prepared.document().tenantId(), prepared.document().documentId(),
                prepared.clearance(), actorId, correlationId);
        UUID documentId = current.documentId();
        DocumentStorageRef currentStorage;
        DocumentCryptoMetadata currentCrypto;
        try {
            currentStorage = documents.findStorageRef(current.tenantId(), documentId)
                    .orElseThrow(() -> unverified(documentId, "storage metadata missing"));
            currentCrypto = documents.findCryptoMetadata(current.tenantId(), documentId)
                    .orElseThrow(() -> unverified(documentId, "crypto metadata missing"));
        } catch (DocumentDownloadUnavailableException ex) {
            safeFailureAudit(current, actorId, correlationId, ex.errorCode(), ex, 0, null, false);
            throw ex;
        }

        if (!Objects.equals(current.contentSha256(), prepared.document().contentSha256())
                || !sameStorage(currentStorage, prepared.storage())
                || !sameCrypto(currentCrypto, prepared.crypto())) {
            safeFailureAudit(current, actorId, correlationId, SdvErrorCode.DOC_008, null, 0, null, false);
            throw new CodedRefusal.Conflict(SdvErrorCode.DOC_008, CHANGED_MESSAGE,
                    "document=" + documentId + " changed between preparing and sending the download");
        }

        // Task 0026: checked again just before decrypting, in case the level was raised since the preparation.
        // Task 0028: for a Highly Confidential document this uses the person's download grant (one download per
        // approval), after every other check, so only a decrypt that starts uses it.
        DocumentAccessGuard.PrintOrDownload allowed = access.requirePrintOrDownload(current, prepared.clearance(),
                DocumentAccessGuard.Attempt.DOWNLOAD, actorId, correlationId, SensitiveAccessGrants.Use.USE);
        SensitivityLevel level = allowed.level();
        CountingOutputStream sent = new CountingOutputStream(response);
        try {
            StorageProvider provider = provider(documentId, prepared.providerId());
            StreamingCryptoPort.CryptoEnvelope envelope = new StreamingCryptoPort.CryptoEnvelope(
                    currentCrypto.cryptoProfile(), currentCrypto.cryptoVersion(), currentCrypto.keyProviderId(),
                    currentCrypto.protectedDataKeyReference(), currentCrypto.keyProviderVersion(),
                    currentStorage.encryptedSizeBytes(), currentStorage.integrityValue());
            InputStream opened;
            try {
                opened = provider.open(current.tenantId(), prepared.volume(), currentStorage.objectKey());
            } catch (IOException | RuntimeException ex) {
                throw new StepFailed("storage could not be opened", ex);
            }
            try (InputStream encrypted = new StorageReadStream(opened)) {
                ValidatingPlaintextOutputStream validating = new ValidatingPlaintextOutputStream(
                        sent,
                        current.contentSizeBytes(),
                        current.contentSha256());
                crypto.decrypt(current.tenantId(), envelope, encrypted, validating);
                validating.finish();
            }
        } catch (IOException | RuntimeException ex) {
            throw failed(current, actorId, correlationId, allowed.grantId(), sent, ex);
        }
        // Every byte was sent and checked. If this audit fails the download still happened; the grant stays used.
        audit.append(new AuditEvent(
                UUID.randomUUID(), current.tenantId(), "USER", actorId,
                "DOCUMENT_DOWNLOADED", "DOCUMENT", documentId.toString(),
                clock.instant(), correlationId, AuditOutcome.SUCCESS,
                successDetails(current, currentStorage, level, allowed.grantId(), prepared.clearance())));
    }

    /**
     * Task 0043: one failed download, audited and turned into its coded failure. Before the first byte: the grant (if
     * any) is given back, SDV-DOC-007. After it: the grant stays used, SDV-DOC-009.
     */
    private DocumentDownloadUnavailableException failed(Document document, String actorId, String correlationId,
                                                        UUID grantId, CountingOutputStream sent, Exception ex) {
        long bytes = sent.count();
        boolean partSent = bytes > 0;
        boolean givenBack = !partSent && grantId != null && access.giveBack(document,
                DocumentAccessGuard.Attempt.DOWNLOAD, grantId, actorId, correlationId, "download failed before sending");
        Throwable cause = ex instanceof StepFailed || ex instanceof DocumentDownloadUnavailableException
                ? (ex.getCause() == null ? ex : ex.getCause()) : ex;
        String step = ex instanceof StepFailed s ? s.step
                : ex instanceof DocumentDownloadUnavailableException d
                        ? String.valueOf(d.logDetail()).replaceFirst("^document=\\S+ step=", "")
                : ex instanceof StorageReadFailed ? "storage read failed"
                : ex instanceof ValidationFailed ? "check failed: " + ex.getMessage()
                : ex instanceof ResponseWriteFailed ? "the browser stopped taking the file"
                : "key service or decryption failed";
        SdvErrorCode code = partSent ? SdvErrorCode.DOC_009 : SdvErrorCode.DOC_007;
        safeFailureAudit(document, actorId, correlationId, code, cause, bytes, grantId, grantId != null && !givenBack);
        String grant = grantId == null ? "none" : givenBack ? "given back" : partSent ? "used" : "used (give back failed)";
        String detail = "document=" + document.documentId() + " step=" + step + " bytesSent=" + bytes + " grant=" + grant;
        // Owner answer 1 to report 0043: before the first byte, 503 for storage or the key service, 500 for a failed
        // check. After it the answer has started; 422 is kept for the rare case it can still be written.
        int status = partSent ? CodedFault.UNPROCESSABLE
                : ex instanceof DocumentDownloadUnavailableException d ? d.httpStatus()
                : statusOf(step);
        return new DocumentDownloadUnavailableException(code, status, partSent ? STOPPED_MESSAGE : UNVERIFIED_MESSAGE,
                detail, cause);
    }

    /** SDV-DOC-007's status from its step: a failed check is 500, storage or the key service is 503. */
    private static int statusOf(String step) {
        return step.startsWith("check failed") ? CodedFault.INTERNAL : CodedFault.UNAVAILABLE;
    }

    private void verifyEncryptedObject(
            Document document,
            DocumentStorageRef storage,
            StorageVolume volume,
            StorageProvider provider) {
        UUID documentId = document.documentId();
        try (InputStream raw = provider.open(document.tenantId(), volume, storage.objectKey());
             DigestingCountingInputStream measured = new DigestingCountingInputStream(raw)) {
            byte[] buffer = new byte[64 * 1024];
            while (measured.read(buffer) != -1) {
                // digest/count performed by wrapper
            }
            if (measured.count() != storage.encryptedSizeBytes()) {
                throw unverified(documentId, "check failed: encrypted size no longer matches");
            }
            if (!Objects.equals(measured.sha256(), storage.integrityValue())) {
                throw unverified(documentId, "check failed: encrypted fingerprint no longer matches");
            }
        } catch (DocumentDownloadUnavailableException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new DocumentDownloadUnavailableException(SdvErrorCode.DOC_007, CodedFault.UNAVAILABLE,
                    UNVERIFIED_MESSAGE, "document=" + documentId + " step=storage read failed", ex);
        }
    }

    /** Task 0026: the level check comes before the status check, so a restricted document reveals nothing. */
    private Document requireAvailable(UUID tenantId, UUID documentId, Clearance clearance,
                                      String actorId, String correlationId) {
        Document document = documents.findDocumentByIdForTenant(tenantId, documentId)
                .orElseThrow(() -> DocumentRefusals.notFound(documentId));
        access.requireOpenable(document, clearance, DocumentAccessGuard.Attempt.DOWNLOAD, actorId, correlationId);
        if (document.status() != DocumentStatus.AVAILABLE) {
            throw DocumentRefusals.notAvailable(documentId, document.status());
        }
        return document;
    }

    private StorageProvider provider(UUID documentId, String providerId) {
        return storageProviders.stream()
                .filter(candidate -> candidate.providerId().equals(providerId))
                .findFirst()
                .orElseThrow(() -> unverified(documentId, "storage provider not installed"));
    }

    private String successDetails(Document document, DocumentStorageRef storage, SensitivityLevel level, UUID grantId,
                                  Clearance clearance) {
        try {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("contentSha256", document.contentSha256());
            safe.put("ciphertextSha256", storage.integrityValue());
            safe.put("contentSizeBytes", document.contentSizeBytes());
            safe.put("currentApproved", true);
            // Task 0026: the effective level, so Confidential downloads stand out in the audit trail.
            safe.put("sensitivityLevel", level.name());
            // Task 0028: the request whose one-use grant this download used (Highly Confidential only).
            if (grantId != null) safe.put("accessRequestId", grantId.toString());
            // Task 0054: the roles, for the Activity report (as refusals record them since task 0033).
            List<String> roles = com.sdv.platform.application.access.AuditRoles.of(clearance);
            if (!roles.isEmpty()) safe.put("roles", roles);
            return json.writeValueAsString(safe);
        } catch (JacksonException ex) {
            throw InternalCheckFailed.auditNotWritten("Could not serialize download audit details.", ex);
        }
    }

    /**
     * Task 0043: the failure's code, the exception class (never its text), the bytes handed to the response, and for
     * a Highly Confidential document the request whose grant it was and whether that grant is still used.
     */
    private void safeFailureAudit(Document document, String actorId, String correlationId, SdvErrorCode code,
                                  Throwable failure, long bytesSent, UUID grantId, boolean grantUsed) {
        try {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("code", code.code());
            safe.put("errorCode", failure == null ? "-" : failure.getClass().getSimpleName());
            safe.put("bytesSent", bytesSent);
            if (grantId != null) {
                safe.put("accessRequestId", grantId.toString());
                safe.put("grantUsed", grantUsed);
            }
            audit.append(new AuditEvent(
                    UUID.randomUUID(), document.tenantId(), "USER", actorId,
                    "DOCUMENT_DOWNLOAD_FAILED", "DOCUMENT", document.documentId().toString(),
                    clock.instant(), correlationId, AuditOutcome.FAILED,
                    json.writeValueAsString(safe)));
        } catch (Exception ignored) {
        }
    }

    private static boolean sameStorage(DocumentStorageRef a, DocumentStorageRef b) {
        return Objects.equals(a.providerId(), b.providerId())
                && Objects.equals(a.storageVolumeId(), b.storageVolumeId())
                && Objects.equals(a.objectKey(), b.objectKey())
                && a.encryptedSizeBytes() == b.encryptedSizeBytes()
                && Objects.equals(a.integrityValue(), b.integrityValue());
    }

    private static boolean sameCrypto(DocumentCryptoMetadata a, DocumentCryptoMetadata b) {
        return Objects.equals(a.cryptoProfile(), b.cryptoProfile())
                && a.cryptoVersion() == b.cryptoVersion()
                && Objects.equals(a.keyProviderId(), b.keyProviderId())
                && Objects.equals(a.protectedDataKeyReference(), b.protectedDataKeyReference())
                && Objects.equals(a.keyProviderVersion(), b.keyProviderVersion());
    }

    static final String UNVERIFIED_MESSAGE = "Vero couldn't verify this document, so it wasn't downloaded.";
    static final String CHANGED_MESSAGE = "This document was just changed. Open it again and download the new version.";
    static final String STOPPED_MESSAGE = "The download didn't finish.";

    /** Task 0043: SDV-DOC-007, nothing was sent (500 for a failed check, else 503); the step goes to the log only. */
    private static DocumentDownloadUnavailableException unverified(UUID documentId, String step) {
        return new DocumentDownloadUnavailableException(SdvErrorCode.DOC_007, statusOf(step), UNVERIFIED_MESSAGE,
                "document=" + documentId + " step=" + step);
    }

    /** Task 0043: a step that failed before decryption, with its name for the log line. */
    private static final class StepFailed extends RuntimeException {
        private final String step;

        StepFailed(String step, Throwable cause) {
            super(step, cause);
            this.step = step;
        }
    }

    /** Task 0043: reading the stored copy failed (not the key service, not a check). */
    private static final class StorageReadFailed extends IOException {
        StorageReadFailed(IOException cause) {
            super("storage read failed", cause);
        }
    }

    /** Task 0043: the response took no more bytes (usually the browser went away). */
    private static final class ResponseWriteFailed extends IOException {
        ResponseWriteFailed(IOException cause) {
            super("response write failed", cause);
        }
    }

    /** Task 0043: the decrypted file failed a size, header or fingerprint check. */
    private static final class ValidationFailed extends IOException {
        ValidationFailed(String check) {
            super(check);
        }
    }

    /** Task 0043: marks a failure reading the stored copy, so the log line can say which step failed. */
    private static final class StorageReadStream extends FilterInputStream {
        StorageReadStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } catch (IOException ex) {
                throw new StorageReadFailed(ex);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return super.read(b, off, len);
            } catch (IOException ex) {
                throw new StorageReadFailed(ex);
            }
        }
    }

    /** Task 0043: counts the bytes handed to the response; one or more means part of the file has left Vero. */
    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count;

        CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        // Counted before the write: a write that fails may already have sent part of it (fail closed).
        @Override
        public void write(int b) throws IOException {
            count++;
            try {
                delegate.write(b);
            } catch (IOException ex) {
                throw new ResponseWriteFailed(ex);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len > 0) count += len;
            try {
                delegate.write(b, off, len);
            } catch (IOException ex) {
                throw new ResponseWriteFailed(ex);
            }
        }

        @Override
        public void flush() throws IOException {
            try {
                delegate.flush();
            } catch (IOException ex) {
                throw new ResponseWriteFailed(ex);
            }
        }

        long count() {
            return count;
        }
    }

    public record PreparedDownload(
            Document document,
            DocumentStorageRef storage,
            DocumentCryptoMetadata crypto,
            StorageVolume volume,
            String providerId,
            Clearance clearance,
            SensitivityLevel sensitivityLevel) {
        public long contentSizeBytes() {
            return document.contentSizeBytes();
        }

        public String originalFilename() {
            return document.originalFilename();
        }
    }

    private static final class ValidatingPlaintextOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final long expectedSize;
        private final String expectedSha256;
        private final MessageDigest digest;
        private final byte[] prefix = new byte[PDF_MAGIC.length];
        private int prefixPosition;
        private long count;

        private ValidatingPlaintextOutputStream(
                OutputStream delegate,
                long expectedSize,
                String expectedSha256) throws IOException {
            this.delegate = delegate;
            this.expectedSize = expectedSize;
            this.expectedSha256 = expectedSha256;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException ex) {
                throw new IOException("SHA-256 is unavailable.", ex);
            }
        }

        @Override
        public void write(int b) throws IOException {
            if (count >= expectedSize) {
                throw new ValidationFailed("decrypted size larger than stored");
            }
            capturePrefix((byte) b);
            digest.update((byte) b);
            delegate.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len < 0 || count > expectedSize - len) {
                throw new ValidationFailed("decrypted size larger than stored");
            }
            for (int i = 0; i < len && prefixPosition < prefix.length; i++) {
                capturePrefix(b[off + i]);
            }
            digest.update(b, off, len);
            delegate.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        void finish() throws IOException {
            if (count != expectedSize) {
                throw new ValidationFailed("decrypted size does not match");
            }
            if (prefixPosition != PDF_MAGIC.length) {
                throw new ValidationFailed("decrypted content is not a complete PDF");
            }
            for (int i = 0; i < PDF_MAGIC.length; i++) {
                if (prefix[i] != PDF_MAGIC[i]) {
                    throw new ValidationFailed("decrypted content has no PDF header");
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!Objects.equals(actual, expectedSha256)) {
                throw new ValidationFailed("decrypted fingerprint does not match");
            }
            delegate.flush();
        }

        private void capturePrefix(byte value) {
            if (prefixPosition < prefix.length) {
                prefix[prefixPosition++] = value;
            }
        }
    }

    private static final class DigestingCountingInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private long count;

        private DigestingCountingInputStream(InputStream input) throws IOException {
            super(input);
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException ex) {
                throw new IOException("SHA-256 is unavailable.", ex);
            }
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                digest.update((byte) value);
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) {
                digest.update(b, off, read);
                count += read;
            }
            return read;
        }

        long count() {
            return count;
        }

        String sha256() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
