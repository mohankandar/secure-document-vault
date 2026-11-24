package com.sdv.platform.application.redaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.DocumentRedactionRepository;
import com.sdv.platform.application.port.HeldRedactionRepository;
import com.sdv.platform.application.port.HeldRedactionRepository.Sealed;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.application.viewer.DocumentViewerService;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.SdvErrorCode;
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
import java.util.UUID;

/**
 * Task 0066: the sealed originals of documents redacted while on legal hold. Only a Restricted Steward opens one, in the
 * viewer; there is no print or download of it. Once no hold is in place any more it is destroyed, with its data key, and
 * audited. Admins have no way to its content.
 */
@Service
public class SealedOriginalService {
    public static final String DESTROYED_ACTION = "SEALED_ORIGINAL_DESTROYED";
    static final String NONE = "No sealed original has this reference.";

    private final HeldRedactionRepository held;
    private final DocumentIntakeRepository documents;
    private final DocumentRedactionRepository redactions;
    private final StorageVolumeRepository volumes;
    private final List<StorageProvider> providers;
    private final DocumentAccessGuard access;
    private final DocumentViewerService viewer;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock = Clock.systemUTC();
    private final ObjectMapper json = new ObjectMapper();

    public SealedOriginalService(HeldRedactionRepository held, DocumentIntakeRepository documents,
                                 DocumentRedactionRepository redactions, StorageVolumeRepository volumes,
                                 List<StorageProvider> providers, DocumentAccessGuard access, DocumentViewerService viewer,
                                 AuditEventPort audit, PlatformTransactionManager transactionManager) {
        this.held = held;
        this.documents = documents;
        this.redactions = redactions;
        this.volumes = volumes;
        this.providers = List.copyOf(providers);
        this.access = access;
        this.viewer = viewer;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record SealedView(UUID sealedId, Instant sealedAt) { }

    /** The document's sealed originals, oldest first; Restricted Stewards only. */
    public List<SealedView> list(UUID tenantId, UUID documentId, Clearance clearance, String actorId, String correlationId) {
        Document document = documents.findDocumentByIdForTenant(tenantId, documentId).orElseThrow(SealedOriginalService::none);
        access.requireSealedOpenable(document, clearance, DocumentAccessGuard.Attempt.OPEN, actorId, correlationId);
        return held.findSealed(tenantId, documentId).stream().map(s -> new SealedView(s.sealedId(), s.sealedAt())).toList();
    }

    public DocumentViewerService.ViewerMetadata open(UUID tenantId, UUID documentId, UUID sealedId, Clearance clearance,
                                                     String actorId, String correlationId) {
        return viewer.sealedMetadata(require(tenantId, documentId, sealedId, clearance, actorId, correlationId), clearance, actorId, correlationId);
    }

    public DocumentViewerService.PageImage page(UUID tenantId, UUID documentId, UUID sealedId, Clearance clearance, int page,
                                                double scale, UUID viewingId, String actorId, String correlationId) {
        return viewer.sealedPage(require(tenantId, documentId, sealedId, clearance, actorId, correlationId), clearance, page,
                scale, viewingId, actorId, correlationId);
    }

    /** The person is checked before the reference, so it tells nobody else whether there is one. */
    private Sealed require(UUID tenantId, UUID documentId, UUID sealedId, Clearance clearance, String actorId, String correlationId) {
        Document document = documents.findDocumentByIdForTenant(tenantId, documentId).orElseThrow(SealedOriginalService::none);
        access.requireSealedOpenable(document, clearance, DocumentAccessGuard.Attempt.OPEN, actorId, correlationId);
        return held.findSealed(tenantId, documentId, sealedId).orElseThrow(SealedOriginalService::none);
    }

    private static CodedRefusal.NotFound none() {
        return new CodedRefusal.NotFound(SdvErrorCode.HLD_010, NONE);
    }

    public record DestroySweepResult(int destroyed, int failed) { }

    /** Destroys the sealed originals of documents that no hold keeps any more. */
    public DestroySweepResult destroyDue(int limit) {
        int destroyed = 0, failed = 0;
        for (Sealed sealed : held.findToDestroy(limit)) {
            try {
                if (destroy(sealed)) destroyed++;
            } catch (RuntimeException | IOException ex) {
                failed++; // still DESTROYING: a later sweep tries again
            }
        }
        return new DestroySweepResult(destroyed, failed);
    }

    private boolean destroy(Sealed sealed) throws IOException {
        UUID tenantId = sealed.tenantId();
        // Under the document's row lock, as placing a hold: a hold placed first keeps it.
        Boolean mine = transactions.execute(status -> {
            redactions.lockDocument(tenantId, sealed.documentId());
            if (held.isDestroying(tenantId, sealed.sealedId())) return true;
            return !held.onHold(tenantId, sealed.documentId()) && held.startDestroying(tenantId, sealed.sealedId(), clock.instant());
        });
        if (!Boolean.TRUE.equals(mine)) return false;
        StorageVolume volume = volumes.findById(tenantId, sealed.storage().storageVolumeId())
                .orElseThrow(() -> new IOException("The storage volume is not available."));
        StorageProvider provider = providers.stream().filter(p -> p.providerId().equals(sealed.storage().providerId())).findFirst()
                .orElseThrow(() -> new IOException("The storage provider is not available."));
        provider.delete(tenantId, volume, sealed.storage().objectKey());
        if (provider.exists(tenantId, volume, sealed.storage().objectKey())) throw new IOException("The sealed original still exists.");
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Instant now = clock.instant();
            if (!held.markDestroyed(tenantId, sealed.sealedId(), now)) return false;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("sealedOriginalId", sealed.sealedId().toString());
            if (sealed.requestId() != null) details.put("accessRequestId", sealed.requestId().toString());
            details.put("contentSha256", sealed.contentSha256());
            details.put("sealedAt", sealed.sealedAt().toString());
            try {
                audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "SYSTEM", "sdv-legal-hold", DESTROYED_ACTION, "DOCUMENT",
                        sealed.documentId().toString(), now, "sealed-original-" + sealed.sealedId(), AuditOutcome.SUCCESS,
                        json.writeValueAsString(details)));
            } catch (JsonProcessingException ex) {
                throw InternalCheckFailed.auditNotWritten("The destruction could not be audited.", ex);
            }
            return true;
        }));
    }
}
