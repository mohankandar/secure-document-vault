package com.sdv.platform.application.port;

import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.DocumentStorageRef;
import com.sdv.platform.domain.submission.Submission;

import com.sdv.platform.domain.document.SensitivityLevel;

import java.time.Instant;
import java.util.Collection;
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

    // ------------------------------------------------------------------ task 0031: exact duplicates

    /**
     * What a submission has received: its documents plus the exact duplicates it delivered (each acknowledged with the
     * document Vero already had, and stored no second time). "2 of 4 received" counts both.
     */
    long countReceived(UUID tenantId, UUID submissionId);

    /**
     * The document an arriving file duplicates: one of this sending system's documents, in one of these submissions
     * (the record's), with this SHA-256 checksum, not deleted or being deleted. The oldest when there are several.
     */
    Optional<Document> findDuplicateOf(UUID tenantId, UUID sourceId, Collection<UUID> submissionIds, String contentSha256);

    /**
     * Task 0032 (0): the corrected document an arriving file is an uncorrected copy of. One of this sending system's
     * documents, in one of these submissions (the record's), not deleted or being deleted, that had this SHA-256
     * checksum before a permanent corrective redaction. That checksum is kept in the redaction's audit event
     * (DOCUMENT_PERMANENTLY_REDACTED, oldContentSha256), so every earlier correction counts. The oldest when there are
     * several.
     */
    Optional<Document> findCorrectedOriginalOf(UUID tenantId, UUID sourceId, Collection<UUID> submissionIds,
                                               String contentSha256);

    /**
     * Held until the transaction ends: two deliveries of the same file for the same record can't both decide that
     * nothing is stored yet, so one is stored and the other becomes its duplicate.
     */
    void lockDuplicateCheck(UUID tenantId, UUID sourceId, UUID recordId, String contentSha256);

    /** The duplicate this delivery (sourceDocumentId, or the idempotency key when there is none) already was. */
    Optional<DuplicateReceipt> findDuplicateReceipt(UUID tenantId, UUID submissionId, String sourceDocumentId,
                                                    String producerIdempotencyKey);

    void insertDuplicateReceipt(DuplicateReceipt receipt);

    /**
     * Task 0033 (0.1): the stored notice for the record's Document and Restricted stewards that an uncorrected copy of
     * a corrected document was received (steward_notices). It names the record by its sending system and primary
     * identifier (read from the submission), and the audit event it comes from.
     */
    void insertUncorrectedCopyNotice(UUID noticeId, UUID tenantId, UUID sourceId, UUID submissionId, UUID auditEventId,
                                     Instant createdAt);

    /**
     * One duplicate delivery (document_duplicate_receipts). Exactly one of sourceDocumentId and producerIdempotencyKey
     * is set. sentLevel: the document level it carried; levelOutcome: RAISED when that raised the existing document,
     * KEPT when it was lower or equal and ignored.
     */
    record DuplicateReceipt(UUID duplicateId, UUID tenantId, UUID sourceId, UUID submissionId, String sourceDocumentId,
                            String producerIdempotencyKey, UUID existingDocumentId, String contentSha256,
                            long contentSizeBytes, SensitivityLevel sentLevel, LevelOutcome levelOutcome,
                            Instant receivedAt) {
        public enum LevelOutcome { RAISED, KEPT }

        public DuplicateReceipt {
            if ((sourceDocumentId == null) == (producerIdempotencyKey == null)) {
                throw new IllegalArgumentException("A duplicate is named by its sourceDocumentId or, without one, its idempotency key.");
            }
            java.util.Objects.requireNonNull(existingDocumentId, "existingDocumentId");
            java.util.Objects.requireNonNull(sentLevel, "sentLevel");
            java.util.Objects.requireNonNull(levelOutcome, "levelOutcome");
        }
    }

    void insertDocument(Document document);
    void insertStorageRef(DocumentStorageRef storageRef);
    void insertCryptoMetadata(DocumentCryptoMetadata cryptoMetadata);

    Optional<DocumentStorageRef> findStorageRef(UUID tenantId, UUID documentId);
    Optional<DocumentCryptoMetadata> findCryptoMetadata(UUID tenantId, UUID documentId);

    /**
     * Marks a manifest slot received. documentId is null when an exact duplicate fills the slot (task 0031): the
     * document it points at belongs to another submission, and its duplicate receipt names it.
     */
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

    /** pageCount: worked out once when the document is stored; null when the PDF couldn't be read. */
    void markDocumentAvailable(UUID tenantId,
                               UUID sourceId,
                               UUID submissionId,
                               UUID documentId,
                               Instant availableAt,
                               Integer pageCount);

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
