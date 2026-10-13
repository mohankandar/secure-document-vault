package com.sdv.platform.domain.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditEvent(
        UUID auditEventId,
        UUID tenantId,
        String actorType,
        String actorId,
        String action,
        String targetType,
        String targetId,
        Instant occurredAt,
        String correlationId,
        AuditOutcome outcome,
        String safeDetailsJson) {
}
