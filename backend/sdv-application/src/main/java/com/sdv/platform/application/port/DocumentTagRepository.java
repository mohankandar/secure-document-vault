package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DocumentTagRepository {
    List<DocumentTag> findByDocument(UUID tenantId, UUID documentId);
    DocumentTag add(UUID tenantId, UUID documentId, String tagValue, String createdBy, Instant createdAt);

    record DocumentTag(UUID tagId, UUID documentId, String tagValue, String createdBy, Instant createdAt) { }
}
