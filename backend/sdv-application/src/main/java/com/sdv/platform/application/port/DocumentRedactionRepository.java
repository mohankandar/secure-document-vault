package com.sdv.platform.application.port;

import com.sdv.platform.application.redaction.RedactionOperation;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStorageRef;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRedactionRepository {
    Optional<Document> lockDocument(UUID tenantId, UUID documentId);
    Optional<RedactionOperation> findPending(UUID tenantId, UUID documentId);
    Optional<RedactionOperation> lockPending(UUID tenantId, UUID documentId);
    Optional<RedactionOperation> claimPendingForCompletion(UUID tenantId, UUID documentId, UUID claimToken);
    List<RedactionOperation> claimStaleForRecovery(int staleAfterMinutes, int limit, UUID claimToken);
    void releaseRecoveryClaim(UUID tenantId, UUID operationId, UUID claimToken);
    void insertPending(RedactionOperation operation);
    void markRedactionProcessing(UUID tenantId, UUID documentId, String expectedContentSha256);
    void finalizeReplacement(UUID tenantId, UUID documentId, String expectedOldContentSha256,
                             String newContentSha256, long newContentSizeBytes,
                             DocumentStorageRef newStorageRef, DocumentCryptoMetadata newCryptoMetadata,
                             Instant completedAt);
    void deletePending(UUID tenantId, UUID operationId);
}
