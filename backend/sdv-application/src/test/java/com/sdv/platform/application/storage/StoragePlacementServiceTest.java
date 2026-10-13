package com.sdv.platform.application.storage;

import com.sdv.platform.application.port.StorageVolumeProbe;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.domain.storage.StorageCapacitySnapshot;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StoragePlacementServiceTest {
    @Test
    void selectsHighestPriorityHealthyActiveVolumeAndSkipsStopWriteOrReadOnly() {
        UUID tenant = UUID.randomUUID();
        StorageVolume readOnly = volume(tenant, "ro", StorageVolumeState.READ_ONLY, 1, 90, 95);
        StorageVolume nearFull = volume(tenant, "full", StorageVolumeState.ACTIVE, 2, 90, 95);
        StorageVolume healthy = volume(tenant, "healthy", StorageVolumeState.ACTIVE, 3, 90, 95);
        FakeRepository repo = new FakeRepository(List.of(readOnly, nearFull, healthy));
        StorageVolumeProbe probe = new StorageVolumeProbe() {
            @Override public String normalize(String rootPath) { return rootPath; }
            @Override public PreparedRoot prepare(String rootPath) { throw new UnsupportedOperationException(); }
            @Override public boolean overlaps(String a, String b) { return false; }
            @Override public StorageCapacitySnapshot inspect(String rootPath) {
                if (rootPath.equals("full")) return new StorageCapacitySnapshot(1000, 51, 94, true, Instant.now());
                return new StorageCapacitySnapshot(1000, 500, 50, true, Instant.now());
            }
        };

        var decision = new StoragePlacementService(repo, probe).select(tenant, 20);
        assertEquals(healthy.volumeId(), decision.volume().volumeId());
        assertFalse(decision.warningThresholdReached());
    }

    @Test
    void failsClosedWhenNoActiveVolumeCanGuaranteeCapacity() {
        UUID tenant = UUID.randomUUID();
        StorageVolume full = volume(tenant, "full", StorageVolumeState.ACTIVE, 1, 90, 95);
        FakeRepository repo = new FakeRepository(List.of(full));
        StorageVolumeProbe probe = new StorageVolumeProbe() {
            @Override public String normalize(String rootPath) { return rootPath; }
            @Override public PreparedRoot prepare(String rootPath) { throw new UnsupportedOperationException(); }
            @Override public boolean overlaps(String a, String b) { return false; }
            @Override public StorageCapacitySnapshot inspect(String rootPath) {
                return new StorageCapacitySnapshot(1000, 10, 99, true, Instant.now());
            }
        };
        assertThrows(NoWritableStorageException.class, () -> new StoragePlacementService(repo, probe).select(tenant, 20));
    }

    private static StorageVolume volume(UUID tenant, String root, StorageVolumeState state, int priority, int warning, int stop) {
        Instant now = Instant.now();
        return new StorageVolume(UUID.randomUUID(), tenant, "LOCAL_FILESYSTEM", root, root, state,
                priority, warning, stop, now, now);
    }

    private record FakeRepository(List<StorageVolume> values) implements StorageVolumeRepository {
        @Override public List<StorageVolume> findAll(UUID tenantId) { return values.stream().filter(v -> v.tenantId().equals(tenantId)).toList(); }
        @Override public Optional<StorageVolume> findById(UUID tenantId, UUID volumeId) { return Optional.empty(); }
        @Override public void lockConfiguration() { }
        @Override public List<ConfiguredRoot> findConfiguredRoots(String providerId) { return List.of(); }
        @Override public void insert(StorageVolume volume) { throw new UnsupportedOperationException(); }
        @Override public void update(StorageVolume volume) { throw new UnsupportedOperationException(); }
    }
}
