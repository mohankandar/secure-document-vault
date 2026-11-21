package com.sdv.platform.application.port;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.storage.StorageCapacitySnapshot;

public interface StorageVolumeProbe {
    String normalize(String rootPath);
    PreparedRoot prepare(String rootPath);
    StorageCapacitySnapshot inspect(String normalizedRootPath);
    boolean overlaps(String normalizedRootPathA, String normalizedRootPathB);

    record PreparedRoot(String normalizedRootPath, StorageCapacitySnapshot capacity) { }

    /**
     * Checks, before a volume exists, that Vero can write in a folder: writes and removes a small test file and
     * removes any folder it had to create, so nothing is left behind. Never throws for an unusable folder;
     * the result says so in plain words.
     */
    default FolderCheck check(String rootPath) {
        throw new InternalCheckFailed("Folder check unavailable");
    }

    /** problem: a plain sentence when the folder cannot be used; null when it can. Space is null when unknown. */
    record FolderCheck(String normalizedRootPath, boolean writable, Long usableBytes, Long totalBytes, String problem) { }
}
