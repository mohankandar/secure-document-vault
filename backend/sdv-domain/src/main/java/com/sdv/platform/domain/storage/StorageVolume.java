package com.sdv.platform.domain.storage;

import java.time.Instant;
import java.util.UUID;

public record StorageVolume(
        UUID volumeId,
        UUID tenantId,
        String providerId,
        String displayName,
        String rootPath,
        StorageVolumeState state,
        int priority,
        int warningPercent,
        int stopWritePercent,
        Instant createdAt,
        Instant updatedAt) {
}
