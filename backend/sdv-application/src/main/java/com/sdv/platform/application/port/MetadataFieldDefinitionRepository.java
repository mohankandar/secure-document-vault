package com.sdv.platform.application.port;

import com.sdv.platform.domain.metadata.MetadataFieldDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetadataFieldDefinitionRepository {
    List<MetadataFieldDefinition> findCurrent(UUID tenantId, UUID sourceId);
    List<MetadataFieldDefinition> findHistory(UUID tenantId, UUID sourceId, String fieldKey);
    Optional<MetadataFieldDefinition> findCurrent(UUID tenantId, UUID sourceId, String fieldKey);
    void retire(UUID tenantId, UUID sourceId, String fieldKey, Instant retiredAt);
    void insert(MetadataFieldDefinition definition);
    default List<String> findDisplayOrder(UUID tenantId, UUID sourceId) { return List.of(); }
    default void replaceDisplayOrder(UUID tenantId, UUID sourceId, List<String> fieldKeys) { throw new UnsupportedOperationException("Field order persistence unavailable"); }
}
