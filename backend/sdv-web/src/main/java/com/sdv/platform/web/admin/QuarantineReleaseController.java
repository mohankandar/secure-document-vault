package com.sdv.platform.web.admin;

import com.sdv.platform.application.quarantine.QuarantineService;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Task 0048: requests to release a whole quarantine incident, for the Business Admin's Approvals list
 * (APPROVE_SENSITIVE_ACCESS, as access requests; anyone else gets SDV-APR-005). One request at a time: there is no
 * bulk decision for these. The views carry the incident name, document count, levels and dates, never a reason,
 * title, file name or content.
 */
@RestController
@RequestMapping("/api/v1/admin/quarantine-releases")
public class QuarantineReleaseController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final QuarantineService quarantine;

    public QuarantineReleaseController(SecurityContextFacade security, AuthorizationService authorization,
                                       QuarantineService quarantine) {
        this.security = security;
        this.authorization = authorization;
        this.quarantine = quarantine;
    }

    @GetMapping
    public ResponseEntity<List<QuarantineService.ReleaseRequestView>> pending() {
        return noStore(quarantine.pendingReleaseRequests(approver()));
    }

    @PostMapping("/{requestId}/approve")
    public ResponseEntity<QuarantineService.ReleaseRequestView> approve(@PathVariable String requestId) {
        return noStore(quarantine.decide(approver(), requestId, true));
    }

    @PostMapping("/{requestId}/decline")
    public ResponseEntity<QuarantineService.ReleaseRequestView> decline(@PathVariable String requestId) {
        return noStore(quarantine.decide(approver(), requestId, false));
    }

    private QuarantineService.Actor approver() {
        SdvPrincipal principal = security.requirePrincipal();
        try {
            authorization.require(principal, Capability.APPROVE_SENSITIVE_ACCESS);
        } catch (AccessDeniedException ex) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.APR_005, ApprovalsController.REFUSED);
        }
        return new QuarantineService.Actor(principal.tenantId(), principal.subject(), principal.displayName(),
                authorization.clearance(principal), MDC.get("correlationId"));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate()).body(body);
    }
}
