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
            Instant ingestedAt,
            String originalFilename,
            /* Task 0026: the document's own level, to work out its effective level; not sent to the screen. */
            @com.fasterxml.jackson.annotation.JsonIgnore com.sdv.platform.domain.document.SensitivityLevel sensitivityLevel) {
        public ProcessingFailure(UUID documentId, UUID submissionId, UUID sourceId, String errorCode,
                                 int processingAttemptCount, Instant ingestedAt, String originalFilename) {
            this(documentId, submissionId, sourceId, errorCode, processingAttemptCount, ingestedAt, originalFilename, null);
        }

        public ProcessingFailure withoutFilename() {
            return new ProcessingFailure(documentId, submissionId, sourceId, errorCode, processingAttemptCount,
                    ingestedAt, null, sensitivityLevel);
        }
    }

    record StuckDocument(
            UUID documentId,
            UUID submissionId,
            UUID sourceId,
            String status,
            Instant stateSince,
            String processingErrorCode,
            String originalFilename,
            /* Task 0026: as ProcessingFailure.sensitivityLevel. */
            @com.fasterxml.jackson.annotation.JsonIgnore com.sdv.platform.domain.document.SensitivityLevel sensitivityLevel) {
        public StuckDocument(UUID documentId, UUID submissionId, UUID sourceId, String status, Instant stateSince,
                             String processingErrorCode, String originalFilename) {
            this(documentId, submissionId, sourceId, status, stateSince, processingErrorCode, originalFilename, null);
        }

        public StuckDocument withoutFilename() {
            return new StuckDocument(documentId, submissionId, sourceId, status, stateSince, processingErrorCode,
                    null, sensitivityLevel);
        }
    }

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
