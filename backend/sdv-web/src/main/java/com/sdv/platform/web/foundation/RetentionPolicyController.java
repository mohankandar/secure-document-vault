package com.sdv.platform.web.foundation;

import com.sdv.platform.application.retention.RetentionPolicyService;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/retention-policy")
public class RetentionPolicyController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final RetentionPolicyService retention;

    public RetentionPolicyController(
            SecurityContextFacade security,
            AuthorizationService authorization,
            RetentionPolicyService retention) {
        this.security = security;
        this.authorization = authorization;
        this.retention = retention;
    }

    @GetMapping
    public ResponseEntity<RetentionPolicyService.RetentionPolicyOverview> current() {
        SdvPrincipal principal = requireRetentionAdmin();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(retention.current(principal.tenantId()));
    }

    @PutMapping
    public ResponseEntity<RetentionPolicyService.RetentionPolicyOverview> update(
            @RequestBody RetentionPolicyRequest request) {
        SdvPrincipal principal = requireRetentionAdmin();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(retention.update(
                        principal.tenantId(), request.policyName(), request.description(), request.defaultRetentionDays(),
                        principal.subject(), MDC.get("correlationId")));
    }

    private SdvPrincipal requireRetentionAdmin() {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.CONFIGURE_RETENTION);
        return principal;
    }

    public record RetentionPolicyRequest(String policyName, String description, Integer defaultRetentionDays) { }
}
