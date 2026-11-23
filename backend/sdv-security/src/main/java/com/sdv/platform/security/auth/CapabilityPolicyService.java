package com.sdv.platform.security.auth;

import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.RoleCapabilities;
import com.sdv.platform.security.SdvAccessRules;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.separation.ForbiddenGrant;
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
    /** Task 0025: Restricted Steward is not configurable; its permissions are fixed. */
    private static final Set<SdvRole> CONFIGURABLE_ROLES = EnumSet.of(SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD);

    private static final Map<String, String> FORBIDDEN_REASONS = Map.of(
            "ADMINS_NEVER_SEE_SENSITIVE_DOCUMENTS",
            "Business Admin, IT Admin and Platform Operations can never see or ask for Confidential or Highly Confidential documents.",
            "STEWARDS_NEVER_APPROVE_SENSITIVE_ACCESS",
            "Document Stewards and Restricted Stewards can never approve requests to print or download sensitive documents; only a Business Admin does.");

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
        // Task 0025, rule 2: whatever is stored, a forbidden grant is never in effect.
        result.removeIf(capability -> SdvAccessRules.forbiddenGrant(role, capability) != null);
        return Set.copyOf(result);
    }

    public Map<String, Boolean> configurableStates(UUID tenantId, SdvRole role) {
        if (!CONFIGURABLE_ROLES.contains(role)) throw new IllegalArgumentException("Only business content roles are configurable.");
        Map<String, Boolean> result = new LinkedHashMap<>();
        Set<Capability> effective = effectiveCapabilities(tenantId, role);
        for (Capability capability : CONFIGURABLE_DOCUMENT_CAPABILITIES) result.put(capability.name(), effective.contains(capability));
        return result;
    }

    /**
     * Task 0025: refuses an override before anything is stored. In this order: a grant the separation rules forbid
     * (SDV-ROL-001), a role whose permissions are fixed (SDV-ROL-002), a capability that can't be changed here
     * (SDV-ROL-003).
     */
    public void validate(SdvRole role, Map<String, Boolean> states) {
        Map<String, Boolean> requested = states == null ? Map.of() : states;
        for (Map.Entry<String, Boolean> entry : requested.entrySet()) {
            Capability capability = capabilityOrNull(entry.getKey());
            if (capability == null || !Boolean.TRUE.equals(entry.getValue())) continue;
            ForbiddenGrant<SdvRole, Capability> forbidden = SdvAccessRules.forbiddenGrant(role, capability);
            if (forbidden != null) {
                throw new CodedRefusal.BadRequest(SdvErrorCode.ROL_001, role.name() + " can never be given "
                        + capability.name() + ". " + FORBIDDEN_REASONS.getOrDefault(forbidden.id(), "") + " Nothing was changed.");
            }
        }
        if (!CONFIGURABLE_ROLES.contains(role)) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.ROL_002, "The permissions of " + role.name()
                    + " are fixed; only BUSINESS_USER and DOCUMENT_STEWARD can be changed here. Nothing was changed.");
        }
        for (String key : requested.keySet()) {
            Capability capability = capabilityOrNull(key);
            if (capability == null || !CONFIGURABLE_DOCUMENT_CAPABILITIES.contains(capability)) {
                throw new CodedRefusal.BadRequest(SdvErrorCode.ROL_003, String.valueOf(key)
                        + " can't be changed in Roles & permissions. Nothing was changed.");
            }
        }
    }

    public void replace(UUID tenantId, SdvRole role, Map<String, Boolean> states, String actorId) {
        validate(role, states);
        Map<String, Boolean> safe = new LinkedHashMap<>();
        for (Capability capability : CONFIGURABLE_DOCUMENT_CAPABILITIES) {
            safe.put(capability.name(), Boolean.TRUE.equals(states.get(capability.name())));
        }
        policies.replaceOverrides(tenantId, role.name(), safe, actorId);
    }

    private static Capability capabilityOrNull(String name) {
        if (name == null) return null;
        try {
            return Capability.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
