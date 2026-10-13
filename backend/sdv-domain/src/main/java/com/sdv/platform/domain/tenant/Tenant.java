package com.sdv.platform.domain.tenant;

import java.time.Instant;
import java.util.UUID;

public record Tenant(
        UUID tenantId,
        String tenantKey,
        String displayName,
        TenantStatus status,
        TenantGuardrails guardrails,
        Instant createdAt,
        Instant updatedAt) {
}
