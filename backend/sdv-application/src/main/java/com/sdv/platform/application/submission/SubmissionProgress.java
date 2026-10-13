package com.sdv.platform.application.submission;

import java.util.List;

public record SubmissionProgress(
        int expectedCount,
        int receivedCount,
        int missingCount,
        List<String> missingSourceDocumentIds) {
}
