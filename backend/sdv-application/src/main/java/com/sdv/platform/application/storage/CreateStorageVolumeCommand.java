package com.sdv.platform.application.storage;

import com.sdv.platform.domain.storage.StorageVolumeState;

public record CreateStorageVolumeCommand(
        String displayName,
        String rootPath,
        StorageVolumeState state,
        int priority,
        Integer warningPercent,
        Integer stopWritePercent) {
}
