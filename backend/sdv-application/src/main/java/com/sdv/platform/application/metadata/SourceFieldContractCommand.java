package com.sdv.platform.application.metadata;

import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;

public record SourceFieldContractCommand(
        String displayLabel,
        MetadataFieldType fieldType,
        boolean required,
        MetadataNormalization normalization,
        String validationRulesJson) {
}
