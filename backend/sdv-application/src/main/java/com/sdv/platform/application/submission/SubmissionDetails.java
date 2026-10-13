package com.sdv.platform.application.submission;

import com.sdv.platform.domain.document.BusinessMetadataValue;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionExpectedDocument;

import java.util.List;

public record SubmissionDetails(
        Submission submission,
        List<SubmissionExpectedDocument> expectedDocuments,
        List<BusinessMetadataValue> metadataValues,
        List<Document> documents,
        SubmissionProgress progress,
        boolean idempotentReplay) {
}
