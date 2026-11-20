package com.sdv.platform.application.port;

import com.sdv.platform.domain.document.SensitivityLevel;

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

    /*
     * Task 0027 (0.4, separation rule 4): Problems never show a file name, at any level, so none is read. A document
     * is named by its record's primary identifier (problemRecords), its document ID, its effective level
     * (sensitivityLevel, set by OperationsService; null when it couldn't be worked out), its received date and its
     * page count (null until the document is stored).
     */

    record ProcessingFailure(
            UUID documentId,
            UUID submissionId,
            UUID sourceId,
            String errorCode,
            int processingAttemptCount,
            Instant ingestedAt,
            Integer pageCount,
            SensitivityLevel sensitivityLevel) {
        public ProcessingFailure withLevel(SensitivityLevel level) {
            return new ProcessingFailure(documentId, submissionId, sourceId, errorCode, processingAttemptCount,
                    ingestedAt, pageCount, level);
        }
    }

    record StuckDocument(
            UUID documentId,
            UUID submissionId,
            UUID sourceId,
            String status,
            Instant stateSince,
            String processingErrorCode,
            Instant ingestedAt,
            Integer pageCount,
            SensitivityLevel sensitivityLevel) {
        public StuckDocument withLevel(SensitivityLevel level) {
            return new StuckDocument(documentId, submissionId, sourceId, status, stateSince, processingErrorCode,
                    ingestedAt, pageCount, level);
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
