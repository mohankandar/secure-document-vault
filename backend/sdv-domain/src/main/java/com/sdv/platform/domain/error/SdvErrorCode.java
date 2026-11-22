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
    /**
     * Task 0025: a sign-in was refused because its roles can never be held together and no other role was left.
     * Task 0038: sent as the code of the sign-in's 401 body; INFO line.
     */
    ROL_004("SDV-ROL-004", false, true),
    /** Task 0025: a sign-in was refused because its role conflict couldn't be recorded. Task 0038: as ROL-004. */
    ROL_005("SDV-ROL-005", false, true),
    /** Task 0038: the person's role doesn't include this action (403). INFO line with the capability. */
    ROL_006("SDV-ROL-006", false, true),
    /**
     * Task 0038: no sign-in at all, or the session ended (401). Task 0039 (owner answer to report 0038, 1): a DEBUG
     * line, not INFO, since every request without a sign-in would write one.
     */
    AUT_001("SDV-AUT-001", false, Log.DEBUG),
    /** Task 0038: a person's sign-in on a sending system's address, or the other way round (403). INFO line. */
    AUT_002("SDV-AUT-002", false, true),
    /** Task 0038: the token wasn't accepted: expired, forged, another issuer or audience, too long a life (401). INFO. */
    AUT_003("SDV-AUT-003", false, true),
    /** Task 0038: the sign-in hub's signing keys can't be fetched, so no token can be checked (503). ERROR line. */
    AUT_004("SDV-AUT-004", true),
    /** Task 0038: the token lacks a claim Vero needs, or a claim isn't usable (401). WARN with the claim's name. */
    AUT_005("SDV-AUT-005", true),
    /** Task 0038: no ACTIVE sign-in connection matches the token's issuer and audience (401). WARN with the host. */
    AUT_006("SDV-AUT-006", true),
    /** Task 0038: signed in, but no login group gives a Vero role (401). INFO line. */
    AUT_007("SDV-AUT-007", false, true),
    /** Task 0038: the local test sign-in headers are missing or not valid; local start only (401). INFO line. */
    AUT_008("SDV-AUT-008", false, true),
    /** Task 0038: the sign-in's organisation is unknown or suspended; one answer for both (403). WARN with which. */
    AUT_009("SDV-AUT-009", true),
    /**
     * Task 0038: a second ACTIVE sign-in connection for the same provider, issuer and audience: refused when it is
     * saved or activated (409, WARN), and 500 with an ERROR line at sign-in if two are found anyway.
     */
    IDP_007("SDV-IDP-007", true),
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
    /** Task 0033: no notice of this tenant has this reference (404). */
    INT_002("SDV-INT-002"),
    /** Task 0033: marking notices read needs 1 to 100 notice references (400). */
    INT_003("SDV-INT-003"),
    /** Task 0039: no submission of this sending system has this ID (404). INFO line. */
    INT_004("SDV-INT-004", false, true),
    /** Task 0039: the token's sending system isn't one of the tenant's, or Tenant IT paused it (403). WARN line. */
    INT_005("SDV-INT-005", true),
    /** Task 0039: no submission body, a count below 1, or a required text missing or too long (400). INFO line. */
    INT_006("SDV-INT-006", false, true),
    /** Task 0039: the list of expected documents isn't valid: over the limit, over the count, an ID twice (400). */
    INT_007("SDV-INT-007", false, true),
    /** Task 0039: a metadata field the sending system doesn't have, a required one missing, null or no scalar (400). */
    INT_008("SDV-INT-008", false, true),
    /** Task 0039: a metadata value breaks its field's type, pattern, range or length (400). INFO: field and rule. */
    INT_009("SDV-INT-009", false, true),
    /** Task 0039: a field's own stored rule can't be used: Vero's setup, not the sender's request (500). ERROR line. */
    INT_010("SDV-INT-010", true),
    /** Task 0039: the same sourceSubmissionId was sent again with different content (409). INFO line. */
    INT_011("SDV-INT-011", false, true),
    /** Task 0039: the upload names no document ID, or one not in the submission's complete list (400). INFO line. */
    INT_012("SDV-INT-012", false, true),
    /** Task 0039: the upload's file name, a text header or its content type isn't valid (400). INFO: the header. */
    INT_013("SDV-INT-013", false, true),
    /** Task 0039: the PDF is bigger than the tenant's or the sending system's limit (413). INFO with the limit. */
    INT_014("SDV-INT-014", false, true),
    /** Task 0039: the body is empty, cut short or doesn't start with a PDF header (400). INFO line. */
    INT_015("SDV-INT-015", false, true),
    /** Task 0039: the submission already has its documents (409), or this one takes it over its total size (400). */
    INT_016("SDV-INT-016", false, true),
    /** Task 0039: the same document ID was sent again with different content or file name (409). INFO line. */
    INT_017("SDV-INT-017", false, true),
    /** Task 0039: retry-finalization of an unknown document (404), or of one in a state it can't be (409). INFO. */
    INT_018("SDV-INT-018", false, true),
    /** Task 0032: the Reports tab is for Business Admins only (VIEW_INTAKE_REPORTS) (403). */
    RPT_001("SDV-RPT-001"),
    /**
     * Task 0032: the day or date range isn't valid: not a date, the end before the start, or more than 31 days (400).
     * Task 0033: the Sensitivity report's period, more than 366 days.
     */
    RPT_002("SDV-RPT-002"),
    /** Task 0032: the sending system isn't one of this tenant's (400). */
    RPT_003("SDV-RPT-003"),
    /** Task 0032: the tenant's time zone setting isn't a time zone Vero knows, so "today" can't be worked out (409). */
    RPT_004("SDV-RPT-004"),
    /** Task 0032: the range holds more lines than one report reads; choose fewer days or one sending system (400). */
    RPT_005("SDV-RPT-005"),
    /** Task 0036: the database is older than this Vero version; every request but health gets 503 until updated. */
    SYS_001("SDV-SYS-001", true),
    /** Task 0036: the database is newer than this Vero version (503). */
    SYS_002("SDV-SYS-002", true),
    /**
     * Task 0036: the database couldn't be read at start to check its version. Task 0037: logged at start; Vero keeps
     * trying, and requests meanwhile get SDV-SYS-014 (503).
     */
    SYS_003("SDV-SYS-003"),
    /** Task 0037: the changelog inside the Vero package can't be read; Vero doesn't start. */
    SYS_004("SDV-SYS-004", true),
    /** Task 0037: a sign-in setting is not valid; Vero doesn't start. */
    SYS_005("SDV-SYS-005", true),
    /**
     * Task 0037: sign-in is switched off: WARN at a local start, and a packaged start refuses. Task 0038: also the
     * code of the 401 every request gets while sign-in is off.
     */
    SYS_006("SDV-SYS-006", true),
    /** Task 0037: a key service setting is not valid; Vero doesn't start. */
    SYS_007("SDV-SYS-007", true),
    /** Task 0037: an approvals setting is not valid; Vero doesn't start. */
    SYS_008("SDV-SYS-008", true),
    /** Task 0037: the retention schedule setting is not valid; Vero doesn't start. */
    SYS_009("SDV-SYS-009", true),
    /** Task 0037: the install's default time zone is not valid; Vero doesn't start. */
    SYS_010("SDV-SYS-010", true),
    /** Task 0037: the explicitly requested sign-in recovery couldn't be applied; Vero doesn't start. */
    SYS_011("SDV-SYS-011", true),
    /** Task 0037: start-up work that needs the database failed; Vero doesn't start (or, after a late recovery, stops). */
    SYS_012("SDV-SYS-012"),
    /** Task 0036: a failure Vero didn't expect; the last-resort answer (500). */
    SYS_013("SDV-SYS-013"),
    /** Task 0036: the database failed during a request (Spring's DataAccessException) (503). */
    SYS_014("SDV-SYS-014"),
    /** Task 0036: the request couldn't be read before Vero's own code ran (Spring MVC refused it) (400). */
    SYS_015("SDV-SYS-015"),
    /** Task 0036: an internal check failed (InternalCheckFailed) (500). */
    SYS_016("SDV-SYS-016"),
    /** Task 0036: the action's audit couldn't be written, so the action was not done (500). */
    SYS_017("SDV-SYS-017"),
    /** Task 0036: no ACTIVE storage volume can take the document (503). */
    STO_001("SDV-STO-001", true),
    /** Task 0037: the storage placement preview's size is negative (400). Other volume settings follow in their task. */
    STO_005("SDV-STO-005"),
    /** Task 0037: no key service is set up: WARN at a local start, and a packaged start refuses. */
    KEY_001("SDV-KEY-001", true);

    /** Task 0039: the line an expected refusal (no person must act) gets: none, DEBUG or INFO. */
    private enum Log { NONE, DEBUG, INFO }

    private final String code;
    private final boolean needsPerson;
    private final Log refusalLog;

    SdvErrorCode(String code) {
        this(code, false);
    }

    SdvErrorCode(String code, boolean needsPerson) {
        this(code, needsPerson, Log.NONE);
    }

    SdvErrorCode(String code, boolean needsPerson, boolean loggedAtInfo) {
        this(code, needsPerson, loggedAtInfo ? Log.INFO : Log.NONE);
    }

    SdvErrorCode(String code, boolean needsPerson, Log refusalLog) {
        this.code = code;
        this.needsPerson = needsPerson;
        this.refusalLog = refusalLog;
    }

    public String code() {
        return code;
    }

    /**
     * Task 0036: the failure catalog marks this code "needs a person": a setting, a group or data must be fixed by
     * someone before it can succeed. A refusal (4xx) with such a code is logged at WARN; a 5xx is always ERROR.
     */
    public boolean needsPerson() {
        return needsPerson;
    }

    /**
     * Task 0038: an expected refusal that still gets one INFO line with the reference and its exact cause (the sign-in
     * and role refusals), so IT can match a reference a person gives them. Other refusals that need no person are not
     * logged; they are audited where the action is.
     */
    public boolean loggedAtInfo() {
        return refusalLog == Log.INFO;
    }

    /** Task 0039: an expected refusal logged at DEBUG only (SDV-AUT-001: a line per request without a sign-in). */
    public boolean loggedAtDebug() {
        return refusalLog == Log.DEBUG;
    }
}
