package com.sdv.platform.application.approvals;

import java.time.Instant;
import java.util.UUID;

/** Task 0071: what approvals need of the legal holds (the hold service), so a release is a decided request. */
public interface HoldReleases {
    /** A hold in place. */
    record Held(UUID holdId, UUID documentId, String placedBy) { }

    /** Refuses (SDV-HLD-007, SDV-HLD-008) when there is no such hold in place. Locks it until the transaction ends. */
    Held requireInPlace(UUID tenantId, String holdReference);

    /** Releases it in the caller's transaction, audited, as the decider. */
    void releaseApproved(UUID tenantId, UUID holdId, UUID accessRequestId, String requestedBy, String actorId, String actorName,
                         Instant at, String correlationId);

    HoldReleases NONE = new HoldReleases() {
        @Override
        public Held requireInPlace(UUID tenantId, String holdReference) {
            throw new com.sdv.platform.domain.error.InternalCheckFailed("Legal holds are not available here.");
        }

        @Override
        public void releaseApproved(UUID tenantId, UUID holdId, UUID accessRequestId, String requestedBy, String actorId,
                                    String actorName, Instant at, String correlationId) {
            throw new com.sdv.platform.domain.error.InternalCheckFailed("Legal holds are not available here.");
        }
    };
}
