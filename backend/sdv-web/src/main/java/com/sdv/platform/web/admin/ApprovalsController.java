package com.sdv.platform.web.admin;

import com.sdv.platform.application.approvals.AccessRequestService;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Task 0028 (S6): the Business Admin's approvals (APPROVE_SENSITIVE_ACCESS). Anyone else is refused with
 * SDV-APR-005 (and the refusal is audited as AUTHORIZATION_DENIED). No screen uses these yet (S9a).
 * <ul>
 *   <li>{@code GET ?kind=&requester=}: what is waiting, oldest first, grouped by the record's primary identifier;</li>
 *   <li>{@code POST /{requestId}/approve} and {@code /decline}: one decision (whole-record requests only this way);</li>
 *   <li>{@code POST /decisions} with {@code {decision, requestIds}}: many at once; each is checked and audited on its
 *       own, and the answer says which were decided.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/access-requests")
public class ApprovalsController {
    static final String REFUSED = "Only a Business Admin can see and decide requests.";

    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final AccessRequestService requests;

    public ApprovalsController(SecurityContextFacade security, AuthorizationService authorization,
                               AccessRequestService requests) {
        this.security = security;
        this.authorization = authorization;
        this.requests = requests;
    }

    @GetMapping
    public ResponseEntity<AccessRequestService.PendingList> pending(@RequestParam(required = false) String kind,
                                                                    @RequestParam(required = false) String requester) {
        SdvPrincipal p = requireApprover();
        return noStore(requests.pending(p.tenantId(), kind, requester, MDC.get("correlationId")));
    }

    /** Task 0031 (0.2): recent decisions by every Business Admin of the tenant, newest first (no reasons). */
    @GetMapping("/decided")
    public ResponseEntity<List<AccessRequestService.DecidedView>> decided() {
        SdvPrincipal p = requireApprover();
        return noStore(requests.decidedRecently(p.tenantId()));
    }

    @PostMapping("/{requestId}/approve")
    public ResponseEntity<AccessRequestService.DecisionView> approve(@PathVariable String requestId) {
        SdvPrincipal p = requireApprover();
        return noStore(requests.decide(p.tenantId(), p.subject(), p.displayName(), requestId,
                AccessRequestService.Decision.APPROVE, MDC.get("correlationId")));
    }

    @PostMapping("/{requestId}/decline")
    public ResponseEntity<AccessRequestService.DecisionView> decline(@PathVariable String requestId) {
        SdvPrincipal p = requireApprover();
        return noStore(requests.decide(p.tenantId(), p.subject(), p.displayName(), requestId,
                AccessRequestService.Decision.DECLINE, MDC.get("correlationId")));
    }

    @PostMapping("/decisions")
    public ResponseEntity<AccessRequestService.BulkResult> decideMany(@RequestBody(required = false) BulkDecision body) {
        SdvPrincipal p = requireApprover();
        BulkDecision b = body == null ? new BulkDecision(null, null) : body;
        return noStore(requests.decideMany(p.tenantId(), p.subject(), p.displayName(), b.decision(), b.requestIds(),
                MDC.get("correlationId")));
    }

    private SdvPrincipal requireApprover() {
        SdvPrincipal principal = security.requirePrincipal();
        try {
            authorization.require(principal, Capability.APPROVE_SENSITIVE_ACCESS);
        } catch (AccessDeniedException ex) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.APR_005, REFUSED);
        }
        return principal;
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.status(HttpStatus.OK).cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate()).body(body);
    }

    public record BulkDecision(String decision, List<String> requestIds) { }
}
