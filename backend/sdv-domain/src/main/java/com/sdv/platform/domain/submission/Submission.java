package com.sdv.platform.domain.submission;

import java.time.Instant;
import java.util.UUID;

public record Submission(
        UUID submissionId,
        UUID tenantId,
        UUID sourceId,
        String sourceSubmissionId,
        int expectedDocumentCount,
        String createRequestSha256,
        SubmissionStatus status,
        Instant createdAt,
        Instant lastActivityAt,
        Instant completedAt) {
}
