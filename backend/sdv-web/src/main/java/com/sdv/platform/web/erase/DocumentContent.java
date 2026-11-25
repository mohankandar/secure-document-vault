package com.sdv.platform.web.erase;

import com.documentplatform.eraselog.EraseHandlerException;
import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.DocumentRedactionRepository;
import com.sdv.platform.application.port.EraseRepository;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.application.retention.RetentionDeletionService;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.DocumentStorageRef;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.storage.StorageVolume;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Task 0080: what the handlers share: erasing a file, erasing a document's whole content, and the audit event in the same transaction. */
@Component
class DocumentContent {
    /** A restored database brought back an original, and the redacted copy that replaced it is lost. */
    static final String AFTER_RESTORE_ACTION = "DOCUMENT_ERASED_AFTER_RESTORE";
    static final String AFTER_RESTORE_REASON = "ERASED_AFTER_RESTORE";

    private final DocumentIntakeRepository documents;
    private final DocumentRedactionRepository redactions;
    private final EraseRepository erased;
    private final StorageVolumeRepository volumes;
    private final List<StorageProvider> providers;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;

    DocumentContent(DocumentIntakeRepository documents, DocumentRedactionRepository redactions, EraseRepository erased,
                    StorageVolumeRepository volumes, List<StorageProvider> providers, AuditEventPort audit, PlatformTransactionManager transactions) {
        this.documents = documents;
        this.redactions = redactions;
        this.erased = erased;
        this.volumes = volumes;
        this.providers = List.copyOf(providers);
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactions);
    }

    /** Deletes the file and makes sure it is gone. A folder that isn't there is a failure, not a file that is gone. */
    void deleteFile(UUID tenantId, String providerId, UUID volumeId, String objectKey) {
        try {
            StorageVolume volume = volumes.findById(tenantId, volumeId).orElseThrow(() -> new IOException("no volume"));
            StorageProvider provider = provider(providerId);
            provider.delete(tenantId, volume, objectKey);
            if (provider.exists(tenantId, volume, objectKey)) throw new IOException("still there");
        } catch (IOException | RuntimeException ex) {
            throw failed();
        }
    }

    private StorageProvider provider(String providerId) throws IOException {
        return providers.stream().filter(p -> p.providerId().equals(providerId)).findFirst().orElseThrow(() -> new IOException("no provider"));
    }

    static EraseHandlerException failed() {
        return new EraseHandlerException(SdvErrorCode.ERS_003.code(), "a file can't be erased: its folder, share or rights");
    }

    static EraseHandlerException refused() {
        return new EraseHandlerException(SdvErrorCode.ERS_004.code(), "it is kept: a legal hold, a quarantine, or its state changed");
    }

    /** True while the document's file is there and its key too. */
    boolean readable(UUID tenantId, UUID documentId) {
        DocumentStorageRef ref = documents.findStorageRef(tenantId, documentId).orElse(null);
        if (ref == null || documents.findCryptoMetadata(tenantId, documentId).isEmpty()) return false;
        try {
            StorageVolume volume = volumes.findById(tenantId, ref.storageVolumeId()).orElseThrow(() -> new IOException("no volume"));
            return provider(ref.providerId()).exists(tenantId, volume, ref.objectKey());
        } catch (IOException | RuntimeException ex) {
            throw failed();
        }
    }

    /** False: nothing was left. {@code auditId} null: a restored database, so the event is about the restore. */
    boolean erase(UUID tenantId, UUID documentId, Instant at, UUID auditId) {
        Document document = documents.findDocumentByIdForTenant(tenantId, documentId).orElse(null);
        if (document == null) return false; // a database older than the document
        DocumentStorageRef ref = documents.findStorageRef(tenantId, documentId).orElse(null);
        boolean key = documents.findCryptoMetadata(tenantId, documentId).isPresent();
        if (document.status() == DocumentStatus.EXPIRED_DELETED && ref == null && !key) return false;
        redactions.findPending(tenantId, documentId).ifPresent(op ->
                deleteFile(tenantId, op.newProviderId(), op.newStorageVolumeId(), op.newObjectKey()));
        if (ref != null) deleteFile(tenantId, ref.providerId(), ref.storageVolumeId(), ref.objectKey());
        boolean restored = document.status() != DocumentStatus.RETENTION_DELETING;
        String reason = auditId == null ? AFTER_RESTORE_REASON : RetentionDeletionService.DELETION_REASON;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("deletionReason", reason);
        details.put("expiresAt", document.expiresAt() == null ? null : document.expiresAt().toString());
        details.put("retentionDaysSnapshot", document.retentionDaysSnapshot());
        details.put("contentSha256", document.contentSha256());
        details.put("ciphertextSha256", ref == null ? null : ref.integrityValue());
        details.put("protectedKeyReferenceRemoved", key);
        details.put("metadataTombstoneRetained", true);
        if (restored) details.put("restoredDatabase", true);
        return audited(auditId == null ? UUID.randomUUID() : auditId, () -> erased.eraseContent(tenantId, documentId, at, reason),
                id -> event(id, tenantId, "retention-scheduler", auditId == null ? AFTER_RESTORE_ACTION : "DOCUMENT_RETENTION_DELETED",
                        "DOCUMENT", documentId, at, null, details));
    }

    /** The change and its audit event, together. The event is written once, also when an entry is applied again. */
    boolean audited(UUID auditId, java.util.function.BooleanSupplier change, Consumer<UUID> event) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            boolean changed = change.getAsBoolean();
            if (changed && !erased.audited(auditId)) event.accept(auditId);
            return changed;
        }));
    }

    void event(UUID id, UUID tenantId, String actor, String action, String targetType, UUID target, Instant at, String correlationId, Map<String, Object> details) {
        try {
            audit.append(new AuditEvent(id, tenantId, "SYSTEM", actor, action, targetType, target.toString(), at, correlationId,
                    AuditOutcome.SUCCESS, VeroJson.mapper().writeValueAsString(details)));
        } catch (JacksonException ex) {
            throw InternalCheckFailed.auditNotWritten("An erasure could not be audited.", ex);
        }
    }
}
