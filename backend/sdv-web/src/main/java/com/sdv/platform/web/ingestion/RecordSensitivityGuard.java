package com.sdv.platform.web.ingestion;

/**
 * Task 0024, part 1a/4a: a record-level sensitivity level is not stored yet, because SDV has no record table (a record
 * is the group of submissions that share their business-identifier values). Until the owner decides where it lives,
 * a delivery that sends one is refused, so the level is never silently dropped (fail closed).
 * See docs/work/0024-report.md, "Questions for the owner".
 */
final class RecordSensitivityGuard {
    static final String HEADER = "X-SDV-Record-Sensitivity-Level";
    static final String JSON_FIELD = "recordSensitivityLevel";
    static final String MESSAGE = "Record sensitivity levels are not accepted yet; leave out "
            + JSON_FIELD + " and " + HEADER + ".";

    private RecordSensitivityGuard() { }

    static void refuseIfPresent(Object value) {
        if (value != null) throw new IllegalArgumentException(MESSAGE);
    }
}
