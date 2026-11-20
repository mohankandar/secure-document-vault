package com.sdv.platform.domain.error;

/**
 * Task 0024, part 2, 4a: stable codes for refusals, written as SDV-AREA-NNN. A code never changes meaning once
 * released. Every code is listed in docs/api/error-codes.md, with what it means, what the user should do and what
 * support should tell the customer; other applications read that list.
 */
public enum SdvErrorCode {
    /** The document upload's X-SDV-Sensitivity-Level is not one of the exact names. */
    SEN_001("SDV-SEN-001"),
    /** The submission's recordSensitivityLevel is not one of the exact names. */
    SEN_002("SDV-SEN-002"),
    /** A document retry was sent with a different sensitivity level than the first upload. */
    SEN_003("SDV-SEN-003"),
    /** A submission retry was sent with a different recordSensitivityLevel than the first request. */
    SEN_004("SDV-SEN-004"),
    /** A record level was sent on a document upload instead of on the submission. */
    SEN_005("SDV-SEN-005"),
    /** A document level was sent on the submission instead of on the document upload. */
    SEN_006("SDV-SEN-006"),
    /** An identifier field change was refused because records of the sending system have a level. */
    SEN_007("SDV-SEN-007"),
    /** Task 0025: a role-permission override would give a role a capability it may never have. */
    ROL_001("SDV-ROL-001"),
    /** Task 0025: a role-permission override names a role whose permissions are fixed. */
    ROL_002("SDV-ROL-002"),
    /** Task 0025: a role-permission override names a capability that can't be changed in Roles & permissions. */
    ROL_003("SDV-ROL-003"),
    /** Task 0025: a sign-in was refused because its roles can never be held together and no other role was left. */
    ROL_004("SDV-ROL-004"),
    /** Task 0025: a sign-in was refused because its role conflict couldn't be recorded. */
    ROL_005("SDV-ROL-005");

    private final String code;

    SdvErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
