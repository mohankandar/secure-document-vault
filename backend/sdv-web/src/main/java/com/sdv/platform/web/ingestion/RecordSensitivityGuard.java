package com.sdv.platform.web.ingestion;

/**
 * Task 0024, part 2: a record's level has one place to be sent, the submission JSON ({@code recordSensitivityLevel}).
 * The same value as an upload header is refused rather than ignored, so a level is never silently dropped.
 */
final class RecordSensitivityGuard {
    static final String HEADER = "X-SDV-Record-Sensitivity-Level";
    static final String MESSAGE = HEADER + " is not accepted on a document upload; send recordSensitivityLevel "
            + "on the submission instead.";

    private RecordSensitivityGuard() { }

    static void refuseIfPresent(Object value) {
        if (value != null) {
            throw new com.sdv.platform.domain.error.CodedRefusal.BadRequest(
                    com.sdv.platform.domain.error.SdvErrorCode.SEN_005, MESSAGE);
        }
    }
}
