package com.sdv.platform.web.security;

import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/session")
public class SessionCapabilitiesController {
    private final SecurityContextFacade security;
    private final CapabilityPolicyService capabilities;
    private final TenantRepository tenants;

    public SessionCapabilitiesController(SecurityContextFacade security, CapabilityPolicyService capabilities, TenantRepository tenants) {
        this.security = security;
        this.capabilities = capabilities;
        this.tenants = tenants;
    }

    @GetMapping
    public SessionResponse currentSession() {
        SdvPrincipal principal = security.requirePrincipal();
        List<String> roles = principal.roles().stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(SdvRole::name)
                .toList();
        List<String> effectiveCapabilities = capabilities.effectiveCapabilities(principal.tenantId(), principal.roles()).stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(Capability::name)
                .toList();
        String tenantDisplayName = tenants.findById(principal.tenantId())
                .map(tenant -> tenant.displayName())
                .filter(name -> !name.isBlank())
                .orElse("Unavailable");
        return new SessionResponse(principal.tenantId(), tenantDisplayName, principal.subject(), roles, effectiveCapabilities);
    }

    public record SessionResponse(UUID tenantId, String tenantDisplayName, String subject, List<String> roles, List<String> capabilities) {
        public SessionResponse {
            roles = List.copyOf(roles);
            capabilities = List.copyOf(capabilities);
        }
    }
}
