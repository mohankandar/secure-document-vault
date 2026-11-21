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
    /** Task 0026: the person's role can't open documents at this document's effective level (403). */
    SEN_008("SDV-SEN-008"),
    /**
     * Task 0026: printing or downloading a Highly Confidential document needs an approval (403). Task 0028: sent when
     * the person has no valid grant (approved, unused, unexpired, for them, this document and this kind).
     */
    SEN_009("SDV-SEN-009"),
    /** Task 0028: raising or asking to lower a level needs CHANGE_SENSITIVITY_LEVEL (a Steward) (403). */
    SEN_010("SDV-SEN-010"),
    /** Task 0028: the level asked for is missing or not one of the exact names (400). */
    SEN_011("SDV-SEN-011"),
    /** Task 0028: a level change's reason is missing, shorter than 3 or longer than 500 characters (400). */
    SEN_012("SDV-SEN-012"),
    /** Task 0028: a raise must go to a level higher than the current one (409). */
    SEN_013("SDV-SEN-013"),
    /** Task 0028: a lowering must go to a level lower than the current one (409). */
    SEN_014("SDV-SEN-014"),
    /** Task 0028: a Steward may ask to lower only from Confidential (403). */
    SEN_015("SDV-SEN-015"),
    /** Task 0028: no document or record of this tenant has this reference (404). */
    SEN_016("SDV-SEN-016"),
    /** Task 0028: only an available document's level can be changed (409). */
    SEN_017("SDV-SEN-017"),
    /** Task 0029: only a Steward or a Business Admin can read a level-change history (403). */
    SEN_018("SDV-SEN-018"),
    /** Task 0030: the audit's sensitivity filter names no level (400). */
    SEN_019("SDV-SEN-019"),
    /** Task 0025: a role-permission override would give a role a capability it may never have. */
    ROL_001("SDV-ROL-001"),
    /** Task 0025: a role-permission override names a role whose permissions are fixed. */
    ROL_002("SDV-ROL-002"),
    /** Task 0025: a role-permission override names a capability that can't be changed in Roles & permissions. */
    ROL_003("SDV-ROL-003"),
    /** Task 0025: a sign-in was refused because its roles can never be held together and no other role was left. */
    ROL_004("SDV-ROL-004"),
    /** Task 0025: a sign-in was refused because its role conflict couldn't be recorded. */
    ROL_005("SDV-ROL-005"),
    /** Task 0027: finding, placing, releasing or listing legal holds needs MANAGE_LEGAL_HOLD (Business Admin) (403). */
    HLD_001("SDV-HLD-001"),
    /** Task 0027: a legal hold's reason is missing, shorter than 3 or longer than 500 characters (400). */
    HLD_002("SDV-HLD-002"),
    /** Task 0027: the record identifier to look documents up by is missing or longer than 300 characters (400). */
    HLD_003("SDV-HLD-003"),
    /** Task 0027: no document of this tenant has this reference (404). */
    HLD_004("SDV-HLD-004"),
    /** Task 0027: the document is already on legal hold (409). */
    HLD_005("SDV-HLD-005"),
    /** Task 0027: the retention cleanup deleted the document, or is deleting it, so it can't be held (409). */
    HLD_006("SDV-HLD-006"),
    /** Task 0027: no legal hold of this tenant has this reference (404). */
    HLD_007("SDV-HLD-007"),
    /** Task 0027: the legal hold was already released (409). */
    HLD_008("SDV-HLD-008"),
    /** Task 0028: asking to print or download a Highly Confidential document needs REQUEST_SENSITIVE_ACCESS (403). */
    APR_001("SDV-APR-001"),
    /** Task 0028: a request's reason is missing, shorter than 3 or longer than 500 characters (400). */
    APR_002("SDV-APR-002"),
    /** Task 0028: the document isn't Highly Confidential, so printing or downloading it needs no approval (409). */
    APR_003("SDV-APR-003"),
    /** Task 0028: the same person already has the same request waiting (409). */
    APR_004("SDV-APR-004"),
    /** Task 0028: listing or deciding requests needs APPROVE_SENSITIVE_ACCESS (Business Admin) (403). */
    APR_005("SDV-APR-005"),
    /** Task 0028: nobody approves or declines their own request (403). */
    APR_006("SDV-APR-006"),
    /** Task 0028: no request of this tenant has this reference (404). */
    APR_007("SDV-APR-007"),
    /** Task 0028: the request is no longer waiting: it was approved, declined, used or has expired (409). */
    APR_008("SDV-APR-008"),
    /** Task 0028: a whole-record request is decided one at a time, never in a bulk decision (409). */
    APR_009("SDV-APR-009"),
    /** Task 0028: a bulk decision is malformed: no decision, no requests, or more than 100 (400). */
    APR_010("SDV-APR-010"),
    /** Task 0028: the lowering no longer applies: the level changed since the request, or the document is gone (409). */
    APR_011("SDV-APR-011"),
    /** Task 0028: the request kind is missing or unknown (400). */
    APR_012("SDV-APR-012"),
    /** Task 0028: no document of this tenant has this reference (404). */
    APR_013("SDV-APR-013"),
    /** Task 0028: the document isn't available, so it can't be printed or downloaded (409). */
    APR_014("SDV-APR-014"),
    /** Task 0028: a person's own requests are listed for Stewards only (CHANGE_SENSITIVITY_LEVEL) (403). */
    APR_015("SDV-APR-015"),
    /** Task 0032: intake notices (an uncorrected copy sent again) are for Document stewards (403). */
    INT_001("SDV-INT-001"),
    /** Task 0032: the Reports tab is for Business Admins only (VIEW_INTAKE_REPORTS) (403). */
    RPT_001("SDV-RPT-001"),
    /** Task 0032: the day or date range isn't valid: not a date, the end before the start, or more than 31 days (400). */
    RPT_002("SDV-RPT-002"),
    /** Task 0032: the sending system isn't one of this tenant's (400). */
    RPT_003("SDV-RPT-003"),
    /** Task 0032: the tenant's time zone setting isn't a time zone Vero knows, so "today" can't be worked out (409). */
    RPT_004("SDV-RPT-004"),
    /** Task 0032: the range holds more lines than one report reads; choose fewer days or one sending system (400). */
    RPT_005("SDV-RPT-005");

    private final String code;

    SdvErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
