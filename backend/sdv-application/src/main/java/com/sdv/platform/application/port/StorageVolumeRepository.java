package com.sdv.platform.application.port;

import com.sdv.platform.domain.storage.StorageVolume;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StorageVolumeRepository {
    List<StorageVolume> findAll(UUID tenantId);
    Optional<StorageVolume> findById(UUID tenantId, UUID volumeId);
    void lockConfiguration();
    List<ConfiguredRoot> findConfiguredRoots(String providerId);
    void insert(StorageVolume volume);
    void update(StorageVolume volume);

    record ConfiguredRoot(UUID volumeId, String rootPath) { }
}
