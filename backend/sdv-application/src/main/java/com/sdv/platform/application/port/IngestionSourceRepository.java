package com.sdv.platform.application.port;

import com.sdv.platform.domain.source.IngestionSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IngestionSourceRepository {
    List<IngestionSource> findAll(UUID tenantId);
    Optional<IngestionSource> findById(UUID tenantId, UUID sourceId);
    Optional<IngestionSource> findByKey(UUID tenantId, String sourceKey);
    void insert(IngestionSource source);
    void update(IngestionSource source);
}
