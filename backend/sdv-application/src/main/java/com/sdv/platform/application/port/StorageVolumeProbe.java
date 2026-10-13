package com.sdv.platform.application.port;

import com.sdv.platform.domain.storage.StorageCapacitySnapshot;

public interface StorageVolumeProbe {
    String normalize(String rootPath);
    PreparedRoot prepare(String rootPath);
    StorageCapacitySnapshot inspect(String normalizedRootPath);
    boolean overlaps(String normalizedRootPathA, String normalizedRootPathB);

    record PreparedRoot(String normalizedRootPath, StorageCapacitySnapshot capacity) { }
}
