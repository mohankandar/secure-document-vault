package com.sdv.platform.web.business;

import com.sdv.platform.application.viewer.DocumentViewerService;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/business/documents/{documentId}/viewer")
public class SecurePdfViewerController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final DocumentViewerService viewer;

    public SecurePdfViewerController(
            SecurityContextFacade security,
            AuthorizationService authorization,
            DocumentViewerService viewer) {
        this.security = security;
        this.authorization = authorization;
        this.viewer = viewer;
    }

    @GetMapping
    public ResponseEntity<DocumentViewerService.ViewerMetadata> metadata(
            @PathVariable UUID documentId) {
        SdvPrincipal principal = requireView();
        return ResponseEntity.ok()
                .headers(viewerHeaders())
                .body(viewer.metadata(
                        principal.tenantId(),
                        documentId,
                        authorization.clearance(principal),
                        principal.subject(),
                        correlationId()));
    }

    @GetMapping(value = "/pages/{pageNumber}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> page(
            @PathVariable UUID documentId,
            @PathVariable int pageNumber,
            @RequestParam(defaultValue = "1.0") double scale,
            @RequestParam(required = false) String viewingId) {
        SdvPrincipal principal = requireView();
        DocumentViewerService.PageImage page = viewer.renderPage(
                principal.tenantId(),
                documentId,
                authorization.clearance(principal),
                pageNumber,
                scale,
                parseViewingId(viewingId),
                principal.subject(),
                correlationId());

        HttpHeaders headers = viewerHeaders();
        headers.setContentType(MediaType.IMAGE_PNG);
        headers.setContentLength(page.pngBytes().length);
        headers.setContentDisposition(ContentDisposition.inline()
                .filename("sdv-viewer-page-" + pageNumber + ".png")
                .build());
        headers.set("X-SDV-Viewer-Page", Integer.toString(page.pageNumber()));
        headers.set("X-SDV-Viewer-Page-Count", Integer.toString(page.pageCount()));
        return ResponseEntity.ok().headers(headers).body(page.pngBytes());
    }

    /** Small page previews (JSON, a PNG data URL per page), of a viewing; audited once per viewing. */
    @GetMapping("/previews")
    public ResponseEntity<DocumentViewerService.PagePreviews> previews(
            @PathVariable UUID documentId,
            @RequestParam(defaultValue = "1") int first,
            @RequestParam(defaultValue = "12") int count,
            @RequestParam(required = false) String viewingId) {
        SdvPrincipal principal = requireView();
        return ResponseEntity.ok()
                .headers(viewerHeaders())
                .body(viewer.previews(principal.tenantId(), documentId, first, count, parseViewingId(viewingId),
                        authorization.clearance(principal), principal.subject(), correlationId()));
    }

    @GetMapping("/search")
    public ResponseEntity<DocumentViewerService.SearchResults> search(
            @PathVariable UUID documentId,
            @RequestParam("q") String query,
            @RequestParam(required = false) String viewingId) {
        SdvPrincipal principal = requireView();
        return ResponseEntity.ok()
                .headers(viewerHeaders())
                .body(viewer.search(
                        principal.tenantId(),
                        documentId,
                        query,
                        parseViewingId(viewingId),
                        authorization.clearance(principal),
                        principal.subject(),
                        correlationId()));
    }

    /** A missing or malformed viewing ID is none: the request is refused (SDV-DOC-011). */
    private static UUID parseViewingId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private SdvPrincipal requireView() {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.VIEW_PDF);
        return principal;
    }

    private static HttpHeaders viewerHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate().mustRevalidate());
        headers.setPragma("no-cache");
        headers.setExpires(0);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        return headers;
    }

    private static String correlationId() {
        return MDC.get("correlationId");
    }
}
