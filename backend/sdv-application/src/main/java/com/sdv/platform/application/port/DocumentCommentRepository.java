package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DocumentCommentRepository {
    List<DocumentComment> findByDocument(UUID tenantId, UUID documentId);
    DocumentComment add(UUID tenantId, UUID documentId, String commentText, String createdBy, Instant createdAt);

    record DocumentComment(UUID commentId, UUID documentId, String commentText, String createdBy, Instant createdAt) { }
}
