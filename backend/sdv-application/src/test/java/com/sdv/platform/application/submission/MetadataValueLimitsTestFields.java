package com.sdv.platform.application.submission;

import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;

import java.time.Instant;
import java.util.UUID;

final class MetadataValueLimitsTestFields {
    private MetadataValueLimitsTestFields() { }

    static MetadataFieldDefinition text(String rules) {
        return new MetadataFieldDefinition(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "field", 1, "Field", MetadataFieldType.TEXT,
                false, false, false, MetadataNormalization.NONE, MetadataSearchMode.values()[0], rules, true, Instant.EPOCH, null, Instant.EPOCH);
    }
}
