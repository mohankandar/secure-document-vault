package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.approval.ApprovalAction;
import com.documentplatform.notifications.approval.ApprovalResult;
import com.documentplatform.notifications.approval.Approvals;
import com.documentplatform.notifications.approval.Decision;
import com.documentplatform.notifications.approval.NewApprovalRequest;
import com.sdv.platform.application.approvals.AccessRequestKind;
import com.sdv.platform.application.notifications.VeroEvent;
import com.sdv.platform.application.notifications.VeroNotice;
import com.sdv.platform.application.port.AccessRequestRepository;
import com.sdv.platform.application.port.AccessRequestRepository.AccessRequest;
import com.sdv.platform.application.port.ApprovalLinkPort;
import com.sdv.platform.application.port.NotificationPort;
import com.sdv.platform.application.port.NotificationRecipientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Task 0082: Vero's {@link ApprovalLinkPort} over platform-core's {@link Approvals}.
 * <ul>
 *   <li><b>Which requests get links:</b> print, download and lowering one document's level, the ones decided on the link
 *       page. A whole-record lowering, a redaction on hold, a hold release and an incident release are decided in their
 *       own review, one at a time: they get the plain "Approval waiting" with its link to the review.</li>
 *   <li><b>Nobody who may not decide gets a link:</b> when the requester is on the entity's Business Admin list, no links
 *       go (the plain message does); the requester can't decide their own request anyway.</li>
 *   <li><b>No record identifier</b> in a link message: the module puts it in every channel of the event.</li>
 *   <li>After the commit, so a request that was rolled back sends nothing; a failure falls back to the plain message.</li>
 * </ul>
 * Logs name the request and the exception's class only, never a token or a link.
 */
public class VeroApprovalLinks implements ApprovalLinkPort {
    private static final Logger log = LoggerFactory.getLogger(VeroApprovalLinks.class);
    /** The request types, by the kind of Vero request they are for. */
    static final Map<AccessRequestKind, String> TYPES = Map.of(AccessRequestKind.PRINT, "print", AccessRequestKind.DOWNLOAD,
            "download", AccessRequestKind.LOWER_LEVEL, "lower-level");
    /** Where a decision made in Vero was made, for platform-core's audit of the link's request. */
    static final String IN_VERO = "Vero";
    static final String DECLINED_IN_VERO = "Declined in Vero.";

    private final Approvals approvals;
    private final AccessRequestRepository requests;
    private final NotificationRecipientRepository lists;
    private final Function<UUID, Optional<String>> customerOf;
    private final NotificationPort notifications;

    public VeroApprovalLinks(Approvals approvals, AccessRequestRepository requests, NotificationRecipientRepository lists,
                             Function<UUID, Optional<String>> customerOf, NotificationPort notifications) {
        this.approvals = approvals;
        this.requests = requests;
        this.lists = lists;
        this.customerOf = customerOf;
        this.notifications = notifications;
    }

    @Override
    public boolean offer(AccessRequest r) {
        String type = TYPES.get(r.kind());
        if (type == null) return false;
        Optional<String> customer;
        try {
            customer = customerOf.apply(r.tenantId());
            if (customer.isEmpty()) return false;
            boolean requesterApproves = lists.holders(customer.get(), r.tenantId(), RecipientRoles.BUSINESS_ADMIN).stream()
                    .anyMatch(e -> e.userId().equals(r.requestedBy()));
            if (requesterApproves) return false;
        } catch (RuntimeException ex) {
            return false;
        }
        String tenant = customer.get();
        afterCommit(() -> {
            try {
                var created = approvals.create(new NewApprovalRequest(type, TenantId.of(tenant), EntityId.of(r.tenantId().toString()),
                        r.requestedBy(), null, AppLink.of(VeroEvent.APPROVAL_WAITING.link())));
                requests.setLinkRequest(r.tenantId(), r.requestId(), created.request().id());
            } catch (RuntimeException ex) {
                log.warn("Approval links for request {} not sent ({}); the plain message goes instead.", r.requestId(),
                        ex.getClass().getSimpleName());
                notifications.publish(VeroNotice.of(VeroEvent.APPROVAL_WAITING, r.tenantId())
                        .time(VeroEvent.TIME_EXPIRES, r.expiresAt()).build());
            }
        });
        return true;
    }

    @Override
    public void decided(UUID tenantId, UUID requestId, boolean approved, String byUserId, String byName, Instant at) {
        afterCommit(() -> {
            try {
                Optional<UUID> link = requests.findLinkRequest(tenantId, requestId);
                Optional<String> customer = customerOf.apply(tenantId);
                if (link.isEmpty() || customer.isEmpty()) return;
                Decision decision = new Decision(approved ? ApprovalAction.APPROVE : ApprovalAction.DECLINE, byUserId, byName, at,
                        approved ? null : DECLINED_IN_VERO);
                // Vero tells the requester itself (REQUEST_APPROVED, REQUEST_DECLINED).
                ApprovalResult result = approvals.decidedElsewhere(TenantId.of(customer.get()), link.get(), decision, IN_VERO, false);
                if (result instanceof ApprovalResult.Refused refused) {
                    log.info("The links of request {} were not closed: {}. Vero's decision stands.", requestId, refused.refusal().name());
                }
            } catch (RuntimeException ex) {
                log.warn("The links of request {} were not closed ({}). Vero's decision stands.", requestId, ex.getClass().getSimpleName());
            }
        });
    }

    private static void afterCommit(Runnable work) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
        } else {
            work.run();
        }
    }

    /** The list role of the approvers. */
    static final class RecipientRoles {
        static final String BUSINESS_ADMIN = "BUSINESS_ADMIN";

        private RecipientRoles() { }
    }
}
