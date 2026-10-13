package com.sdv.platform.web.admin;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/role-capabilities")
public class RoleCapabilityAdminController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final CapabilityPolicyService capabilities;
    private final AuditEventPort audit;

    public RoleCapabilityAdminController(SecurityContextFacade security,
                                         AuthorizationService authorization,
                                         CapabilityPolicyService capabilities,
                                         AuditEventPort audit) {
        this.security = security;
        this.authorization = authorization;
        this.capabilities = capabilities;
        this.audit = audit;
    }

    @GetMapping
    public RoleCapabilityView get() {
        SdvPrincipal principal = requireAdmin();
        return view(principal.tenantId());
    }

    @PutMapping
    public RoleCapabilityView update(@RequestBody UpdateRequest request) {
        SdvPrincipal principal = requireAdmin();
        if (request == null || request.roles() == null) throw new IllegalArgumentException("roles is required.");
        for (Map.Entry<String, Map<String, Boolean>> entry : request.roles().entrySet()) {
            SdvRole role = parseConfigurableRole(entry.getKey());
            capabilities.replace(principal.tenantId(), role, entry.getValue() == null ? Map.of() : entry.getValue(), principal.subject());
        }
        audit.append(new AuditEvent(UUID.randomUUID(), principal.tenantId(), "USER", principal.subject(),
                "ROLE_CAPABILITIES_UPDATED", "TENANT", principal.tenantId().toString(), Instant.now(),
                MDC.get("correlationId"), AuditOutcome.SUCCESS, "{\"rolesUpdated\":" + request.roles().size() + "}"));
        return view(principal.tenantId());
    }

    private RoleCapabilityView view(UUID tenantId) {
        LinkedHashMap<String, Map<String, Boolean>> roles = new LinkedHashMap<>();
        roles.put(SdvRole.BUSINESS_USER.name(), capabilities.configurableStates(tenantId, SdvRole.BUSINESS_USER));
        roles.put(SdvRole.DOCUMENT_STEWARD.name(), capabilities.configurableStates(tenantId, SdvRole.DOCUMENT_STEWARD));
        return new RoleCapabilityView(roles, List.of(
                Capability.DOWNLOAD_CURRENT_PDF.name(),
                Capability.PRINT_DOCUMENT.name(),
                Capability.ADD_DOCUMENT_TAG.name(),
                Capability.ADD_DOCUMENT_COMMENT.name(),
                Capability.PERMANENT_REDACTION.name()));
    }

    private SdvPrincipal requireAdmin() {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.CONFIGURE_ROLE_PERMISSIONS);
        return principal;
    }

    private static SdvRole parseConfigurableRole(String value) {
        SdvRole role;
        try { role = SdvRole.valueOf(value); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Unknown role: " + value); }
        if (role != SdvRole.BUSINESS_USER && role != SdvRole.DOCUMENT_STEWARD) {
            throw new IllegalArgumentException("Only BUSINESS_USER and DOCUMENT_STEWARD are configurable here.");
        }
        return role;
    }

    public record UpdateRequest(Map<String, Map<String, Boolean>> roles) { }
    public record RoleCapabilityView(Map<String, Map<String, Boolean>> roles, List<String> configurableCapabilities) { }
}
