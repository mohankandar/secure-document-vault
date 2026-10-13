package com.sdv.platform.security.auth;

import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.RoleCapabilities;
import com.sdv.platform.security.SdvRole;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CapabilityPolicyService {
    private static final Set<Capability> CONFIGURABLE_DOCUMENT_CAPABILITIES = EnumSet.of(
            Capability.DOWNLOAD_CURRENT_PDF,
            Capability.PRINT_DOCUMENT,
            Capability.ADD_DOCUMENT_TAG,
            Capability.ADD_DOCUMENT_COMMENT,
            Capability.PERMANENT_REDACTION);
    private static final Set<SdvRole> CONFIGURABLE_ROLES = EnumSet.of(SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD);

    private final RoleCapabilityPolicyPort policies;

    public CapabilityPolicyService(RoleCapabilityPolicyPort policies) {
        this.policies = policies;
    }

    public boolean has(SdvPrincipal principal, Capability capability) {
        for (SdvRole role : principal.roles()) {
            if (effectiveCapabilities(principal.tenantId(), role).contains(capability)) return true;
        }
        return false;
    }

    public Set<Capability> effectiveCapabilities(UUID tenantId, Set<SdvRole> roles) {
        EnumSet<Capability> result = EnumSet.noneOf(Capability.class);
        for (SdvRole role : roles) result.addAll(effectiveCapabilities(tenantId, role));
        return Set.copyOf(result);
    }

    public Set<Capability> effectiveCapabilities(UUID tenantId, SdvRole role) {
        EnumSet<Capability> result = EnumSet.noneOf(Capability.class);
        result.addAll(RoleCapabilities.capabilities(role));
        if (CONFIGURABLE_ROLES.contains(role)) {
            Map<String, Boolean> overrides = policies.findOverrides(tenantId, role.name());
            for (Capability capability : CONFIGURABLE_DOCUMENT_CAPABILITIES) {
                Boolean enabled = overrides.get(capability.name());
                if (enabled != null) {
                    if (enabled) result.add(capability); else result.remove(capability);
                }
            }
        }
        return Set.copyOf(result);
    }

    public Map<String, Boolean> configurableStates(UUID tenantId, SdvRole role) {
        if (!CONFIGURABLE_ROLES.contains(role)) throw new IllegalArgumentException("Only business content roles are configurable.");
        Map<String, Boolean> result = new LinkedHashMap<>();
        Set<Capability> effective = effectiveCapabilities(tenantId, role);
        for (Capability capability : CONFIGURABLE_DOCUMENT_CAPABILITIES) result.put(capability.name(), effective.contains(capability));
        return result;
    }

    public void replace(UUID tenantId, SdvRole role, Map<String, Boolean> states, String actorId) {
        if (!CONFIGURABLE_ROLES.contains(role)) throw new IllegalArgumentException("Only business content roles are configurable.");
        Map<String, Boolean> safe = new LinkedHashMap<>();
        for (Capability capability : CONFIGURABLE_DOCUMENT_CAPABILITIES) {
            safe.put(capability.name(), Boolean.TRUE.equals(states.get(capability.name())));
        }
        policies.replaceOverrides(tenantId, role.name(), safe, actorId);
    }
}
