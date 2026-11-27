package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.approval.ApprovalAction;
import com.documentplatform.notifications.approval.ApprovalAudit;
import com.documentplatform.notifications.approval.ApprovalRefusal;
import com.documentplatform.notifications.approval.ApprovalRequest;
import com.documentplatform.notifications.approval.ApprovalResult;
import com.documentplatform.notifications.approval.Approvals;
import com.documentplatform.notifications.approval.SignedInPerson;
import com.sdv.platform.application.approvals.AccessRequestService;
import com.sdv.platform.application.port.AccessRequestRepository;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0082: Vero's page for an Approve or Decline link, after sign-in. The token travels in the request body, never
 * in an address, so no access log holds it.
 * <ul>
 *   <li>{@code POST /open} ({@code token}): what the link is for, as Approvals shows it, and the link's action. Decides
 *       nothing (an e-mail scanner that opens links decides nothing).</li>
 *   <li>{@code POST /decide} ({@code token}): the confirming click. Vero decides through {@link AccessRequestService},
 *       with all its checks and its audit (never one's own request, the approver's permission now); then the link's
 *       request is closed. When Vero has decided first (on its own screen, or another link), the page says so.</li>
 * </ul>
 * A refused link (not valid, someone else's, expired, the approver role gone, the request gone) is audited through
 * {@link ApprovalAudit} with its code, never the token.
 */
@RestController
@RequestMapping("/api/v1/approval-links")
public class ApprovalLinkController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final ObjectProvider<Approvals> approvals;
    private final ApprovalAudit audit;
    private final AccessRequestRepository links;
    private final AccessRequestService requests;
    private final PlatformIdentityReads identity;
    private final Clock clock = Clock.systemUTC();

    public ApprovalLinkController(SecurityContextFacade security, AuthorizationService authorization, ObjectProvider<Approvals> approvals,
                                  ApprovalAudit audit, AccessRequestRepository links, AccessRequestService requests,
                                  PlatformIdentityReads identity) {
        this.security = security;
        this.authorization = authorization;
        this.approvals = approvals;
        this.audit = audit;
        this.links = links;
        this.requests = requests;
        this.identity = identity;
    }

    public record LinkRequest(String token) { }

    /**
     * state: READY (decide with the button), DECIDED (just now), ALREADY_DECIDED (by someone, in Vero or by a link),
     * REFUSED (with code and message). action: what the link does. request: what Approvals shows for it, when it waits.
     */
    public record View(String state, String action, String code, String message, AccessRequestService.PendingRequestView request,
                       String decidedAction, String decidedByName, Instant decidedAt) { }

    @PostMapping("/open")
    public ResponseEntity<View> open(@RequestBody(required = false) LinkRequest body) {
        return noStore(look(body, false));
    }

    @PostMapping("/decide")
    public ResponseEntity<View> decide(@RequestBody(required = false) LinkRequest body) {
        return noStore(look(body, true));
    }

    private View look(LinkRequest body, boolean decide) {
        SdvPrincipal p = security.requirePrincipal();
        Approvals a = approvals.getIfAvailable();
        if (a == null) {
            throw new CodedRefusal.Conflict(SdvErrorCode.NTF_007, "Approval links are switched off in this install. Decide in Approvals.");
        }
        String customer = identity.slotOf(p.tenantId()).map(PlatformIdentityReads.EntitySlot::tenantId)
                .orElseThrow(() -> new CodedRefusal.Conflict(SdvErrorCode.NTF_007, "This entity is not set up in identity yet."));
        TenantId tenant = TenantId.of(customer);
        SignedInPerson person = new SignedInPerson(tenant, p.subject(), p.displayName());
        String token = body == null || body.token() == null ? "" : body.token().trim();
        ApprovalResult result = token.isEmpty() ? new ApprovalResult.Refused(ApprovalRefusal.LINK_INVALID) : a.open(token, person);
        if (result instanceof ApprovalResult.Refused r) return refused(tenant, p, null, null, r.refusal());
        ApprovalRequest link = result instanceof ApprovalResult.Ready ready ? ready.request() : ((ApprovalResult.AlreadyDecided) result).request();
        if (!Objects.equals(link.entity(), EntityId.of(p.tenantId().toString()))) {
            throw new CodedRefusal.Conflict(SdvErrorCode.NTF_008, "This request is another entity's. Choose that entity, then open the link again.");
        }
        if (result instanceof ApprovalResult.AlreadyDecided) return alreadyDecided(link);
        ApprovalAction action = ((ApprovalResult.Ready) result).action();
        Optional<UUID> veroId = links.findByLinkRequest(p.tenantId(), link.id());
        Optional<AccessRequestService.PendingRequestView> waiting = veroId.flatMap(id -> waiting(p.tenantId(), id));
        if (waiting.isEmpty()) {
            return new View("ALREADY_DECIDED", action.name(), null, "This request was decided in Vero, or it no longer waits.",
                    null, null, null, null);
        }
        if (!decide) return new View("READY", action.name(), null, null, waiting.get(), null, null, null);
        // Vero decides, with its own checks and audit; then (after the commit) the link's request is closed.
        authorization.require(p, Capability.APPROVE_SENSITIVE_ACCESS);
        AccessRequestService.DecisionView done = requests.decide(p.tenantId(), p.subject(), p.displayName(), veroId.get().toString(),
                action == ApprovalAction.APPROVE ? AccessRequestService.Decision.APPROVE : AccessRequestService.Decision.DECLINE,
                MDC.get("correlationId"));
        return new View("DECIDED", action.name(), null, null, null, done.status().name(), p.displayName(), clock.instant());
    }

    private Optional<AccessRequestService.PendingRequestView> waiting(UUID entity, UUID requestId) {
        return requests.pending(entity, null, null, MDC.get("correlationId")).groups().stream()
                .flatMap(g -> g.requests().stream()).filter(r -> r.requestId().equals(requestId)).findFirst();
    }

    private static View alreadyDecided(ApprovalRequest link) {
        var d = link.decision();
        return new View("ALREADY_DECIDED", null, null, "This request was already " + d.action().pastTense() + ".", null,
                d.action().name(), d.byName(), d.at());
    }

    private View refused(TenantId tenant, SdvPrincipal p, UUID linkId, String type, ApprovalRefusal refusal) {
        audit.record(new ApprovalAudit.Entry(tenant, linkId, EntityId.of(p.tenantId().toString()), type, p.subject(), null,
                ApprovalAudit.Outcome.REFUSED, refusal, clock.instant(), null));
        return new View("REFUSED", null, refusal.name(), refusal.text(), null, null, null, null);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header("Referrer-Policy", "no-referrer").body(body);
    }
}
