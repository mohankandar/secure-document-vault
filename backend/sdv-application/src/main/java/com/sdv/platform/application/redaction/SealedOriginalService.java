package com.sdv.platform.application.redaction;

import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.DocumentRedactionRepository;
import com.sdv.platform.application.port.ErasePort;
import com.sdv.platform.application.port.EraseRepository;
import com.sdv.platform.application.port.HeldRedactionRepository;
import com.sdv.platform.application.port.HeldRedactionRepository.Sealed;
import com.sdv.platform.application.viewer.DocumentViewerService;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
    private final ErasePort erase;
    private final EraseRepository erased;
    private final DocumentAccessGuard access;
    private final DocumentViewerService viewer;
    private final TransactionTemplate transactions;
    private final Clock clock = Clock.systemUTC();

    public SealedOriginalService(HeldRedactionRepository held, DocumentIntakeRepository documents,
                                 DocumentRedactionRepository redactions, ErasePort erase, EraseRepository erased, DocumentAccessGuard access,
                                 DocumentViewerService viewer, PlatformTransactionManager transactionManager) {
        this.held = held;
        this.documents = documents;
        this.redactions = redactions;
        this.erase = erase;
        this.erased = erased;
        this.access = access;
        this.viewer = viewer;
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
                                                double scale, UUID viewingId, String actorName, String actorId, String correlationId) {
        return viewer.sealedPage(require(tenantId, documentId, sealedId, clearance, actorId, correlationId), clearance, page,
                scale, viewingId, actorName, actorId, correlationId);
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
            } catch (ErasePort.EraseFailed ex) {
                failed++;
                if (!ex.refused()) break; // the log goes in order: a later sweep tries again
            } catch (RuntimeException ex) {
                failed++; // still DESTROYING: a later sweep tries again
            }
        }
        return new DestroySweepResult(destroyed, failed);
    }

    private boolean destroy(Sealed sealed) {
        UUID tenantId = sealed.tenantId();
        // Under the document's row lock, as placing a hold: a hold placed first keeps it.
        Boolean mine = transactions.execute(status -> {
            redactions.lockDocument(tenantId, sealed.documentId());
            if (held.isDestroying(tenantId, sealed.sealedId())) return true;
            return !erased.kept(tenantId, sealed.documentId()) && held.startDestroying(tenantId, sealed.sealedId(), clock.instant());
        });
        if (!Boolean.TRUE.equals(mine)) return false;
        // Task 0080: through the erase log, which also writes the audit event.
        erase.sealedOriginal(tenantId, sealed.documentId(), sealed.sealedId(), UUID.randomUUID());
        return true;
    }
}
