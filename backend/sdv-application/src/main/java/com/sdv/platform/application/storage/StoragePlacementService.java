package com.sdv.platform.application.storage;

import com.sdv.platform.application.port.StorageVolumeProbe;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.domain.storage.StorageCapacitySnapshot;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.UUID;

@Service
public class StoragePlacementService {
    private final StorageVolumeRepository volumes;
    private final StorageVolumeProbe probe;

    public StoragePlacementService(StorageVolumeRepository volumes, StorageVolumeProbe probe) {
        this.volumes = volumes;
        this.probe = probe;
    }

    public PlacementDecision select(UUID tenantId, long requiredBytes) {
        if (requiredBytes < 0) throw new IllegalArgumentException("requiredBytes cannot be negative.");

        return volumes.findAll(tenantId).stream()
                .filter(volume -> volume.state() == StorageVolumeState.ACTIVE)
                .map(volume -> candidate(volume, requiredBytes))
                .filter(Candidate::eligible)
                .sorted(Comparator
                        .comparingInt((Candidate candidate) -> candidate.volume().priority())
                        .thenComparingInt(Candidate::projectedUsedPercent)
                        .thenComparing(candidate -> candidate.volume().volumeId()))
                .findFirst()
                .map(candidate -> new PlacementDecision(
                        candidate.volume(), candidate.capacity(), candidate.projectedUsedPercent(),
                        candidate.projectedUsedPercent() >= candidate.volume().warningPercent()))
                .orElseThrow(() -> new NoWritableStorageException(
                        "No healthy ACTIVE storage volume can safely accept the requested content."));
    }

    private Candidate candidate(StorageVolume volume, long requiredBytes) {
        try {
            StorageCapacitySnapshot capacity = probe.inspect(volume.rootPath());
            if (!capacity.writable() || capacity.totalBytes() <= 0 || capacity.usableBytes() < requiredBytes) {
                return new Candidate(volume, capacity, 100, false);
            }
            long projectedUsable = capacity.usableBytes() - requiredBytes;
            long projectedUsed = capacity.totalBytes() - projectedUsable;
            // Use double for the percentage calculation so very large FileStore byte counts cannot
            // overflow a long during the multiply-by-100 step.
            int projectedUsedPercent = (int) Math.min(100.0d,
                    Math.max(0.0d, Math.floor((projectedUsed * 100.0d) / capacity.totalBytes())));
            boolean eligible = projectedUsedPercent < volume.stopWritePercent();
            return new Candidate(volume, capacity, projectedUsedPercent, eligible);
        } catch (RuntimeException ex) {
            return new Candidate(volume, null, 100, false);
        }
    }

    private record Candidate(
            StorageVolume volume,
            StorageCapacitySnapshot capacity,
            int projectedUsedPercent,
            boolean eligible) { }

    public record PlacementDecision(
            StorageVolume volume,
            StorageCapacitySnapshot capacity,
            int projectedUsedPercent,
            boolean warningThresholdReached) { }
}
