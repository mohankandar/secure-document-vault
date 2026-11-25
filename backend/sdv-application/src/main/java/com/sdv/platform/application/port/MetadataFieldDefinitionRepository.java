package com.sdv.platform.application.port;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetadataFieldDefinitionRepository {
    List<MetadataFieldDefinition> findCurrent(UUID tenantId, UUID sourceId);
    List<MetadataFieldDefinition> findHistory(UUID tenantId, UUID sourceId, String fieldKey);
    Optional<MetadataFieldDefinition> findCurrent(UUID tenantId, UUID sourceId, String fieldKey);
    void insert(MetadataFieldDefinition definition);
    /** Task 0078: a search text as the field's values are stored; empty when it isn't such a value. */
    default List<String> storedForms(UUID tenantId, String fieldKey, String text) { return List.of(); }
    default List<String> findDisplayOrder(UUID tenantId, UUID sourceId) { return List.of(); }
    default void replaceDisplayOrder(UUID tenantId, UUID sourceId, List<String> fieldKeys) { throw new InternalCheckFailed("Field order persistence unavailable"); }
    /** The one or two fields that name a record, in title order; empty means the default title. */
    default List<String> findRecordTitleFields(UUID tenantId, UUID sourceId) { return List.of(); }
    default void replaceRecordTitleFields(UUID tenantId, UUID sourceId, List<String> fieldKeys) { throw new InternalCheckFailed("Record title persistence unavailable"); }
}
