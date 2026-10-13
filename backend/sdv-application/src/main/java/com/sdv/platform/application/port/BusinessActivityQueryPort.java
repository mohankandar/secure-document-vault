package com.sdv.platform.application.port;

import com.sdv.platform.domain.audit.AuditEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BusinessActivityQueryPort {
    List<AuditEvent> findDocumentActivity(UUID tenantId, UUID documentId, Instant from, Instant to, String action, String actorId, boolean includeViewEvents, int limit);
}
