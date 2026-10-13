package com.sdv.platform.application.source;

import com.sdv.platform.domain.source.IngestionSourceAuthType;
import com.sdv.platform.domain.source.SourceLimits;

public record CreateIngestionSourceCommand(
        String sourceKey,
        String displayName,
        IngestionSourceAuthType authType,
        String credentialReference,
        SourceLimits limits) {
}
