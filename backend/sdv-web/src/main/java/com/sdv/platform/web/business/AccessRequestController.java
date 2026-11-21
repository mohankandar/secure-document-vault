package com.sdv.platform.web.business;

import com.sdv.platform.application.approvals.AccessRequestService;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Task 0028 (S6):
 * <ul>
 *   <li>{@code POST /documents/{documentId}/access-requests} with {@code {kind, reason}} (kind PRINT or DOWNLOAD): a
 *       Restricted Steward (REQUEST_SENSITIVE_ACCESS) asks to print or download one Highly Confidential document
 *       once (201). Anyone else is refused with SDV-APR-001.</li>
 *   <li>{@code GET /access-requests}: a Steward's own requests and what became of them, expired ones included.
 *       Anyone without CHANGE_SENSITIVITY_LEVEL is refused with SDV-APR-015.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/business")
public class AccessRequestController {
    static final String REFUSED = "Only a Restricted Steward can ask to print or download a Highly Confidential document.";
    static final String MINE_REFUSED = "Only a Steward has requests to look at.";

    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final AccessRequestService requests;

    public AccessRequestController(SecurityContextFacade security, AuthorizationService authorization,
                                   AccessRequestService requests) {
        this.security = security;
        this.authorization = authorization;
        this.requests = requests;
    }

    @PostMapping("/documents/{documentId}/access-requests")
    public ResponseEntity<AccessRequestService.RequestView> request(@PathVariable String documentId,
                                                                   @RequestBody(required = false) NewRequest body) {
        SdvPrincipal p = require(Capability.REQUEST_SENSITIVE_ACCESS, SdvErrorCode.APR_001, REFUSED);
        NewRequest r = body == null ? new NewRequest(null, null) : body;
        return SensitivityLevelController.noStore(HttpStatus.CREATED, requests.requestPrintOrDownload(p.tenantId(),
                p.subject(), p.displayName(), authorization.clearance(p), documentId, r.kind(), r.reason(),
                MDC.get("correlationId")));
    }

    @GetMapping("/access-requests")
    public ResponseEntity<List<AccessRequestService.RequestView>> mine() {
        SdvPrincipal p = require(Capability.CHANGE_SENSITIVITY_LEVEL, SdvErrorCode.APR_015, MINE_REFUSED);
        return SensitivityLevelController.noStore(HttpStatus.OK,
                requests.mine(p.tenantId(), p.subject(), MDC.get("correlationId")));
    }

    private SdvPrincipal require(Capability capability, SdvErrorCode code, String message) {
        SdvPrincipal principal = security.requirePrincipal();
        try {
            authorization.require(principal, capability);
        } catch (AccessDeniedException ex) {
            throw new CodedRefusal.Forbidden(code, message);
        }
        return principal;
    }

    public record NewRequest(String kind, String reason) { }
}
