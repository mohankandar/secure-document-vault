package com.sdv.platform.domain.tenant;

public record TenantGuardrails(
        long maxPdfBytes,
        int maxPagesPerPdf,
        int maxDocumentsPerSubmission,
        long maxAggregateSubmissionBytes,
        int incompleteAttentionHours,
        int storageWarningPercent,
        int storageStopWritePercent,
        Integer defaultRetentionDays) {

    public static TenantGuardrails defaults() {
        return new TenantGuardrails(
                250L * 1024L * 1024L,
                2_000,
                100,
                2L * 1024L * 1024L * 1024L,
                24,
                90,
                95,
                null);
    }
}
