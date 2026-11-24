package com.sdv.platform.web.business;

import com.sdv.platform.application.redaction.SealedOriginalService;
import com.sdv.platform.application.viewer.DocumentViewerService;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Task 0066: the sealed originals of a document redacted on legal hold; Restricted Stewards only, reading only. */
@RestController
@RequestMapping("/api/v1/business/documents/{documentId}/sealed-originals")
public class SealedOriginalController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final SealedOriginalService sealed;

    public SealedOriginalController(SecurityContextFacade security, AuthorizationService authorization, SealedOriginalService sealed) {
        this.security = security;
        this.authorization = authorization;
        this.sealed = sealed;
    }

    @GetMapping
    public ResponseEntity<List<SealedOriginalService.SealedView>> list(@PathVariable UUID documentId) {
        SdvPrincipal p = requireView();
        return ResponseEntity.ok().headers(SecurePdfViewerController.viewerHeaders())
                .body(sealed.list(p.tenantId(), documentId, authorization.clearance(p), p.subject(), MDC.get("correlationId")));
    }

    @GetMapping("/{sealedId}/viewer")
    public ResponseEntity<DocumentViewerService.ViewerMetadata> open(@PathVariable UUID documentId, @PathVariable UUID sealedId) {
        SdvPrincipal p = requireView();
        return ResponseEntity.ok().headers(SecurePdfViewerController.viewerHeaders())
                .body(sealed.open(p.tenantId(), documentId, sealedId, authorization.clearance(p), p.subject(), MDC.get("correlationId")));
    }

    @GetMapping(value = "/{sealedId}/viewer/pages/{pageNumber}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> page(@PathVariable UUID documentId, @PathVariable UUID sealedId, @PathVariable int pageNumber,
                                       @RequestParam(defaultValue = "1.0") double scale,
                                       @RequestParam(required = false) String viewingId) {
        SdvPrincipal p = requireView();
        DocumentViewerService.PageImage page = sealed.page(p.tenantId(), documentId, sealedId, authorization.clearance(p), pageNumber,
                scale, SecurePdfViewerController.parseViewingId(viewingId), p.subject(), MDC.get("correlationId"));
        HttpHeaders headers = SecurePdfViewerController.viewerHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setContentLength(page.pngBytes().length);
        return ResponseEntity.ok().headers(headers).body(page.pngBytes());
    }

    private SdvPrincipal requireView() {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.VIEW_PDF);
        return principal;
    }
}
