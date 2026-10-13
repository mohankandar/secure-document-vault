package com.sdv.platform.domain.source;

import java.time.Instant;
import java.util.UUID;

public record IngestionSource(
        UUID sourceId,
        UUID tenantId,
        String sourceKey,
        String displayName,
        IngestionSourceStatus status,
        IngestionSourceAuthType authType,
        String credentialReference,
        SourceLimits limits,
        Instant createdAt,
        Instant updatedAt) {
}
