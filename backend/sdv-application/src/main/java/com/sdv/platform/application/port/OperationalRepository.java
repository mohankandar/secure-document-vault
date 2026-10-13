package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OperationalRepository {
    boolean databaseAvailable();

    long countProcessingFailures(UUID tenantId);
    List<ProcessingFailure> findProcessingFailures(UUID tenantId, int limit);

    long countStuckDocuments(UUID tenantId, Instant processingCutoff, Instant transitionCutoff);
    List<StuckDocument> findStuckDocuments(UUID tenantId, Instant processingCutoff, Instant transitionCutoff, int limit);

    long countAttentionSubmissions(UUID tenantId, Instant now);
    List<AttentionSubmission> findAttentionSubmissions(UUID tenantId, Instant now, int limit);

    record ProcessingFailure(
            UUID documentId,
            UUID submissionId,
            UUID sourceId,
            String errorCode,
            int processingAttemptCount,
            Instant ingestedAt) { }

    record StuckDocument(
            UUID documentId,
            UUID submissionId,
            UUID sourceId,
            String status,
            Instant stateSince,
            String processingErrorCode) { }

    record AttentionSubmission(
            UUID submissionId,
            UUID sourceId,
            String sourceSubmissionId,
            String status,
            int expectedDocumentCount,
            long receivedDocumentCount,
            Instant lastActivityAt,
            int attentionHours) { }
}
