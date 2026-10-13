package com.sdv.platform.application.port;

import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditQueryPort {
    List<AuditEvent> find(UUID tenantId, Filter filter, int limitPlusOne);

    record Filter(
            String action,
            AuditOutcome outcome,
            String actorType,
            String actorId,
            String targetType,
            String targetId,
            String correlationId,
            Instant from,
            Instant to,
            Instant beforeOccurredAt,
            UUID beforeEventId) { }
}
