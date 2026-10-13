package com.sdv.platform.application.metadata;

import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;

public record BusinessMetadataBehaviorCommand(
        String displayLabel,
        boolean businessIdentifier,
        boolean searchable,
        MetadataNormalization normalization,
        MetadataSearchMode searchMode) {
}
