package com.sdv.platform.application.port;

import com.sdv.platform.application.port.AccessRequestRepository.AccessRequest;

import java.time.Instant;
import java.util.UUID;

/**
 * Task 0082: one-click Approve and Decline links in the "Approval waiting" e-mail, over platform-core's approvals.
 * Vero's {@code access_requests} stays the one truth: a link only opens Vero's page, which decides through
 * {@code AccessRequestService}; every decision made in Vero then closes the link's request. Nothing here throws, and
 * inside a transaction the work is done after the commit.
 */
public interface ApprovalLinkPort {

    /**
     * A request waits. True when links go out for it (the kinds decided on the link page, with links switched on, and a
     * requester who is not one of the approvers): the caller then sends no plain "Approval waiting". False: the caller
     * sends the plain message.
     */
    boolean offer(AccessRequest request);

    /** Vero decided the request (on any screen): its link's request, if it has one, says so from now on. */
    void decided(UUID tenantId, UUID requestId, boolean approved, String byUserId, String byName, Instant at);

    /** No links (tests, and installs without a link key). */
    ApprovalLinkPort NONE = new ApprovalLinkPort() {
        @Override public boolean offer(AccessRequest request) { return false; }
        @Override public void decided(UUID tenantId, UUID requestId, boolean approved, String byUserId, String byName, Instant at) { }
    };
}
