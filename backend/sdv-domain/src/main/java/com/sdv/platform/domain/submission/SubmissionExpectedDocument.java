package com.sdv.platform.domain.submission;

import java.time.Instant;
import java.util.UUID;

public record SubmissionExpectedDocument(
        UUID expectedDocumentId,
        UUID tenantId,
        UUID submissionId,
        String sourceDocumentId,
        ExpectedDocumentState state,
        UUID receivedDocumentId,
        Instant createdAt,
        Instant receivedAt) {
}
