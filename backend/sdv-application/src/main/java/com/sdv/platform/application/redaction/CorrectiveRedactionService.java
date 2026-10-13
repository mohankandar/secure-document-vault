package com.sdv.platform.application.redaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.*;
import com.sdv.platform.application.storage.StoragePlacementService;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.*;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.tenant.Tenant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
public class CorrectiveRedactionService {
    private static final int MAX_REGIONS = 100;
    private static final int MAX_REASON_LENGTH = 1000;
    private static final int RECOVERY_STALE_AFTER_MINUTES = 10;

    private final TenantRepository tenants;
    private final IngestionSourceRepository sources;
    private final DocumentIntakeRepository documents;
    private final DocumentRedactionRepository redactions;
    private final StorageVolumeRepository volumes;
    private final StoragePlacementService placement;
    private final List<StorageProvider> storageProviders;
    private final StreamingCryptoPort crypto;
    private final PdfSanitizingRedactor sanitizer;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.systemUTC();

    public CorrectiveRedactionService(
            TenantRepository tenants,
            IngestionSourceRepository sources,
            DocumentIntakeRepository documents,
            DocumentRedactionRepository redactions,
            StorageVolumeRepository volumes,
            StoragePlacementService placement,
            List<StorageProvider> storageProviders,
            StreamingCryptoPort crypto,
            PdfSanitizingRedactor sanitizer,
            AuditEventPort audit,
            PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.sources = sources;
        this.documents = documents;
        this.redactions = redactions;
        this.volumes = volumes;
        this.placement = placement;
        this.storageProviders = List.copyOf(storageProviders);
        this.crypto = crypto;
        this.sanitizer = sanitizer;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public RedactionResult redact(UUID tenantId, UUID documentId, String reason, boolean confirmed,
                                  List<RedactionRegion> requestedRegions, String actorId, String correlationId) {
        if (!confirmed) throw new IllegalArgumentException("Permanent corrective redaction requires explicit confirmation.");
        String normalizedReason = normalizeReason(reason);
        List<RedactionRegion> regions = normalizeRegions(requestedRegions);
        String regionsJson = toJson(regions);
        String requestSha = requestSha256(normalizedReason, regions);

        Document current = documents.findDocumentByIdForTenant(tenantId, documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found."));

        if (current.status() == DocumentStatus.REDACTION_PROCESSING) {
            RedactionOperation pending = redactions.findPending(tenantId, documentId)
                    .orElseThrow(() -> new RedactionFailedException(
                            "Document has an incomplete corrective-redaction state requiring operational recovery."));
            if (!pending.actorId().equals(actorId) || !pending.requestSha256().equals(requestSha)) {
                throw new IllegalStateException("A different corrective redaction is already in progress.");
            }
            UUID claimToken = UUID.randomUUID();
            RedactionOperation claimed = redactions.claimPendingForCompletion(tenantId, documentId, claimToken)
                    .orElseThrow(() -> new IllegalStateException(
                            "Corrective redaction is already being completed or recovered by another instance."));
            try {
                return completeStaged(claimed, regions.size(), correlationId);
            } catch (RuntimeException ex) {
                safeReleaseRecoveryClaim(claimed, claimToken);
                safeFailureAudit(tenantId, documentId, actorId, normalizedReason, regions.size(), correlationId, ex);
                throw ex;
            }
        }
        if (current.status() != DocumentStatus.AVAILABLE) {
            throw new IllegalStateException("Only AVAILABLE documents may be permanently redacted.");
        }

        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found."));
        IngestionSource source = sources.findById(tenantId, current.sourceId())
                .orElseThrow(() -> new NoSuchElementException("Ingestion source not found."));

        DocumentStorageRef oldStorage = documents.findStorageRef(tenantId, documentId)
                .orElseThrow(() -> new RedactionFailedException("Document storage metadata is unavailable."));
        DocumentCryptoMetadata oldCrypto = documents.findCryptoMetadata(tenantId, documentId)
                .orElseThrow(() -> new RedactionFailedException("Document crypto metadata is unavailable."));
        StorageVolume oldVolume = volumes.findById(tenantId, oldStorage.storageVolumeId())
                .orElseThrow(() -> new RedactionFailedException("Document storage volume is unavailable."));
        StorageProvider oldProvider = provider(oldStorage.providerId());

        byte[] plaintext = null;
        byte[] sanitized = null;
        StorageProvider.StoredObject newStored = null;
        StorageVolume newVolume = null;
        StorageProvider newProvider = null;
        boolean staged = false;

        try {
            plaintext = verifiedPlaintext(current, oldStorage, oldCrypto, oldVolume, oldProvider);
            sanitized = sanitizer.sanitize(plaintext, regions, effectiveMaxPdfBytes(tenant, source));
            String newContentSha = sha256(sanitized);

            newVolume = placement.select(tenantId, estimateEncryptedSize(sanitized.length)).volume();
            newProvider = provider(newVolume.providerId());

            final StreamingCryptoPort.CryptoEnvelope[] holder = new StreamingCryptoPort.CryptoEnvelope[1];
            byte[] replacementBytes = sanitized;
            newStored = newProvider.store(tenantId, newVolume, output ->
                    holder[0] = crypto.encrypt(tenantId, new ByteArrayInputStream(replacementBytes), output));
            StreamingCryptoPort.CryptoEnvelope envelope = Objects.requireNonNull(
                    holder[0], "Corrective-redaction encryption did not return envelope metadata.");

            if (newStored.sizeBytes() != envelope.encryptedSizeBytes()) {
                throw new RedactionFailedException("Replacement ciphertext size did not match crypto metadata.");
            }

            RedactionOperation operation = new RedactionOperation(
                    UUID.randomUUID(), tenantId, documentId, current.sourceId(), current.submissionId(),
                    actorId, requestSha, normalizedReason, regionsJson,
                    oldStorage.providerId(), oldStorage.storageVolumeId(), oldStorage.objectKey(),
                    oldStorage.encryptedSizeBytes(), oldStorage.integrityValue(),
                    current.contentSha256(), Objects.requireNonNull(current.contentSizeBytes()),
                    newVolume.providerId(), newVolume.volumeId(), newStored.objectKey(),
                    envelope.encryptedSizeBytes(), envelope.ciphertextSha256(),
                    envelope.cryptoProfile(), envelope.cryptoVersion(), envelope.keyProviderId(),
                    envelope.protectedDataKeyReference(), envelope.keyProviderVersion(),
                    newContentSha, sanitized.length, clock.instant());

            Boolean stageResult = transactions.execute(status -> stage(operation, oldStorage, oldCrypto));
            staged = Boolean.TRUE.equals(stageResult);
            if (!staged) throw new IllegalStateException("Corrective redaction could not acquire the current document version.");
            UUID claimToken = UUID.randomUUID();
            RedactionOperation claimed = redactions.claimPendingForCompletion(tenantId, documentId, claimToken)
                    .orElseThrow(() -> new IllegalStateException("Corrective redaction could not acquire its completion lease."));
            try {
                return completeStaged(claimed, regions.size(), correlationId);
            } catch (RuntimeException ex) {
                safeReleaseRecoveryClaim(claimed, claimToken);
                throw ex;
            }
        } catch (RuntimeException ex) {
            if (!staged && newProvider != null && newVolume != null && newStored != null) {
                safeDelete(newProvider, tenantId, newVolume, newStored.objectKey());
            }
            safeFailureAudit(tenantId, documentId, actorId, normalizedReason, regions.size(), correlationId, ex);
            throw ex;
        } catch (IOException ex) {
            if (!staged && newProvider != null && newVolume != null && newStored != null) {
                safeDelete(newProvider, tenantId, newVolume, newStored.objectKey());
            }
            safeFailureAudit(tenantId, documentId, actorId, normalizedReason, regions.size(), correlationId, ex);
            throw new RedactionFailedException("Corrective redaction failed safely.", ex);
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            if (sanitized != null) Arrays.fill(sanitized, (byte) 0);
        }
    }

    public RecoverySweepResult recoverStale(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100.");
        UUID claimToken = UUID.randomUUID();
        List<RedactionOperation> claimed = redactions.claimStaleForRecovery(RECOVERY_STALE_AFTER_MINUTES, limit, claimToken);
        int completed = 0;
        int failed = 0;
        for (RedactionOperation operation : claimed) {
            int regionCount = 0;
            String recoveryCorrelationId = "redaction-recovery-" + operation.operationId();
            try {
                regionCount = regionCount(operation.regionsJson());
                completeStaged(operation, regionCount, recoveryCorrelationId);
                completed++;
            } catch (RuntimeException ex) {
                failed++;
                safeFailureAudit(operation.tenantId(), operation.documentId(), operation.actorId(),
                        operation.reason(), regionCount, recoveryCorrelationId, ex);
                // Keep the DB-time claim in place as a bounded retry backoff. A crashed or
                // failed recovery becomes eligible again after the same 10-minute lease.
            }
        }
        return new RecoverySweepResult(claimed.size(), completed, failed);
    }

    private int regionCount(String regionsJson) {
        try {
            var node = json.readTree(regionsJson);
            if (!node.isArray()) throw new IllegalStateException("Stored redaction regions are not an array.");
            return node.size();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored redaction regions cannot be parsed for recovery.", ex);
        }
    }

    private boolean stage(RedactionOperation operation, DocumentStorageRef expectedStorage,
                          DocumentCryptoMetadata expectedCrypto) {
        Document locked = redactions.lockDocument(operation.tenantId(), operation.documentId())
                .orElseThrow(() -> new NoSuchElementException("Document not found."));
        if (locked.status() != DocumentStatus.AVAILABLE
                || !Objects.equals(locked.contentSha256(), operation.oldContentSha256())) return false;

        DocumentStorageRef currentStorage = documents.findStorageRef(operation.tenantId(), operation.documentId())
                .orElseThrow(() -> new IllegalStateException("Document storage metadata disappeared."));
        DocumentCryptoMetadata currentCrypto = documents.findCryptoMetadata(operation.tenantId(), operation.documentId())
                .orElseThrow(() -> new IllegalStateException("Document crypto metadata disappeared."));
        if (!currentStorage.equals(expectedStorage) || !currentCrypto.equals(expectedCrypto)) return false;

        redactions.insertPending(operation);
        redactions.markRedactionProcessing(operation.tenantId(), operation.documentId(), operation.oldContentSha256());
        return true;
    }

    private RedactionResult completeStaged(RedactionOperation operation, int regionCount, String correlationId) {
        StorageVolume newVolume = volumes.findById(operation.tenantId(), operation.newStorageVolumeId())
                .orElseThrow(() -> new RedactionFailedException("Replacement storage volume is unavailable."));
        StorageProvider newProvider = provider(operation.newProviderId());
        verifyReplacementObject(operation, newVolume, newProvider);

        StorageVolume oldVolume = volumes.findById(operation.tenantId(), operation.oldStorageVolumeId())
                .orElseThrow(() -> new RedactionFailedException("Original storage volume is unavailable."));
        StorageProvider oldProvider = provider(operation.oldProviderId());

        try {
            oldProvider.delete(operation.tenantId(), oldVolume, operation.oldObjectKey());
            if (oldProvider.exists(operation.tenantId(), oldVolume, operation.oldObjectKey())) {
                throw new IOException("Original ciphertext still exists after corrective-redaction cleanup.");
            }
        } catch (IOException ex) {
            throw new RedactionFailedException(
                    "Permanent redaction cannot complete until the prior ciphertext is removed.", ex);
        }

        Instant completedAt = clock.instant();
        RedactionResult result = transactions.execute(status ->
                finalizeTransaction(operation, regionCount, correlationId, completedAt));
        if (result == null) throw new RedactionFailedException("Corrective redaction finalization returned no result.");
        return result;
    }

    private RedactionResult finalizeTransaction(RedactionOperation expected, int regionCount,
                                                String correlationId, Instant completedAt) {
        RedactionOperation pending = redactions.lockPending(expected.tenantId(), expected.documentId())
                .orElseThrow(() -> new RedactionFailedException("Pending corrective-redaction state is unavailable."));
        if (!pending.operationId().equals(expected.operationId())
                || !pending.requestSha256().equals(expected.requestSha256())) {
            throw new IllegalStateException("Corrective redaction pending state changed unexpectedly.");
        }

        Document locked = redactions.lockDocument(expected.tenantId(), expected.documentId())
                .orElseThrow(() -> new NoSuchElementException("Document not found."));
        if (locked.status() != DocumentStatus.REDACTION_PROCESSING) {
            throw new IllegalStateException("Document is not in corrective-redaction processing state.");
        }

        redactions.finalizeReplacement(
                expected.tenantId(), expected.documentId(), expected.oldContentSha256(),
                expected.newContentSha256(), expected.newContentSizeBytes(),
                new DocumentStorageRef(expected.documentId(), expected.tenantId(), expected.newProviderId(),
                        expected.newStorageVolumeId(), expected.newObjectKey(),
                        expected.newEncryptedSizeBytes(), expected.newIntegrityValue()),
                new DocumentCryptoMetadata(expected.documentId(), expected.tenantId(),
                        expected.newCryptoProfile(), expected.newCryptoVersion(), expected.newKeyProviderId(),
                        expected.newProtectedDataKeyReference(), expected.newKeyProviderVersion()),
                completedAt);

        audit.append(new AuditEvent(
                UUID.randomUUID(), expected.tenantId(), "USER", expected.actorId(),
                "DOCUMENT_PERMANENTLY_REDACTED", "DOCUMENT", expected.documentId().toString(),
                completedAt, correlationId, AuditOutcome.SUCCESS, successAuditJson(expected, regionCount)));

        redactions.deletePending(expected.tenantId(), expected.operationId());

        Document updated = documents.findDocumentByIdForTenant(expected.tenantId(), expected.documentId())
                .orElseThrow(() -> new IllegalStateException("Redacted document could not be reloaded."));
        return new RedactionResult(expected.operationId(), expected.documentId(), updated.status().name(),
                regionCount, updated.contentSha256(), updated.expiresAt());
    }

    private byte[] verifiedPlaintext(Document document, DocumentStorageRef storageRef,
                                     DocumentCryptoMetadata cryptoMetadata, StorageVolume volume,
                                     StorageProvider provider) throws IOException {
        Long expectedSize = document.contentSizeBytes();
        if (expectedSize == null || expectedSize <= 0 || expectedSize > Integer.MAX_VALUE - 8L) {
            throw new IOException("Document size is outside the corrective-redaction in-memory boundary.");
        }
        FixedPlaintextBuffer plaintext = new FixedPlaintextBuffer(Math.toIntExact(expectedSize), document.contentSha256());
        try (InputStream raw = provider.open(document.tenantId(), volume, storageRef.objectKey());
             DigestingCountingInputStream encrypted = new DigestingCountingInputStream(raw)) {
            crypto.decrypt(document.tenantId(), envelope(cryptoMetadata, storageRef), encrypted, plaintext);
            plaintext.finish();
            if (encrypted.count() != storageRef.encryptedSizeBytes()
                    || !encrypted.sha256().equals(storageRef.integrityValue())) {
                plaintext.destroy();
                throw new IOException("Original ciphertext no longer matches committed custody metadata.");
            }
            return plaintext.take();
        } catch (IOException | RuntimeException ex) {
            plaintext.destroy();
            throw ex;
        }
    }

    private void verifyReplacementObject(RedactionOperation op, StorageVolume volume, StorageProvider provider) {
        if (op.newContentSizeBytes() > Integer.MAX_VALUE - 8L) {
            throw new RedactionFailedException("Replacement document exceeds the in-memory verification boundary.");
        }
        FixedPlaintextBuffer plaintext = null;
        try {
            if (!provider.exists(op.tenantId(), volume, op.newObjectKey())) throw new IOException("Replacement ciphertext object is missing.");
            plaintext = new FixedPlaintextBuffer(Math.toIntExact(op.newContentSizeBytes()), op.newContentSha256());
            try (InputStream raw = provider.open(op.tenantId(), volume, op.newObjectKey());
                 DigestingCountingInputStream encrypted = new DigestingCountingInputStream(raw)) {
                crypto.decrypt(op.tenantId(),
                        new StreamingCryptoPort.CryptoEnvelope(
                                op.newCryptoProfile(), op.newCryptoVersion(), op.newKeyProviderId(),
                                op.newProtectedDataKeyReference(), op.newKeyProviderVersion(),
                                op.newEncryptedSizeBytes(), op.newIntegrityValue()),
                        encrypted, plaintext);
                plaintext.finish();
                if (encrypted.count() != op.newEncryptedSizeBytes()
                        || !encrypted.sha256().equals(op.newIntegrityValue())) {
                    throw new IOException("Replacement ciphertext integrity verification failed.");
                }
            }
        } catch (IOException | RuntimeException ex) {
            throw new RedactionFailedException("Replacement ciphertext failed pre-switch verification.", ex);
        } finally {
            if (plaintext != null) plaintext.destroy();
        }
    }

    private static StreamingCryptoPort.CryptoEnvelope envelope(DocumentCryptoMetadata crypto,
                                                                DocumentStorageRef storage) {
        return new StreamingCryptoPort.CryptoEnvelope(
                crypto.cryptoProfile(), crypto.cryptoVersion(), crypto.keyProviderId(),
                crypto.protectedDataKeyReference(), crypto.keyProviderVersion(),
                storage.encryptedSizeBytes(), storage.integrityValue());
    }

    private StorageProvider provider(String providerId) {
        return storageProviders.stream().filter(p -> p.providerId().equals(providerId)).findFirst()
                .orElseThrow(() -> new RedactionFailedException("Configured storage provider is unavailable."));
    }

    private static long effectiveMaxPdfBytes(Tenant tenant, IngestionSource source) {
        Long sourceLimit = source.limits().maxPdfBytes();
        return sourceLimit == null ? tenant.guardrails().maxPdfBytes()
                : Math.min(tenant.guardrails().maxPdfBytes(), sourceLimit);
    }

    private static long estimateEncryptedSize(long plaintextBytes) {
        long chunks = Math.max(1L, (plaintextBytes + 65535L) / 65536L);
        try {
            return Math.addExact(plaintextBytes, Math.addExact(64L, Math.multiplyExact(chunks, 32L)));
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private static String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Permanent corrective redaction requires a reason.");
        String value = reason.trim();
        if (value.length() < 3 || value.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Redaction reason must be between 3 and " + MAX_REASON_LENGTH + " characters.");
        }
        return value;
    }

    private static List<RedactionRegion> normalizeRegions(List<RedactionRegion> regions) {
        if (regions == null || regions.isEmpty()) throw new IllegalArgumentException("At least one redaction region is required.");
        if (regions.size() > MAX_REGIONS) throw new IllegalArgumentException("At most " + MAX_REGIONS + " redaction regions are allowed.");
        return regions.stream().sorted(Comparator.comparingInt(RedactionRegion::pageNumber)
                        .thenComparingDouble(RedactionRegion::y).thenComparingDouble(RedactionRegion::x)
                        .thenComparingDouble(RedactionRegion::height).thenComparingDouble(RedactionRegion::width))
                .toList();
    }

    private String toJson(List<RedactionRegion> regions) {
        try { return json.writeValueAsString(regions); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Could not serialize redaction regions.", ex); }
    }

    private String successAuditJson(RedactionOperation op, int regionCount) {
        try {
            Map<String,Object> safe = new LinkedHashMap<>();
            safe.put("operationId", op.operationId().toString());
            safe.put("reason", op.reason());
            safe.put("regions", json.readTree(op.regionsJson()));
            safe.put("regionCount", regionCount);
            safe.put("oldContentSha256", op.oldContentSha256());
            safe.put("newContentSha256", op.newContentSha256());
            safe.put("oldCiphertextSha256", op.oldIntegrityValue());
            safe.put("newCiphertextSha256", op.newIntegrityValue());
            safe.put("retentionPreserved", true);
            return json.writeValueAsString(safe);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize redaction audit details.", ex);
        }
    }

    private void safeFailureAudit(UUID tenantId, UUID documentId, String actorId, String reason,
                                  int regionCount, String correlationId, Exception failure) {
        try {
            Map<String,Object> safe = new LinkedHashMap<>();
            safe.put("reason", reason);
            safe.put("regionCount", regionCount);
            safe.put("errorCode", failure.getClass().getSimpleName());
            audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId,
                    "DOCUMENT_REDACTION_FAILED", "DOCUMENT", documentId.toString(), clock.instant(),
                    correlationId, AuditOutcome.FAILED, json.writeValueAsString(safe)));
        } catch (Exception ignored) {
        }
    }

    private static String requestSha256(String reason, List<RedactionRegion> regions) {
        StringBuilder canonical = new StringBuilder(reason).append('\n');
        for (RedactionRegion r : regions) {
            canonical.append(r.pageNumber()).append('|').append(Double.toHexString(r.x())).append('|')
                    .append(Double.toHexString(r.y())).append('|').append(Double.toHexString(r.width())).append('|')
                    .append(Double.toHexString(r.height())).append('\n');
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable.", ex); }
    }

    private void safeReleaseRecoveryClaim(RedactionOperation operation, UUID claimToken) {
        try {
            redactions.releaseRecoveryClaim(operation.tenantId(), operation.operationId(), claimToken);
        } catch (RuntimeException ignored) {
        }
    }

    public record RecoverySweepResult(int candidates, int completed, int failed) { }

    private static void safeDelete(StorageProvider provider, UUID tenantId, StorageVolume volume, String objectKey) {
        try { provider.delete(tenantId, volume, objectKey); } catch (Exception ignored) { }
    }

    private static final class FixedPlaintextBuffer extends OutputStream {
        private static final byte[] PDF_MAGIC = new byte[]{'%', 'P', 'D', 'F', '-'};
        private final byte[] data;
        private final String expectedSha;
        private final MessageDigest digest;
        private int position;

        FixedPlaintextBuffer(int size, String expectedSha) throws IOException {
            data = new byte[size]; this.expectedSha = expectedSha;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException ex) { throw new IOException("SHA-256 is unavailable.", ex); }
        }
        @Override public void write(int b) throws IOException {
            if (position >= data.length) throw new IOException("Decrypted PDF exceeded committed plaintext size.");
            data[position++] = (byte)b; digest.update((byte)b);
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (len < 0 || position > data.length - len) throw new IOException("Decrypted PDF exceeded committed plaintext size.");
            System.arraycopy(b, off, data, position, len); digest.update(b, off, len); position += len;
        }
        void finish() throws IOException {
            if (position != data.length) throw new IOException("Decrypted PDF size mismatch.");
            if (data.length < PDF_MAGIC.length) throw new IOException("Decrypted content is not a PDF.");
            for (int i=0;i<PDF_MAGIC.length;i++) if (data[i] != PDF_MAGIC[i]) throw new IOException("Decrypted content has invalid PDF header.");
            if (!Objects.equals(expectedSha, HexFormat.of().formatHex(digest.digest()))) throw new IOException("Decrypted PDF fingerprint mismatch.");
        }
        byte[] take() { return data; }
        void destroy() { Arrays.fill(data, (byte)0); }
    }

    private static final class DigestingCountingInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private long count;
        DigestingCountingInputStream(InputStream input) throws IOException {
            super(input);
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException ex) { throw new IOException("SHA-256 is unavailable.", ex); }
        }
        @Override public int read() throws IOException {
            int v=super.read(); if(v!=-1){digest.update((byte)v); count++;} return v;
        }
        @Override public int read(byte[] b,int off,int len)throws IOException{
            int r=super.read(b,off,len); if(r>0){digest.update(b,off,r); count+=r;} return r;
        }
        long count(){return count;}
        String sha256(){return HexFormat.of().formatHex(digest.digest());}
    }
}
