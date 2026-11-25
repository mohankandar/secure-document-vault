package com.sdv.platform.web.fields;

import com.sdv.platform.application.metadata.CaseFieldService;
import com.sdv.platform.application.port.CaseFieldsPort;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Task 0077: the entity's case fields, for the Business Admin. The import's file is the request body as it is ({@code platform-fields/1}). */
@RestController
@RequestMapping("/api/v1/admin/case-fields")
public class CaseFieldController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final CaseFieldService fields;

    public CaseFieldController(SecurityContextFacade security, AuthorizationService authorization, CaseFieldService fields) {
        this.security = security;
        this.authorization = authorization;
        this.fields = fields;
    }

    public record Sensitive(Boolean sensitive) { }

    public record File(String file) { }

    @GetMapping
    public List<CaseFieldsPort.FieldState> list() {
        SdvPrincipal p = security.requirePrincipal();
        // IT's field editor names the locked types too; it changes nothing here.
        if (!authorization.has(p, Capability.CONFIGURE_INGESTION_SOURCES)) authorization.require(p, Capability.CONFIGURE_METADATA_SEARCH);
        return fields.list(p.tenantId());
    }

    @PutMapping("/{fieldKey}/sensitive")
    public CaseFieldsPort.FieldState sensitive(@PathVariable String fieldKey, @RequestBody Sensitive request) {
        SdvPrincipal p = require();
        if (request == null || request.sensitive() == null) throw new IllegalArgumentException("sensitive is required.");
        return fields.setSensitive(p.tenantId(), fieldKey, request.sensitive(), p.subject(), MDC.get("correlationId"));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        SdvPrincipal p = require();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("vero-field-list.json").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(fields.export(p.tenantId(), p.subject(), MDC.get("correlationId")).getBytes(StandardCharsets.UTF_8));
    }

    /** Changes nothing: what the import would do. */
    @PostMapping("/import/preview")
    public List<CaseFieldsPort.ImportLine> preview(@RequestBody File request) {
        return fields.preview(require().tenantId(), request == null ? null : request.file());
    }

    @PostMapping("/import")
    public List<CaseFieldsPort.ImportLine> importFile(@RequestBody File request) {
        SdvPrincipal p = require();
        return fields.importFile(p.tenantId(), request == null ? null : request.file(), p.subject(), MDC.get("correlationId"));
    }

    private SdvPrincipal require() {
        SdvPrincipal p = security.requirePrincipal();
        authorization.require(p, Capability.CONFIGURE_METADATA_SEARCH);
        return p;
    }
}
