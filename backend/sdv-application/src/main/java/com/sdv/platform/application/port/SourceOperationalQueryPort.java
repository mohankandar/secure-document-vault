package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.UUID;

public interface SourceOperationalQueryPort {
    SourceOperationalSummary summarize(UUID tenantId, UUID sourceId);

    record SourceOperationalSummary(
            long submissionCount,
            long documentCount,
            Instant lastActivityAt,
            Instant lastDocumentAvailableAt,
            Instant lastProcessingFailureAt) { }
}
