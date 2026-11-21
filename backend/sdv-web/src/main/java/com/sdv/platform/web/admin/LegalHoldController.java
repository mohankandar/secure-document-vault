package com.sdv.platform.web.admin;

import com.sdv.platform.application.legalhold.LegalHoldService;
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
 * Task 0027 (R2): legal holds for the Business Admin (MANAGE_LEGAL_HOLD). Anyone else is refused with SDV-HLD-001
 * (and the refusal is audited as AUTHORIZATION_DENIED). No screen uses these yet; the V1 screens will.
 * <ul>
 *   <li>{@code GET /documents?identifier=…}: the documents of the record with this primary identifier;</li>
 *   <li>{@code GET}: holds in place;</li>
 *   <li>{@code POST} with {@code documentId} and {@code reason}: place a hold (201);</li>
 *   <li>{@code POST /{holdId}/release}: release it (the hold stays as history).</li>
 * </ul>
 * References are taken as text, so a malformed one gets its coded 404 like an unknown one.
 */
@RestController
@RequestMapping("/api/v1/admin/legal-holds")
public class LegalHoldController {
    static final String REFUSED = "Only a Business Admin can manage legal holds.";

    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final LegalHoldService holds;

    public LegalHoldController(SecurityContextFacade security, AuthorizationService authorization, LegalHoldService holds) {
        this.security = security;
        this.authorization = authorization;
        this.holds = holds;
    }

    @GetMapping("/documents")
    public ResponseEntity<List<LegalHoldService.HoldDocumentView>> documents(@RequestParam(required = false) String identifier) {
        SdvPrincipal principal = requireHoldManager();
        return noStore(HttpStatus.OK, holds.find(principal.tenantId(), identifier));
    }

    @GetMapping
    public ResponseEntity<List<LegalHoldService.LegalHoldView>> active() {
        SdvPrincipal principal = requireHoldManager();
        return noStore(HttpStatus.OK, holds.active(principal.tenantId()));
    }

    @PostMapping
    public ResponseEntity<LegalHoldService.LegalHoldView> place(@RequestBody(required = false) PlaceRequest request) {
        SdvPrincipal principal = requireHoldManager();
        PlaceRequest safe = request == null ? new PlaceRequest(null, null) : request;
        return noStore(HttpStatus.CREATED, holds.place(principal.tenantId(), principal.subject(), principal.displayName(),
                safe.documentId(), safe.reason(), MDC.get("correlationId")));
    }

    @PostMapping("/{holdId}/release")
    public ResponseEntity<LegalHoldService.LegalHoldView> release(@PathVariable String holdId) {
        SdvPrincipal principal = requireHoldManager();
        return noStore(HttpStatus.OK, holds.release(principal.tenantId(), principal.subject(), principal.displayName(),
                holdId, MDC.get("correlationId")));
    }

    private SdvPrincipal requireHoldManager() {
        SdvPrincipal principal = security.requirePrincipal();
        try {
            authorization.require(principal, Capability.MANAGE_LEGAL_HOLD);
        } catch (AccessDeniedException ex) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.HLD_001, REFUSED);
        }
        return principal;
    }

    private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate()).body(body);
    }

    public record PlaceRequest(String documentId, String reason) { }
}
