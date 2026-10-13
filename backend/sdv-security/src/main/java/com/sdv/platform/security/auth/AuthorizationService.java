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
