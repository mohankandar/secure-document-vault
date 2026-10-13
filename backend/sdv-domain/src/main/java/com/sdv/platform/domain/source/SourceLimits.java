package com.sdv.platform.domain.source;

public record SourceLimits(
        Long maxPdfBytes,
        Integer maxPagesPerPdf,
        Integer maxDocumentsPerSubmission,
        Long maxAggregateSubmissionBytes,
        Integer incompleteAttentionHours) {
}
