package com.sdv.platform.domain.document;

import java.util.UUID;

public record BusinessMetadataValue(
        UUID metadataValueId,
        UUID tenantId,
        UUID submissionId,
        UUID sourceId,
        UUID definitionId,
        String fieldKey,
        int definitionVersion,
        String storedValue,
        String normalizedValue) {
}
