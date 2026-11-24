package com.sdv.platform.application.legalhold;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.LegalHoldRepository;
import com.sdv.platform.application.port.LegalHoldRepository.DocumentFacts;
import com.sdv.platform.application.port.LegalHoldRepository.LegalHold;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0027 (R2): legal holds, V1-lite. A Business Admin (MANAGE_LEGAL_HOLD; the web layer checks it) finds a
 * record's documents by its primary identifier, puts one document on hold, and releases the hold later. The retention
 * cleanup never deletes a document with a hold in place. The Business Admin never opens the document: the views carry
 * the primary identifier, the effective level, the received date and the page count, never a title, file name or
 * content. The document ID is in the views for support only; admin screens don't show it.
 * <p>
 * Each place and release is audited in the same transaction as the change, so neither happens without its audit
 * event. The hold's reason is not copied into the audit (it is free text, kept in legal_holds).
 */
@Service
public class LegalHoldService implements com.sdv.platform.application.approvals.HoldReleases {
    public static final String PLACED_ACTION = "LEGAL_HOLD_PLACED";
    public static final String RELEASED_ACTION = "LEGAL_HOLD_RELEASED";
    /** Task 0037: a release refused because the hold was already released (SDV-HLD-008). */
    public static final String RELEASE_REFUSED_ACTION = "LEGAL_HOLD_RELEASE_REFUSED";
    public static final int REASON_MIN = 3;
    public static final int REASON_MAX = 500;
    public static final int IDENTIFIER_MAX = 300;
    static final int FIND_LIMIT = 100;
    static final int LIST_LIMIT = 200;
    /** The retention cleanup deleted these, or is deleting them: they can't be held. */
    private static final Set<String> NOT_HOLDABLE = Set.of("EXPIRED_DELETED", "RETENTION_DELETING");

    private final LegalHoldRepository holds;
    private final DocumentAccessGuard access;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public LegalHoldService(LegalHoldRepository holds, DocumentAccessGuard access, AuditEventPort audit,
                            PlatformTransactionManager transactionManager) {
        this(holds, access, audit, transactionManager, Clock.systemUTC());
    }

    LegalHoldService(LegalHoldRepository holds, DocumentAccessGuard access, AuditEventPort audit,
                     PlatformTransactionManager transactionManager, Clock clock) {
        this.holds = Objects.requireNonNull(holds, "holds");
        this.access = Objects.requireNonNull(access, "access");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = clock;
    }

