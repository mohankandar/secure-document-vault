package com.sdv.platform.application.document;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class DocumentFinalizationService {
    private final DocumentIntakeRepository documents;
    private final StorageVolumeRepository volumes;
    private final List<StorageProvider> storageProviders;
    private final StreamingCryptoPort crypto;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public DocumentFinalizationService(DocumentIntakeRepository documents,
                                       StorageVolumeRepository volumes,
                                       List<StorageProvider> storageProviders,
                                       StreamingCryptoPort crypto,
                                       AuditEventPort audit,
                                       PlatformTransactionManager transactionManager) {
        this(documents, volumes, storageProviders, crypto, audit,
                new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    DocumentFinalizationService(DocumentIntakeRepository documents,
                                StorageVolumeRepository volumes,
                                List<StorageProvider> storageProviders,
                                StreamingCryptoPort crypto,
                                AuditEventPort audit,
                                TransactionTemplate transactions,
                                Clock clock) {
        this.documents = documents;
        this.volumes = volumes;
        this.storageProviders = List.copyOf(storageProviders);
        this.crypto = crypto;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Document finalizeDocument(UUID tenantId,
                                     UUID sourceId,
                                     UUID submissionId,
                                     UUID documentId,
                                     String sourceSubject,
                                     String correlationId) {
        Document before = requireDocument(tenantId, sourceId, submissionId, documentId);
        if (before.status() == DocumentStatus.AVAILABLE) return before;
        if (before.status() != DocumentStatus.RECEIVED && before.status() != DocumentStatus.PROCESSING_FAILED) {
            return before;
        }

        boolean claimed = Boolean.TRUE.equals(transactions.execute(status ->
                documents.transitionDocumentToProcessing(
                        tenantId, sourceId, submissionId, documentId, clock.instant())));
        if (!claimed) return requireDocument(tenantId, sourceId, submissionId, documentId);

        try {
            verifyDurableObject(tenantId, documentId);
            Instant availableAt = clock.instant();
            transactions.executeWithoutResult(status ->
                    documents.markDocumentAvailable(tenantId, sourceId, submissionId, documentId, availableAt));
            audit.append(new AuditEvent(
                    UUID.randomUUID(), tenantId, "SOURCE", sourceSubject,
                    "DOCUMENT_AVAILABLE", "DOCUMENT", documentId.toString(), availableAt, correlationId,
                    AuditOutcome.SUCCESS, "{\"submissionId\":\"" + submissionId + "\"}"));
        } catch (Exception ex) {
            String errorCode = classify(ex);
            transactions.executeWithoutResult(status ->
                    documents.markDocumentProcessingFailed(
                            tenantId, sourceId, submissionId, documentId, errorCode));
            audit.append(new AuditEvent(
                    UUID.randomUUID(), tenantId, "SYSTEM", "document-finalizer",
                    "DOCUMENT_PROCESSING_FAILED", "DOCUMENT", documentId.toString(), clock.instant(), correlationId,
                    AuditOutcome.FAILED,
                    "{\"submissionId\":\"" + submissionId + "\",\"errorCode\":\"" + errorCode + "\"}"));
        }

        return requireDocument(tenantId, sourceId, submissionId, documentId);
    }

    private void verifyDurableObject(UUID tenantId, UUID documentId) throws IOException {
        Document document = documents.findDocumentByIdForTenant(tenantId, documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found."));
        DocumentStorageRef storageRef = documents.findStorageRef(tenantId, documentId)
                .orElseThrow(() -> new IOException("Document storage reference is missing."));
        DocumentCryptoMetadata cryptoMetadata = documents.findCryptoMetadata(tenantId, documentId)
                .orElseThrow(() -> new IOException("Document crypto metadata is missing."));
        StorageVolume volume = volumes.findById(tenantId, storageRef.storageVolumeId())
                .orElseThrow(() -> new IOException("Document storage volume is unavailable."));
        StorageProvider provider = provider(storageRef.providerId());

        if (!provider.exists(tenantId, volume, storageRef.objectKey())) {
            throw new IOException("Encrypted document object is unavailable.");
        }

        try (InputStream raw = provider.open(tenantId, volume, storageRef.objectKey());
             DigestingCountingInputStream encrypted = new DigestingCountingInputStream(raw)) {
            PlaintextVerifier plaintext = new PlaintextVerifier();
            StreamingCryptoPort.CryptoEnvelope envelope = new StreamingCryptoPort.CryptoEnvelope(
                    cryptoMetadata.cryptoProfile(),
                    cryptoMetadata.cryptoVersion(),
                    cryptoMetadata.keyProviderId(),
                    cryptoMetadata.protectedDataKeyReference(),
                    cryptoMetadata.keyProviderVersion(),
                    storageRef.encryptedSizeBytes(),
                    storageRef.integrityValue());

            crypto.decrypt(tenantId, envelope, encrypted, plaintext);
            plaintext.finish();

            if (encrypted.count() != storageRef.encryptedSizeBytes()) {
                throw new IOException("Encrypted object size does not match committed storage metadata.");
            }
            if (!encrypted.sha256().equals(storageRef.integrityValue())) {
                throw new IOException("Encrypted object integrity fingerprint does not match committed storage metadata.");
            }
            if (document.contentSizeBytes() == null || plaintext.count() != document.contentSizeBytes()) {
                throw new IOException("Decrypted document size does not match committed receipt metadata.");
            }
            if (!plaintext.sha256().equals(document.contentSha256())) {
                throw new IOException("Decrypted document fingerprint does not match committed receipt metadata.");
            }
        }
    }

    private Document requireDocument(UUID tenantId, UUID sourceId, UUID submissionId, UUID documentId) {
        return documents.findDocumentById(tenantId, sourceId, submissionId, documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found."));
    }

    private StorageProvider provider(String providerId) throws IOException {
        return storageProviders.stream()
                .filter(candidate -> candidate.providerId().equals(providerId))
                .findFirst()
                .orElseThrow(() -> new IOException("Configured storage provider is unavailable: " + providerId));
    }

    private static String classify(Exception ex) {
        if (ex instanceof SecurityException) return "STORAGE_SECURITY_VALIDATION_FAILED";
        if (ex instanceof IOException) return "DURABLE_OBJECT_VALIDATION_FAILED";
        return "DOCUMENT_FINALIZATION_FAILED";
    }

    private static final class DigestingCountingInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private long count;

        DigestingCountingInputStream(InputStream input) throws IOException {
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

        long count() { return count; }
        String sha256() { return HexFormat.of().formatHex(digest.digest()); }
    }

    private static final class PlaintextVerifier extends OutputStream {
        private static final byte[] PDF_MAGIC = new byte[]{'%', 'P', 'D', 'F', '-'};
        private final MessageDigest digest;
        private final byte[] prefix = new byte[PDF_MAGIC.length];
        private int prefixLength;
        private long count;

        PlaintextVerifier() throws IOException {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException ex) {
                throw new IOException("SHA-256 is unavailable.", ex);
            }
        }

        @Override
        public void write(int b) {
            byte value = (byte) b;
            if (prefixLength < prefix.length) prefix[prefixLength++] = value;
            digest.update(value);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            int copy = Math.min(len, prefix.length - prefixLength);
            if (copy > 0) {
                System.arraycopy(b, off, prefix, prefixLength, copy);
                prefixLength += copy;
            }
            digest.update(b, off, len);
            count += len;
        }

        void finish() throws IOException {
            if (count == 0 || prefixLength != PDF_MAGIC.length) {
                throw new IOException("Decrypted document is not a complete PDF.");
            }
            for (int i = 0; i < PDF_MAGIC.length; i++) {
                if (prefix[i] != PDF_MAGIC[i]) {
                    throw new IOException("Decrypted document no longer has the committed PDF header.");
                }
            }
        }

        long count() { return count; }
        String sha256() { return HexFormat.of().formatHex(digest.digest()); }
    }
}
