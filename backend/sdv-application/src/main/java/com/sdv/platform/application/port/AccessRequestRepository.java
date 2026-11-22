package com.sdv.platform.application.port;

import com.sdv.platform.application.approvals.AccessRequestKind;
import com.sdv.platform.application.approvals.AccessRequestStatus;
import com.sdv.platform.domain.document.SensitivityLevel;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0028: access_requests (migration 027) and the few document facts the approvals need. Every statement is
 * limited to one tenant.
 */
public interface AccessRequestRepository {
    /**
     * Stores a new PENDING request. False, with nothing stored, when the same person already has the same request
     * pending (same kind and document, or the same record).
     */
    boolean insertPending(AccessRequest request);

    /** The request, with its row locked until the transaction ends; empty when unknown. */
    Optional<AccessRequest> lock(UUID tenantId, UUID requestId);

    /**
     * Records a decision on a PENDING request. Task 0037: false when no pending request changed (someone else decided
     * it at the same moment); the caller refuses with SDV-APR-008.
     */
    boolean decide(UUID tenantId, UUID requestId, AccessRequestStatus status, String decidedBy, String decidedByName,
                Instant decidedAt, Instant expiresAt);

    /**
     * Pending requests whose time is up, and approved print or download grants whose use window has ended, become
     * EXPIRED. Returns the requests that changed (as they were before), so each can be audited.
     */
    List<AccessRequest> expireDue(UUID tenantId, Instant now);

    /** An approved, unused, unexpired grant for this person, document and kind (without using it). */
    Optional<UUID> findValidGrant(UUID tenantId, UUID documentId, String requestedBy, AccessRequestKind kind, Instant now);

    /**
     * Uses one such grant: marks it USED in one statement, so two uses at once can't both succeed. Empty when there
     * is none.
     */
    Optional<UUID> useGrant(UUID tenantId, UUID documentId, String requestedBy, AccessRequestKind kind, Instant now);

    /**
     * Task 0043: gives a used grant back: USED becomes APPROVED again and its use time is cleared; its use window
     * (expires_at) is unchanged. Only this grant, of this person, document and kind, and only while it is USED. True
     * when it changed.
     */
    boolean giveBackGrant(UUID tenantId, UUID requestId, UUID documentId, String requestedBy, AccessRequestKind kind);

    /** PENDING requests, oldest first. kinds: null or empty for all; requester: matches the subject or the name exactly. */
    List<AccessRequest> findPending(UUID tenantId, Collection<AccessRequestKind> kinds, String requester, int limit);

    /** This person's own requests, newest first. */
    List<AccessRequest> findByRequester(UUID tenantId, String requestedBy, int limit);

    /**
     * Task 0031 (0.2): requests any Business Admin of the tenant decided at or after {@code since}, newest decision
     * first (whatever happened to them afterwards: used or expired).
     */
    List<AccessRequest> findDecidedSince(UUID tenantId, Instant since, int limit);

    /** The documents' facts a Business Admin may see, plus what the server needs to work out levels and records. */
    List<DocumentFacts> findDocumentFacts(UUID tenantId, Collection<UUID> documentIds);

    /** The record's primary identifier: the submission's first business identifier in display order, and its label. */
    Optional<PrimaryIdentifier> findPrimaryIdentifier(UUID tenantId, UUID submissionId);

    /** The document row's own level and status, locked until the transaction ends; empty when unknown. */
    Optional<LockedDocument> lockDocument(UUID tenantId, UUID documentId);

    /** Sets a document's own level; fails unless exactly one row changed. */
    void updateDocumentLevel(UUID tenantId, UUID documentId, SensitivityLevel level);

    /**
     * Task 0029 (0.4): one row of the level-change history (sensitivity_level_changes), written in the same
     * transaction as the change. The reason lives here, not in the audit.
     */
    void insertLevelChange(LevelChange change);

    /** A document's level changes, newest first. */
    List<LevelChange> findDocumentLevelChanges(UUID tenantId, UUID documentId, int limit);

    /** A record's level changes, newest first. */
    List<LevelChange> findRecordLevelChanges(UUID tenantId, UUID sourceId, String recordKey, int limit);

    record AccessRequest(
            UUID requestId,
            UUID tenantId,
            AccessRequestKind kind,
            UUID documentId,
            UUID sourceId,
            String recordKey,
            SensitivityLevel requestedLevel,
            String reason,
            String requestedBy,
            String requestedByName,
            Instant requestedAt,
            AccessRequestStatus status,
            String decidedBy,
            String decidedByName,
            Instant decidedAt,
            Instant expiresAt,
            Instant usedAt) {
        /** The name to show for the requester: their name when known, otherwise the sign-in subject. */
        public String requesterShown() {
            return requestedByName == null || requestedByName.isBlank() ? requestedBy : requestedByName;
        }
    }

    record DocumentFacts(
            UUID documentId,
            UUID sourceId,
            UUID submissionId,
            String status,
            SensitivityLevel ownLevel,
            Instant receivedAt,
            Integer pageCount,
            String primaryIdentifier,
            String primaryIdentifierLabel) { }

    record PrimaryIdentifier(String value, String label) { }

    record LockedDocument(UUID documentId, String status, SensitivityLevel ownLevel) { }

    /**
     * Task 0029 (0.4): one level change. scope DOCUMENT names documentId; scope RECORD names sourceId and recordKey.
     * from is null only for a record's first level. accessRequestId names the approved request of a lowering.
     */
    record LevelChange(
            UUID changeId,
            UUID tenantId,
            Scope scope,
            UUID documentId,
            UUID sourceId,
            String recordKey,
            Direction direction,
            SensitivityLevel from,
            SensitivityLevel to,
            String reason,
            String changedBy,
            String changedByName,
            Instant changedAt,
            UUID accessRequestId) {
        public enum Scope { DOCUMENT, RECORD }

        public enum Direction { RAISED, LOWERED }
    }
}
