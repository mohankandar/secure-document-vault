package com.sdv.platform.web.operations;

import com.documentplatform.monitoring.CheckState;
import com.documentplatform.monitoring.DependencyStatus;
import com.documentplatform.monitoring.spring.DiagnosticBundles;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.security.auth.AuthorizationService;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 0074: what Vero depends on and how each is doing, and the diagnostic bundle for support (a zip without secrets or
 * content). IT Admin and Platform operations only. Every bundle is in the audit before it leaves Vero.
 */
@RestController
@RequestMapping("/api/v1/operations")
public class ChecksController {
    static final String ACTION = "DIAGNOSTIC_BUNDLE_DOWNLOADED";
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final DependencyStatus status;
    private final DiagnosticBundles bundles;
    private final AuditEventPort audit;

    public ChecksController(SecurityContextFacade security, AuthorizationService authorization, DependencyStatus status,
                            DiagnosticBundles bundles, AuditEventPort audit) {
        this.security = security;
        this.authorization = authorization;
        this.status = status;
        this.bundles = bundles;
        this.audit = audit;
    }

    /** cause and fix: plain words for IT; null while the check is working. */
    public record Check(String name, String group, String state, String code, String cause, String fix, Instant checkedAt) { }

    public record Checks(List<Check> checks) { }

    @GetMapping("/checks")
    public ResponseEntity<Checks> checks() {
        requireTechnicalView();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(new Checks(status.results().stream().map(r -> {
            boolean working = r.state() == CheckState.WORKING;
            return new Check(r.name(), r.group(), r.state().name(), r.code().code(), working ? null : r.cause(), working ? null : r.fix(), r.checkedAt());
        }).toList()));
    }

    @GetMapping("/diagnostic-bundle")
    public ResponseEntity<byte[]> bundle() throws IOException {
        SdvPrincipal person = requireTechnicalView();
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        bundles.write(zip);
        Instant now = Instant.now();
        audit.append(new AuditEvent(UUID.randomUUID(), person.tenantId(), "USER", person.subject(), ACTION, "INSTALLATION", "vero",
                now, MDC.get("correlationId"), AuditOutcome.SUCCESS, "{\"bytes\":" + zip.size() + "}", person.displayName()));
        return ResponseEntity.ok().contentType(new MediaType("application", "zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("vero-diagnostics-" + now.toString().substring(0, 10) + ".zip").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(zip.toByteArray());
    }

    private SdvPrincipal requireTechnicalView() {
        SdvPrincipal person = security.requirePrincipal();
        authorization.require(person, Capability.VIEW_TECHNICAL_FAILURES);
        return person;
    }
}
