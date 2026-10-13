package com.sdv.platform.application.source;

import com.sdv.platform.domain.source.IngestionSourceAuthType;
import com.sdv.platform.domain.source.IngestionSourceStatus;
import com.sdv.platform.domain.source.SourceLimits;

public record UpdateIngestionSourceCommand(
        String displayName,
        IngestionSourceStatus status,
        IngestionSourceAuthType authType,
        String credentialReference,
        SourceLimits limits) {
}
