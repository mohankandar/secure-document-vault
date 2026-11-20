package com.sdv.platform.web.admin;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
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
        // Task 0025: the whole request is checked before anything is stored, so a refusal changes nothing.
        LinkedHashMap<SdvRole, Map<String, Boolean>> checked = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Boolean>> entry : request.roles().entrySet()) {
            SdvRole role = parseRole(entry.getKey());
            Map<String, Boolean> states = entry.getValue() == null ? Map.of() : entry.getValue();
            try {
                capabilities.validate(role, states);
            } catch (RuntimeException refused) {
                if (refused instanceof CodedRefusal coded) auditRefusal(principal, role, coded);
                throw refused;
            }
            checked.put(role, states);
        }
        checked.forEach((role, states) -> capabilities.replace(principal.tenantId(), role, states, principal.subject()));
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

    /** The refused change is audited (DENIED) with its code; the refused values themselves are not stored. */
    private void auditRefusal(SdvPrincipal principal, SdvRole role, CodedRefusal refusal) {
        audit.append(new AuditEvent(UUID.randomUUID(), principal.tenantId(), "USER", principal.subject(),
                "ROLE_CAPABILITIES_UPDATE_REFUSED", "TENANT", principal.tenantId().toString(), Instant.now(),
                MDC.get("correlationId"), AuditOutcome.DENIED,
                "{\"role\":\"" + role.name() + "\",\"code\":\"" + refusal.errorCode().code() + "\"}"));
    }

    /** Which roles can be changed is decided by {@link CapabilityPolicyService#validate} (SDV-ROL-002). */
    private static SdvRole parseRole(String value) {
        try { return SdvRole.valueOf(value); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Unknown role: " + value); }
    }

    public record UpdateRequest(Map<String, Map<String, Boolean>> roles) { }
    public record RoleCapabilityView(Map<String, Map<String, Boolean>> roles, List<String> configurableCapabilities) { }
}
