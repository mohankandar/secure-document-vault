package com.sdv.platform.application.download;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.NoSuchElementException;
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
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.systemUTC();

    public ControlledDownloadService(
            DocumentIntakeRepository documents,
            StorageVolumeRepository volumes,
            List<StorageProvider> storageProviders,
            StreamingCryptoPort crypto,
            AuditEventPort audit) {
        this.documents = documents;
        this.volumes = volumes;
        this.storageProviders = List.copyOf(storageProviders);
        this.crypto = crypto;
        this.audit = audit;
    }

    /**
     * Resolves and pre-validates the current AVAILABLE document before HTTP headers are committed.
     * The encrypted object is immutable and is verified by size + committed ciphertext SHA-256.
     */
    public PreparedDownload prepare(UUID tenantId, UUID documentId) {
        Document document = requireAvailable(tenantId, documentId);
        DocumentStorageRef storage = documents.findStorageRef(tenantId, documentId)
                .orElseThrow(() -> unavailable("Current document storage metadata is unavailable."));
        DocumentCryptoMetadata cryptoMetadata = documents.findCryptoMetadata(tenantId, documentId)
                .orElseThrow(() -> unavailable("Current document crypto metadata is unavailable."));
        StorageVolume volume = volumes.findById(tenantId, storage.storageVolumeId())
                .orElseThrow(() -> unavailable("Current document storage volume is unavailable."));
        StorageProvider provider = provider(storage.providerId());

        if (document.contentSizeBytes() == null || document.contentSizeBytes() <= 0) {
            throw unavailable("Current document size metadata is unavailable.");
        }

        verifyEncryptedObject(document, storage, volume, provider);
        return new PreparedDownload(document, storage, cryptoMetadata, volume, provider.providerId());
    }

    /**
     * Streams the current protected PDF directly to the authorized caller without writing plaintext
     * to normal server files. The plan is re-checked before decrypt so a stale preparation cannot
     * silently become a historical-version download.
     */
    public void stream(
            PreparedDownload prepared,
            OutputStream response,
            String actorId,
            String correlationId) throws IOException {
        Objects.requireNonNull(prepared, "prepared download is required.");
        Objects.requireNonNull(response, "download response stream is required.");

        Document current = requireAvailable(prepared.document().tenantId(), prepared.document().documentId());
        DocumentStorageRef currentStorage = documents.findStorageRef(
                        current.tenantId(), current.documentId())
                .orElseThrow(() -> unavailable("Current document storage metadata is unavailable."));
        DocumentCryptoMetadata currentCrypto = documents.findCryptoMetadata(
                        current.tenantId(), current.documentId())
                .orElseThrow(() -> unavailable("Current document crypto metadata is unavailable."));

        if (!Objects.equals(current.contentSha256(), prepared.document().contentSha256())
                || !sameStorage(currentStorage, prepared.storage())
                || !sameCrypto(currentCrypto, prepared.crypto())) {
            throw new IllegalStateException("Document changed before the controlled download could start.");
        }

        StorageProvider provider = provider(prepared.providerId());
        StreamingCryptoPort.CryptoEnvelope envelope = new StreamingCryptoPort.CryptoEnvelope(
                currentCrypto.cryptoProfile(), currentCrypto.cryptoVersion(), currentCrypto.keyProviderId(),
                currentCrypto.protectedDataKeyReference(), currentCrypto.keyProviderVersion(),
                currentStorage.encryptedSizeBytes(), currentStorage.integrityValue());

        try (InputStream encrypted = provider.open(
                current.tenantId(), prepared.volume(), currentStorage.objectKey())) {
            ValidatingPlaintextOutputStream validating = new ValidatingPlaintextOutputStream(
                    response,
                    current.contentSizeBytes(),
                    current.contentSha256());
            crypto.decrypt(current.tenantId(), envelope, encrypted, validating);
            validating.finish();

            audit.append(new AuditEvent(
                    UUID.randomUUID(), current.tenantId(), "USER", actorId,
                    "DOCUMENT_DOWNLOADED", "DOCUMENT", current.documentId().toString(),
                    clock.instant(), correlationId, AuditOutcome.SUCCESS,
                    successDetails(current, currentStorage)));
        } catch (IOException ex) {
            safeFailureAudit(current, actorId, correlationId, ex);
            throw ex;
        } catch (RuntimeException ex) {
            safeFailureAudit(current, actorId, correlationId, ex);
            throw ex;
        }
    }

    private void verifyEncryptedObject(
            Document document,
            DocumentStorageRef storage,
            StorageVolume volume,
            StorageProvider provider) {
        try (InputStream raw = provider.open(document.tenantId(), volume, storage.objectKey());
             DigestingCountingInputStream measured = new DigestingCountingInputStream(raw)) {
            byte[] buffer = new byte[64 * 1024];
            while (measured.read(buffer) != -1) {
                // digest/count performed by wrapper
            }
            if (measured.count() != storage.encryptedSizeBytes()) {
                throw unavailable("Encrypted object size no longer matches committed custody metadata.");
            }
            if (!Objects.equals(measured.sha256(), storage.integrityValue())) {
                throw unavailable("Encrypted object integrity no longer matches committed custody metadata.");
            }
        } catch (DocumentDownloadUnavailableException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new DocumentDownloadUnavailableException(
                    "The current document cannot be verified for controlled download.", ex);
        }
    }

    private Document requireAvailable(UUID tenantId, UUID documentId) {
        Document document = documents.findDocumentByIdForTenant(tenantId, documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found."));
        if (document.status() != DocumentStatus.AVAILABLE) {
            throw new IllegalStateException("Only the current AVAILABLE document may be downloaded.");
        }
        return document;
    }

    private StorageProvider provider(String providerId) {
        return storageProviders.stream()
                .filter(candidate -> candidate.providerId().equals(providerId))
                .findFirst()
                .orElseThrow(() -> unavailable("Configured storage provider is unavailable."));
    }

    private String successDetails(Document document, DocumentStorageRef storage) {
        try {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("contentSha256", document.contentSha256());
            safe.put("ciphertextSha256", storage.integrityValue());
            safe.put("contentSizeBytes", document.contentSizeBytes());
            safe.put("currentApproved", true);
            return json.writeValueAsString(safe);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize download audit details.", ex);
        }
    }

    private void safeFailureAudit(Document document, String actorId, String correlationId, Exception failure) {
        try {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("errorCode", failure.getClass().getSimpleName());
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

    private static DocumentDownloadUnavailableException unavailable(String message) {
        return new DocumentDownloadUnavailableException(message);
    }

    public record PreparedDownload(
            Document document,
            DocumentStorageRef storage,
            DocumentCryptoMetadata crypto,
            StorageVolume volume,
            String providerId) {
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
                throw new IOException("Decrypted PDF exceeded committed plaintext size.");
            }
            capturePrefix((byte) b);
            digest.update((byte) b);
            delegate.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len < 0 || count > expectedSize - len) {
                throw new IOException("Decrypted PDF exceeded committed plaintext size.");
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
                throw new IOException("Decrypted PDF size does not match committed plaintext size.");
            }
            if (prefixPosition != PDF_MAGIC.length) {
                throw new IOException("Decrypted content is not a complete PDF.");
            }
            for (int i = 0; i < PDF_MAGIC.length; i++) {
                if (prefix[i] != PDF_MAGIC[i]) {
                    throw new IOException("Decrypted content no longer has the committed PDF header.");
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!Objects.equals(actual, expectedSha256)) {
                throw new IOException("Decrypted PDF fingerprint does not match committed custody metadata.");
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
