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

    /** Records a decision on a PENDING request; fails unless exactly one pending request changed. */
    void decide(UUID tenantId, UUID requestId, AccessRequestStatus status, String decidedBy, String decidedByName,
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

    /** PENDING requests, oldest first. kinds: null or empty for all; requester: matches the subject or the name exactly. */
    List<AccessRequest> findPending(UUID tenantId, Collection<AccessRequestKind> kinds, String requester, int limit);

    /** This person's own requests, newest first. */
    List<AccessRequest> findByRequester(UUID tenantId, String requestedBy, int limit);

    /** The documents' facts a Business Admin may see, plus what the server needs to work out levels and records. */
    List<DocumentFacts> findDocumentFacts(UUID tenantId, Collection<UUID> documentIds);

    /** The record's primary identifier: the submission's first business identifier in display order, and its label. */
    Optional<PrimaryIdentifier> findPrimaryIdentifier(UUID tenantId, UUID submissionId);

    /** The document row's own level and status, locked until the transaction ends; empty when unknown. */
    Optional<LockedDocument> lockDocument(UUID tenantId, UUID documentId);

    /** Sets a document's own level; fails unless exactly one row changed. */
    void updateDocumentLevel(UUID tenantId, UUID documentId, SensitivityLevel level);

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
}
