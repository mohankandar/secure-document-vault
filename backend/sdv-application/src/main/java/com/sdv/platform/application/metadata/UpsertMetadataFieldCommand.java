package com.sdv.platform.application.metadata;

import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;

public record UpsertMetadataFieldCommand(
        String displayLabel,
        MetadataFieldType fieldType,
        boolean required,
        boolean businessIdentifier,
        boolean searchable,
        MetadataNormalization normalization,
        MetadataSearchMode searchMode,
        String validationRulesJson) {
}
