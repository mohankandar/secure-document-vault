package com.sdv.platform.domain.storage;

import java.time.Instant;

public record StorageCapacitySnapshot(
        long totalBytes,
        long usableBytes,
        int usedPercent,
        boolean writable,
        Instant observedAt) {
}
