package com.sdv.platform.application.storage;

import com.sdv.platform.domain.storage.StorageCapacitySnapshot;
import com.sdv.platform.domain.storage.StorageVolume;

public record StorageVolumeView(
        StorageVolume volume,
        StorageCapacitySnapshot capacity,
        boolean warningThresholdReached,
        boolean stopWriteThresholdReached,
        String observationError) {
}
