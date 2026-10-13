package com.sdv.platform.web.business;

import com.sdv.platform.application.redaction.CorrectiveRedactionService;
import com.sdv.platform.application.redaction.RedactionRegion;
import com.sdv.platform.application.redaction.RedactionResult;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/business/documents/{documentId}/redactions")
public class CorrectiveRedactionController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final CorrectiveRedactionService redaction;

    public CorrectiveRedactionController(SecurityContextFacade security,
                                         AuthorizationService authorization,
                                         CorrectiveRedactionService redaction) {
        this.security = security;
        this.authorization = authorization;
        this.redaction = redaction;
    }

    @PostMapping
    public ResponseEntity<RedactionResult> redact(@PathVariable UUID documentId,
                                                   @RequestBody RedactionRequest request) {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.PERMANENT_REDACTION);

        List<RedactionRegion> regions = request.regions() == null ? List.of()
                : request.regions().stream()
                    .map(r -> new RedactionRegion(r.pageNumber(), r.x(), r.y(), r.width(), r.height()))
                    .toList();

        RedactionResult result = redaction.redact(
                principal.tenantId(), documentId, request.reason(), request.confirmed(),
                regions, principal.subject(), MDC.get("correlationId"));

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(result);
    }

    public record RedactionRequest(String reason, boolean confirmed, List<RegionRequest> regions) { }
    public record RegionRequest(int pageNumber, double x, double y, double width, double height) { }
}
