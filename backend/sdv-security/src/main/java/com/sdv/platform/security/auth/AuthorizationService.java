package com.sdv.platform.security.auth;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.security.Capability;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthorizationService {
    private final AuditEventPort audit;
    private final CapabilityPolicyService capabilities;

    public AuthorizationService(AuditEventPort audit, CapabilityPolicyService capabilities) {
        this.audit = audit;
        this.capabilities = capabilities;
    }

    public void require(SdvPrincipal principal, Capability capability) {
        if (!capabilities.has(principal, capability)) {
            safeDeniedAudit(principal, capability);
            throw new AccessDeniedException("Required capability is not granted: " + capability.name());
        }
    }

    /** Task 0029: whether the person holds the capability, without auditing a refusal (for "one of two" checks). */
    public boolean has(SdvPrincipal principal, Capability capability) {
        return capabilities.has(principal, capability);
    }

    /**
     * Task 0026: the sensitivity levels this person may open, from their effective capabilities. Nothing else in
     * the request (headers, parameters) can change it.
     */
    public com.sdv.platform.application.access.Clearance clearance(SdvPrincipal principal) {
        // Task 0033: the role names go with it, for the refusal's audit event only (attempts by role in the
        // Sensitivity report); they decide nothing.
        return new com.sdv.platform.application.access.Clearance(
                capabilities.has(principal, Capability.VIEW_CONFIDENTIAL),
                capabilities.has(principal, Capability.VIEW_HIGHLY_CONFIDENTIAL),
                principal.roles().stream().map(Enum::name).toList());
    }

    private void safeDeniedAudit(SdvPrincipal principal, Capability capability) {
        try {
            audit.append(new AuditEvent(
                    UUID.randomUUID(), principal.tenantId(), "USER", principal.subject(),
                    "AUTHORIZATION_DENIED", "CAPABILITY", capability.name(), Instant.now(),
                    MDC.get("correlationId"), AuditOutcome.DENIED,
                    "{\"capability\":\"" + capability.name() + "\"}"));
        } catch (RuntimeException ignored) {
            // Authorization must still fail closed even when the audit store itself is unavailable.
        }
    }
}
