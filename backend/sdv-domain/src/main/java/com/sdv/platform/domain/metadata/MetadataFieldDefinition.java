package com.sdv.platform.domain.metadata;

import java.time.Instant;
import java.util.UUID;

public record MetadataFieldDefinition(
        UUID definitionId,
        UUID tenantId,
        UUID sourceId,
        String fieldKey,
        int version,
        String displayLabel,
        MetadataFieldType fieldType,
        boolean required,
        boolean businessIdentifier,
        boolean searchable,
        MetadataNormalization normalization,
        MetadataSearchMode searchMode,
        String validationRulesJson,
        boolean active,
        Instant effectiveAt,
        Instant retiredAt,
        Instant createdAt) {
}
