package com.sdv.platform.application.submission;

import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionExpectedDocument;

import java.util.List;

public record SubmissionDetails(
        Submission submission,
        List<SubmissionExpectedDocument> expectedDocuments,
        /* The values sent with this request; without one, the record's that aren't sensitive (task 0078). */
        java.util.Map<String, String> metadata,
        List<Document> documents,
        SubmissionProgress progress,
        boolean idempotentReplay) {
}
