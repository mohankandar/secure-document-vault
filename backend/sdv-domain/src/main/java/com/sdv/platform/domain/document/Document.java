package com.sdv.platform.domain.document;

import java.time.Instant;
import java.util.UUID;

public record Document(
        UUID documentId,
        UUID tenantId,
        UUID submissionId,
        UUID sourceId,
        String sourceDocumentId,
        String producerIdempotencyKey,
        String originalFilename,
        String mediaType,
        String contentSha256,
        Long contentSizeBytes,
        DocumentStatus status,
        Integer retentionDaysSnapshot,
        Instant ingestedAt,
        Instant expiresAt,
        Instant availableAt,
        int processingAttemptCount,
        String processingErrorCode,
        Instant deletedAt,
        /* Task 0024: the document's own level, set by the sending system (STANDARD when it sent none). Never null. */
        SensitivityLevel sensitivityLevel) {
    public Document {
        if (sensitivityLevel == null) throw new IllegalArgumentException("A document always has a sensitivity level.");
    }
}
