package com.sdv.platform.application.port;

import com.sdv.platform.domain.document.BusinessMetadataValue;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionExpectedDocument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubmissionRepository {
    InsertResult insertIfAbsent(Submission candidate);
    Optional<Submission> findById(UUID tenantId, UUID sourceId, UUID submissionId);
    Optional<Submission> findBySourceIdentity(UUID tenantId, UUID sourceId, String sourceSubmissionId);
    List<SubmissionExpectedDocument> findExpectedDocuments(UUID tenantId, UUID submissionId);
    List<BusinessMetadataValue> findBusinessMetadata(UUID tenantId, UUID sourceId, UUID submissionId);
    void insertExpectedDocuments(List<SubmissionExpectedDocument> expectedDocuments);
    void insertBusinessMetadata(List<BusinessMetadataValue> metadataValues);

    record InsertResult(Submission submission, boolean inserted) { }
}
