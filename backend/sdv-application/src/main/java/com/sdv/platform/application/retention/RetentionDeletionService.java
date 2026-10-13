package com.sdv.platform.application.retention;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.RetentionRepository;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.DocumentStorageRef;
import com.sdv.platform.domain.storage.StorageVolume;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
public class RetentionDeletionService {
    public static final String DELETION_REASON = "RETENTION_EXPIRED";

    private final RetentionRepository retention;
    private final DocumentIntakeRepository documents;
    private final StorageVolumeRepository volumes;
    private final List<StorageProvider> storageProviders;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.systemUTC();

    public RetentionDeletionService(
            RetentionRepository retention,
            DocumentIntakeRepository documents,
            StorageVolumeRepository volumes,
            List<StorageProvider> storageProviders,
            AuditEventPort audit,
            PlatformTransactionManager transactionManager) {
        this.retention = retention;
        this.documents = documents;
        this.volumes = volumes;
        this.storageProviders = List.copyOf(storageProviders);
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public SweepResult sweep(int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        List<RetentionRepository.DueDocument> due = retention.findDueDocuments(bounded);
        int completed = 0;
        int failed = 0;
        for (RetentionRepository.DueDocument candidate : due) {
            try {
                if (expire(candidate.tenantId(), candidate.documentId())) completed++;
            } catch (RuntimeException ex) {
                failed++;
            }
        }
        return new SweepResult(due.size(), completed, failed);
    }

    boolean expire(UUID tenantId, UUID documentId) {
        Instant startedAt = clock.instant();
        if (!retention.claimDueDocument(tenantId, documentId, startedAt)) return false;

        Document document = documents.findDocumentByIdForTenant(tenantId, documentId)
                .orElseThrow(() -> new NoSuchElementException("Retention candidate document not found."));
        if (document.status() != DocumentStatus.RETENTION_DELETING) {
            return false;
        }
        if (document.expiresAt() == null) {
            throw new IllegalStateException("Retention deletion cannot proceed without a snapshotted expiration.");
        }

        Optional<DocumentStorageRef> storageOptional = documents.findStorageRef(tenantId, documentId);
        Optional<DocumentCryptoMetadata> cryptoOptional = documents.findCryptoMetadata(tenantId, documentId);
        String ciphertextSha = storageOptional.map(DocumentStorageRef::integrityValue).orElse(null);

        try {
            if (storageOptional.isPresent()) {
                DocumentStorageRef storage = storageOptional.get();
                StorageVolume volume = volumes.findById(tenantId, storage.storageVolumeId())
                        .orElseThrow(() -> new IllegalStateException("Retention storage volume is unavailable."));
                StorageProvider provider = provider(storage.providerId());
                provider.delete(tenantId, volume, storage.objectKey());
                if (provider.exists(tenantId, volume, storage.objectKey())) {
                    throw new IOException("Protected ciphertext still exists after retention deletion attempt.");
                }
            }

            Instant deletedAt = clock.instant();
            transactions.executeWithoutResult(status -> {
                retention.finalizeExpiredDocument(tenantId, documentId, deletedAt, DELETION_REASON);
                audit.append(new AuditEvent(
                        UUID.randomUUID(), tenantId, "SYSTEM", "retention-scheduler",
                        "DOCUMENT_RETENTION_DELETED", "DOCUMENT", documentId.toString(),
                        deletedAt, null, AuditOutcome.SUCCESS,
                        successDetails(document, ciphertextSha, cryptoOptional.isPresent())));
            });
            return true;
        } catch (Exception ex) {
            safeFailureAudit(document, ex);
            throw ex instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException("Retention deletion could not complete safely.", ex);
        }
    }

    private StorageProvider provider(String providerId) {
        return storageProviders.stream()
                .filter(candidate -> candidate.providerId().equals(providerId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Configured storage provider is unavailable."));
    }

    private String successDetails(Document document, String ciphertextSha, boolean keyReferenceWasPresent) {
        try {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("deletionReason", DELETION_REASON);
            safe.put("expiresAt", document.expiresAt().toString());
            safe.put("retentionDaysSnapshot", document.retentionDaysSnapshot());
            safe.put("contentSha256", document.contentSha256());
            safe.put("ciphertextSha256", ciphertextSha);
            safe.put("protectedKeyReferenceRemoved", keyReferenceWasPresent);
            safe.put("metadataTombstoneRetained", true);
            return json.writeValueAsString(safe);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize retention deletion audit details.", ex);
        }
    }

    private void safeFailureAudit(Document document, Exception failure) {
        try {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("deletionReason", DELETION_REASON);
            safe.put("errorCode", failure.getClass().getSimpleName());
            audit.append(new AuditEvent(
                    UUID.randomUUID(), document.tenantId(), "SYSTEM", "retention-scheduler",
                    "DOCUMENT_RETENTION_DELETE_FAILED", "DOCUMENT", document.documentId().toString(),
                    clock.instant(), null, AuditOutcome.FAILED,
                    json.writeValueAsString(safe)));
        } catch (Exception ignored) {
        }
    }

    public record SweepResult(int candidates, int completed, int failed) { }
}
