package com.sdv.platform.domain.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * actorDisplayName (task 0011, migration 020): the person's or sending system's name at the time of the event, from
 * the signed-in session. Null for events written before migration 020 and for background work; screens then show
 * actorId. Existing rows are never changed.
 */
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
        String safeDetailsJson,
        String actorDisplayName) {

    public AuditEvent(UUID auditEventId, UUID tenantId, String actorType, String actorId, String action,
                      String targetType, String targetId, Instant occurredAt, String correlationId,
                      AuditOutcome outcome, String safeDetailsJson) {
        this(auditEventId, tenantId, actorType, actorId, action, targetType, targetId, occurredAt, correlationId,
                outcome, safeDetailsJson, null);
    }

    public AuditEvent withActorDisplayName(String name) {
        return new AuditEvent(auditEventId, tenantId, actorType, actorId, action, targetType, targetId, occurredAt,
                correlationId, outcome, safeDetailsJson, name);
    }
}
