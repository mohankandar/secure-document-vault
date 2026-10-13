package com.sdv.platform.application.storage;

import com.sdv.platform.domain.storage.StorageVolumeState;

public record UpdateStorageVolumeCommand(
        String displayName,
        StorageVolumeState state,
        int priority,
        int warningPercent,
        int stopWritePercent) {
}