    /** The documents of the record with this primary identifier value, newest first, at most 100. */
    public List<HoldDocumentView> find(UUID tenantId, String identifier) {
        requireTenant(tenantId);
        String value = identifier == null ? "" : identifier.strip();
        if (value.isEmpty() || value.length() > IDENTIFIER_MAX) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.HLD_003,
                    "Enter the record's identifier (at most " + IDENTIFIER_MAX + " characters).");
        }
        List<DocumentFacts> found = holds.findByPrimaryIdentifier(tenantId, value, FIND_LIMIT);
        Map<UUID, SensitivityLevel> levels = levels(tenantId, found);
        return found.stream().map(d -> HoldDocumentView.of(d, levels.get(d.documentId()))).toList();
    }

    /** Holds in place, newest first, at most 200. */
    public List<LegalHoldView> active(UUID tenantId) {
        requireTenant(tenantId);
        List<LegalHoldRepository.HoldRow> rows = holds.findActive(tenantId, LIST_LIMIT);
        Map<UUID, SensitivityLevel> levels = levels(tenantId, rows.stream().map(LegalHoldRepository.HoldRow::document).toList());
        return rows.stream().map(r -> LegalHoldView.of(r.hold(), r.document(), levels.get(r.document().documentId()))).toList();
    }

    public LegalHoldView place(UUID tenantId, String actorId, String actorDisplayName, String documentReference,
                               String reason, String correlationId) {
        requireTenant(tenantId);
        requireActor(actorId);
        UUID documentId = parse(documentReference, SdvErrorCode.HLD_004, NO_DOCUMENT);
        String why = reason(reason);
        Instant now = clock.instant();
        return transactions.execute(status -> {
            DocumentFacts document = holds.lockDocument(tenantId, documentId)
                    .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.HLD_004, NO_DOCUMENT));
            if (NOT_HOLDABLE.contains(document.status())) {
                throw new CodedRefusal.Conflict(SdvErrorCode.HLD_006,
                        "This document was deleted by the retention cleanup, or is being deleted, so it can't be held.");
            }
            if (document.activeHoldId() != null) {
                throw new CodedRefusal.Conflict(SdvErrorCode.HLD_005, "This document is already on legal hold.");
            }
            LegalHold hold = new LegalHold(UUID.randomUUID(), tenantId, documentId, why, actorId, name(actorDisplayName),
                    now, null, null, null);
            holds.insert(hold);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("holdId", hold.holdId().toString());
            append(tenantId, actorId, actorDisplayName, PLACED_ACTION, documentId, now, correlationId, details);
            SensitivityLevel level = levels(tenantId, List.of(document)).get(documentId);
            return LegalHoldView.of(hold, document, level);
        });
    }

    public LegalHoldView release(UUID tenantId, String actorId, String actorDisplayName, String holdReference,
                                 String correlationId) {
        requireTenant(tenantId);
        requireActor(actorId);
        UUID holdId = parse(holdReference, SdvErrorCode.HLD_007, NO_HOLD);
        Instant now = clock.instant();
        AtomicReference<UUID> heldDocument = new AtomicReference<>();
        try {
            return transactions.execute(status -> releaseLocked(tenantId, actorId, actorDisplayName, holdId, now,
                    correlationId, heldDocument));
        } catch (CodedRefusal.Conflict ex) {
            if (ex.errorCode() == SdvErrorCode.HLD_008) {
                releaseRefused(tenantId, actorId, actorDisplayName, holdId, heldDocument.get(), ex, correlationId);
            }
            throw ex;
        }
    }

    // ---- Task 0071: a release is a request that another Business Admin decides (AccessRequestService) ----

    @Override
    public Held requireInPlace(UUID tenantId, String holdReference) {
        UUID holdId = parse(holdReference, SdvErrorCode.HLD_007, NO_HOLD);
        LegalHold hold = holds.lockHold(tenantId, holdId)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.HLD_007, NO_HOLD));
        if (!hold.active()) throw new CodedRefusal.Conflict(SdvErrorCode.HLD_008, "This legal hold was already released.");
        return new Held(hold.holdId(), hold.documentId(), hold.placedBy());
    }

    @Override
    public void releaseApproved(UUID tenantId, UUID holdId, UUID accessRequestId, String requestedBy, String actorId, String actorName,
                                Instant at, String correlationId) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("accessRequestId", accessRequestId.toString());
        extra.put("requestedBy", requestedBy);
        releaseLocked(tenantId, actorId, actorName, holdId, at, correlationId, new AtomicReference<>(), extra);
    }

    private LegalHoldView releaseLocked(UUID tenantId, String actorId, String actorDisplayName, UUID holdId, Instant now,
                                        String correlationId, AtomicReference<UUID> heldDocument) {
        return releaseLocked(tenantId, actorId, actorDisplayName, holdId, now, correlationId, heldDocument, Map.of());
    }

    private LegalHoldView releaseLocked(UUID tenantId, String actorId, String actorDisplayName, UUID holdId, Instant now,
                                        String correlationId, AtomicReference<UUID> heldDocument,
                                        Map<String, Object> extra) {
        LegalHold hold = holds.lockHold(tenantId, holdId)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.HLD_007, NO_HOLD));
        heldDocument.set(hold.documentId());
        if (!hold.active()) {
            throw new CodedRefusal.Conflict(SdvErrorCode.HLD_008, "This legal hold was already released.");
        }
        Instant releasedAt = now.isBefore(hold.placedAt()) ? hold.placedAt() : now;
        if (!holds.release(tenantId, holdId, actorId, name(actorDisplayName), releasedAt)) {
            // Task 0037 (owner answer 2 to report 0036): released by someone else at the same moment.
            throw new CodedRefusal.Conflict(SdvErrorCode.HLD_008, RELEASED_BY_SOMEONE_ELSE);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("holdId", holdId.toString());
        details.put("placedAt", hold.placedAt().toString());
        details.putAll(extra);
        append(tenantId, actorId, actorDisplayName, RELEASED_ACTION, hold.documentId(), releasedAt, correlationId, details);
        LegalHold released = new LegalHold(hold.holdId(), hold.tenantId(), hold.documentId(), hold.reason(),
                hold.placedBy(), hold.placedByName(), hold.placedAt(), actorId, name(actorDisplayName), releasedAt);
        DocumentFacts document = holds.lockDocument(tenantId, hold.documentId()).orElse(null);
        SensitivityLevel level = document == null ? null : levels(tenantId, List.of(document)).get(hold.documentId());
        return LegalHoldView.of(released, document, level);
    }

    static final String RELEASED_BY_SOMEONE_ELSE = "This hold was already released.";

    /**
     * Task 0037: a release refused with SDV-HLD-008 is audited on its own (the release itself was rolled back), as
     * LEGAL_HOLD_RELEASE_REFUSED. It never stops the refusal.
     */
    private void releaseRefused(UUID tenantId, String actorId, String actorDisplayName, UUID holdId, UUID documentId,
                                CodedRefusal refusal, String correlationId) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("holdId", holdId.toString());
            details.put("code", refusal.errorCode().code());
            String safe = json.writeValueAsString(details);
            audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId, RELEASE_REFUSED_ACTION,
                    documentId == null ? "LEGAL_HOLD" : "DOCUMENT", documentId == null ? holdId.toString() : documentId.toString(),
                    clock.instant(), correlationId, AuditOutcome.DENIED, safe, actorDisplayName));
        } catch (RuntimeException | JsonProcessingException ignored) {
            // The refusal stands even when the audit store is unavailable.
        }
    }

    private static final String NO_DOCUMENT = "No document has this reference.";
    private static final String NO_HOLD = "No legal hold has this reference.";

    private Map<UUID, SensitivityLevel> levels(UUID tenantId, List<DocumentFacts> documents) {
        if (documents.isEmpty()) return Map.of();
        return access.effectiveLevels(tenantId, documents.stream()
                .map(d -> new DocumentAccessGuard.DocumentLevelRef(d.documentId(), d.sourceId(), d.submissionId(), d.ownLevel()))
                .toList());
    }

    private void append(UUID tenantId, String actorId, String actorDisplayName, String action, UUID documentId,
                        Instant at, String correlationId, Map<String, Object> details) {
        String safe;
        try {
            safe = json.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw InternalCheckFailed.auditNotWritten("The legal hold could not be audited, so nothing was changed.", ex);
        }
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId, action, "DOCUMENT",
                documentId.toString(), at, correlationId, AuditOutcome.SUCCESS, safe, actorDisplayName));
    }

    private static String reason(String reason) {
        String value = reason == null ? "" : reason.strip();
        if (value.length() < REASON_MIN || value.length() > REASON_MAX) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.HLD_002,
                    "Give a reason of " + REASON_MIN + " to " + REASON_MAX + " characters.");
        }
        return value;
    }

    private static UUID parse(String reference, SdvErrorCode code, String message) {
        if (reference == null) throw new CodedRefusal.NotFound(code, message);
        try {
            UUID id = UUID.fromString(reference.strip());
            // UUID.fromString accepts short forms such as "1-1-1-1-1"; only the canonical form is a reference.
            if (!id.toString().equalsIgnoreCase(reference.strip())) throw new CodedRefusal.NotFound(code, message);
            return id;
        } catch (IllegalArgumentException ex) {
            throw new CodedRefusal.NotFound(code, message);
        }
    }

    /** Task 0028, 0.3: the name from the signed-in session, or null when the session has none. */
    private static String name(String displayName) {
        return displayName == null || displayName.isBlank() ? null : displayName.strip();
    }

    private static void requireTenant(UUID tenantId) {
        if (tenantId == null) throw new InternalCheckFailed("tenantId is required.");
    }

    private static void requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) throw new InternalCheckFailed("actorId is required.");
    }

    /**
     * A document as a Business Admin sees it. sensitivityLevel is the effective level. documentId is for support
     * only: admin screens don't show it.
     */
    public record HoldDocumentView(
            UUID documentId,
            String primaryIdentifier,
            String primaryIdentifierLabel,
            SensitivityLevel sensitivityLevel,
            Instant receivedAt,
            Integer pageCount,
            boolean onHold,
            UUID activeHoldId) {
        static HoldDocumentView of(DocumentFacts d, SensitivityLevel level) {
            return new HoldDocumentView(d.documentId(), d.primaryIdentifier(), d.primaryIdentifierLabel(), level,
                    d.receivedAt(), d.pageCount(), d.activeHoldId() != null, d.activeHoldId());
        }
    }

    /** A hold with its document as a Business Admin sees it (see {@link HoldDocumentView}). */
    public record LegalHoldView(
            UUID holdId,
            UUID documentId,
            String primaryIdentifier,
            String primaryIdentifierLabel,
            SensitivityLevel sensitivityLevel,
            Instant receivedAt,
            Integer pageCount,
            String reason,
            String placedBy,
            /* Task 0028, 0.3: the person's name at the time; null for holds placed before names were kept. */
            String placedByName,
            Instant placedAt,
            String releasedBy,
            String releasedByName,
            Instant releasedAt) {
        static LegalHoldView of(LegalHold h, DocumentFacts d, SensitivityLevel level) {
            return new LegalHoldView(h.holdId(), h.documentId(),
                    d == null ? null : d.primaryIdentifier(), d == null ? null : d.primaryIdentifierLabel(), level,
                    d == null ? null : d.receivedAt(), d == null ? null : d.pageCount(),
                    h.reason(), h.placedBy(), h.placedByName(), h.placedAt(), h.releasedBy(), h.releasedByName(),
                    h.releasedAt());
        }
    }
}
