package com.sdv.platform.web.business;

import com.sdv.platform.application.download.ControlledDownloadService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/business/documents/{documentId}")
public class ControlledDownloadController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final ControlledDownloadService downloads;

    public ControlledDownloadController(
            SecurityContextFacade security,
            AuthorizationService authorization,
            ControlledDownloadService downloads) {
        this.security = security;
        this.authorization = authorization;
        this.downloads = downloads;
    }

    @GetMapping(value = "/download", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID documentId) {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.DOWNLOAD_CURRENT_PDF);

        ControlledDownloadService.PreparedDownload prepared = downloads.prepare(
                principal.tenantId(), documentId);
        String filename = safeFilename(prepared.originalFilename());
        String correlationId = MDC.get("correlationId");

        StreamingResponseBody body = output -> downloads.stream(
                prepared, output, principal.subject(), correlationId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentLength(prepared.contentSizeBytes());
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build());
        headers.setCacheControl(CacheControl.noStore().cachePrivate().mustRevalidate());
        headers.setPragma("no-cache");
        headers.setExpires(0);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static String safeFilename(String original) {
        if (original == null || original.isBlank()) return "document.pdf";
        String safe = original.replaceAll("[\\r\\n\\u0000-\\u001f\\u007f]", "_")
                .replace('\\', '_')
                .replace('/', '_')
                .trim();
        if (safe.isBlank()) safe = "document.pdf";
        if (!safe.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) safe += ".pdf";
        return safe.length() <= 240 ? safe : safe.substring(0, 236) + ".pdf";
    }
}
