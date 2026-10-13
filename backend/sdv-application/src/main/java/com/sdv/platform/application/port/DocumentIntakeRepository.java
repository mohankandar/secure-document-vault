package com.sdv.platform.application.port;

import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.DocumentStorageRef;
import com.sdv.platform.domain.submission.Submission;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentIntakeRepository {
    Submission lockSubmission(UUID tenantId, UUID sourceId, UUID submissionId);

    Optional<Document> findDocumentById(UUID tenantId, UUID sourceId, UUID submissionId, UUID documentId);
    Optional<Document> findDocumentByIdForTenant(UUID tenantId, UUID documentId);
    Optional<Document> findBySourceDocumentId(UUID tenantId, UUID sourceId, UUID submissionId, String sourceDocumentId);
    Optional<Document> findByProducerIdempotencyKey(UUID tenantId, UUID sourceId, UUID submissionId, String producerIdempotencyKey);
    List<Document> findDocuments(UUID tenantId, UUID sourceId, UUID submissionId);

    long countDocuments(UUID tenantId, UUID submissionId);
    long sumContentBytes(UUID tenantId, UUID submissionId);
    boolean hasExactDuplicateContent(UUID tenantId, UUID sourceId, String contentSha256, UUID excludingDocumentId);

    void insertDocument(Document document);
    void insertStorageRef(DocumentStorageRef storageRef);
    void insertCryptoMetadata(DocumentCryptoMetadata cryptoMetadata);

    Optional<DocumentStorageRef> findStorageRef(UUID tenantId, UUID documentId);
    Optional<DocumentCryptoMetadata> findCryptoMetadata(UUID tenantId, UUID documentId);

    void markExpectedDocumentReceived(UUID tenantId,
                                      UUID submissionId,
                                      String sourceDocumentId,
                                      UUID documentId,
                                      Instant receivedAt);

    void updateSubmissionAfterReceipt(UUID tenantId,
                                      UUID sourceId,
                                      UUID submissionId,
                                      int expectedDocumentCount,
                                      Instant activityAt);

    boolean transitionDocumentToProcessing(UUID tenantId,
                                           UUID sourceId,
                                           UUID submissionId,
                                           UUID documentId,
                                           Instant processingAt);

    void markDocumentAvailable(UUID tenantId,
                               UUID sourceId,
                               UUID submissionId,
                               UUID documentId,
                               Instant availableAt);

    void markDocumentProcessingFailed(UUID tenantId,
                                      UUID sourceId,
                                      UUID submissionId,
                                      UUID documentId,
                                      String errorCode);

    void markSubmissionIncompleteIfDue(UUID tenantId,
                                       UUID sourceId,
                                       UUID submissionId,
                                       Instant cutoff);
}
