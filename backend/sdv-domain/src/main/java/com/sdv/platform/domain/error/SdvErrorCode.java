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
     * Task 0041: the person may work in more than one entity and the request chose none, or chose one that is not
     * theirs or is not an entity ID (409). INFO line with which.
     */
    AUT_010("SDV-AUT-010", false, true),
    /**
     * Task 0041: the token's client ID has no ACTIVE client binding, or its entity or the login connection is not
     * ACTIVE (401). WARN with the client ID (never a secret).
     */
    AUT_011("SDV-AUT-011", true),
    /** Task 0041: an entity or source claim in a sending system's token does not match its client binding (401). WARN. */
    AUT_012("SDV-AUT-012", true),
    /** Task 0041: the client binding names a source key the entity has no sending system for in Vero (401). WARN. */
    AUT_013("SDV-AUT-013", true),
    /** Task 0065: browser sign-in is not set up, or no login connection is in use (503). WARN line. */
    AUT_014("SDV-AUT-014", false, Log.WARN),
    /** Task 0065: the login system's addresses can't be read (503), or the browser can't reach it. WARN line. */
    AUT_015("SDV-AUT-015", false, Log.WARN),
    /** Task 0065: the login system's answer doesn't belong to this browser's sign-in (screen only). */
    AUT_016("SDV-AUT-016"),
    /** Task 0065: the login system refused the sign-in (screen only). */
    AUT_017("SDV-AUT-017"),
    /**
     * Task 0041: the entity slot can't be activated (not in the licence, no licence in force, already activated, name
     * not usable or taken); the answer names the reason (409). INFO line.
     */
    ENT_001("SDV-ENT-001", false, true),
    /** Task 0041: the person has no Business Admin grant on that entity slot, so may not activate it (403). INFO line. */
    ENT_002("SDV-ENT-002", false, true),
    /** Task 0048: the document is quarantined and this person is not one of its reviewers (403). INFO line. */
    QUA_001("SDV-QUA-001", false, true),
    /** Task 0048: quarantining, reviewing or releasing needs a Steward who can open the document's level (403). INFO. */
    QUA_002("SDV-QUA-002", false, true),
    /** Task 0048: quarantine by filter (a whole incident) is for a Restricted Steward only (403). INFO line. */
    QUA_003("SDV-QUA-003", false, true),
    /** Task 0048: the incident name, a reason, or the filter is missing or not valid (400). */
    QUA_004("SDV-QUA-004"),
    /** Task 0048: no document of this entity has this reference, or it was deleted (404). */
    QUA_005("SDV-QUA-005"),
    /** Task 0048: the document is already quarantined (409). */
    QUA_006("SDV-QUA-006"),
    /** Task 0048: no incident of this entity has this reference (404). */
    QUA_007("SDV-QUA-007"),
    /** Task 0048: the incident is released, so nothing can be added to it or asked for it (409). */
    QUA_008("SDV-QUA-008"),
    /** Task 0048: the number of matching documents changed since it was counted; count again (409). */
    QUA_009("SDV-QUA-009"),
    /** Task 0048: the release request can't be made or decided: one is waiting, it is decided or expired, or it is the person's own (409). */
    QUA_010("SDV-QUA-010"),
    /** Task 0048: the document is not quarantined (409). */
    QUA_011("SDV-QUA-011"),
    /** Task 0071: this person released as many of the incident's documents one by one as a day allows (409). */
    QUA_012("SDV-QUA-012"),
    /** Task 0050: the sealed audit report needs a Business Admin or IT Admin (403). */
    AUD_010("SDV-AUD-010"),
    /** Task 0050: the report's dates are missing, not days, or the first comes after the last (400). */
    AUD_011("SDV-AUD-011"),
    /** Task 0050: a filter of the report is not valid (400). */
    AUD_012("SDV-AUD-012"),
    /** Task 0050: the report would hold more than 10,000 events; nothing was generated (400). */
    AUD_013("SDV-AUD-013"),
    /** Task 0050: no report of this entity has this reference (404). */
    AUD_014("SDV-AUD-014"),
    /** Task 0050: the fingerprint to verify is not 64 hexadecimal characters (400). */
    AUD_015("SDV-AUD-015"),
    /** Task 0050: the report could not be recorded in the register or the audit, so it was not handed out (500). */
    AUD_016("SDV-AUD-016"),
    /** Task 0050: the database refused changing or removing an audit row or a report: they are append-only (500). */
    AUD_017("SDV-AUD-017"),
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
    /** Task 0066: the document was put on legal hold while it was being redacted; send the redaction again to ask for approval (409). */
    HLD_009("SDV-HLD-009"),
    /** Task 0066: no sealed original of this document has this reference, or it was destroyed (404). */
    HLD_010("SDV-HLD-010"),
    /** Task 0066: the sealed original of a held document is for Restricted Stewards only (403). */
    HLD_011("SDV-HLD-011"),
    /** Task 0071: the Business Admin who placed a hold doesn't decide its release; another one does (403). */
    HLD_012("SDV-HLD-012"),
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
    /** Task 0066: the redaction no longer applies: the document changed since it was asked for, or is gone (409). */
    APR_017("SDV-APR-017"),
    /** Task 0032: intake notices (an uncorrected copy sent again) are for Document stewards (403). */
    INT_001("SDV-INT-001"),
    /** Task 0033: no notice of this tenant has this reference (404). */
    INT_002("SDV-INT-002"),
    /** Task 0033: marking notices read needs 1 to 100 notice references (400). */
    INT_003("SDV-INT-003"),
    /** Task 0039: no submission of this sending system has this ID (404). INFO line. */
    INT_004("SDV-INT-004", false, true),
    /** Task 0039: the token's sending system isn't one of the tenant's, or IT paused it (403). WARN line. */
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
    /** Task 0063: an uploaded PDF that can't be read (400). */
    INT_019("SDV-INT-019", false, true),
    /** Task 0063: an uploaded PDF that is encrypted (400). */
    INT_020("SDV-INT-020", false, true),
    /** Task 0063: an uploaded PDF with active content (400). */
    INT_021("SDV-INT-021", false, true),
    /** Task 0063: an uploaded PDF with more pages than allowed (400). */
    INT_022("SDV-INT-022", false, true),
    /** Task 0043: no document of this tenant has this ID (404). INFO line. */
    DOC_001("SDV-DOC-001", false, Log.INFO),
    /** Task 0043: the document isn't available: still being stored, failed, being corrected or deleted (409). INFO. */
    DOC_002("SDV-DOC-002", false, Log.INFO),
    /** Task 0043: the viewer can't read the stored copy: storage metadata, volume, storage or key service (422). ERROR. */
    DOC_003("SDV-DOC-003", false, Log.ERROR),
    /** Task 0043: the stored copy failed its size or fingerprint check, or is too big to view (422). ERROR line. */
    DOC_004("SDV-DOC-004", false, Log.ERROR),
    /** Task 0043: the PDF can't be loaded or a page can't be drawn (422). WARN line with the page. */
    DOC_005("SDV-DOC-005", false, Log.WARN),
    /** Task 0043: a viewer request isn't valid: page, zoom, preview count or search text (400). DEBUG line. */
    DOC_006("SDV-DOC-006", false, Log.DEBUG),
    /** Task 0043: the download can't be read or verified before anything is sent (422). ERROR line. */
    DOC_007("SDV-DOC-007", false, Log.ERROR),
    /** Task 0043: a correction replaced the document between preparing and sending the download (409). WARN line. */
    DOC_008("SDV-DOC-008", false, Log.WARN),
    /**
     * Task 0043: the download stopped after part of the file was handed to the response (storage, key service, check
     * or the browser). ERROR line saying whether a grant was used; a Highly Confidential grant stays used.
     */
    DOC_009("SDV-DOC-009", false, Log.ERROR),
    /** Task 0043: a tag or comment is empty or too long (400). DEBUG line. */
    DOC_010("SDV-DOC-010", false, Log.DEBUG),
    /** Task 0063: pages, previews and search belong to an opening of the document by this person (409). */
    DOC_011("SDV-DOC-011", false, true),
    /** Task 0063: drawing or searching the document took too long (422). */
    DOC_012("SDV-DOC-012", false, Log.WARN),
    /** Task 0063: the document has more pages, or a larger picture, than Vero shows (422). */
    DOC_013("SDV-DOC-013", false, Log.WARN),
    /** Task 0071: pages in print quality need this person's own print, made with an approval, in the last 15 minutes (409). */
    DOC_014("SDV-DOC-014", false, true),
    /** Task 0043: the search criteria aren't valid (400). DEBUG line; messages name a field's key, never a value. */
    SCH_001("SDV-SCH-001", false, Log.DEBUG),
    /** Task 0043: the record reference is malformed or names no record of this tenant (400 or 404). INFO line. */
    SCH_002("SDV-SCH-002", false, Log.INFO),
    /** Task 0043: the recently viewed request isn't valid (400). DEBUG line. */
    SCH_003("SDV-SCH-003", false, Log.DEBUG),
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
    /** Task 0055: the Restrictions page is for Business Admins only (VIEW_RESTRICTIONS) (403). */
    RST_001("SDV-RST-001"),
    /** Task 0055: a page position of the Restrictions page that Vero didn't give (400). */
    RST_002("SDV-RST-002"),
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
    /**
     * Task 0040 part 2: Vero is too busy to take the request: the database connection pool is full, or PostgreSQL has
     * no connection left for Vero. 503 with a Retry-After header, the only answer that carries one.
     */
    SYS_018("SDV-SYS-018"),
    /** Task 0062: the request body is larger than Vero takes (413). Not for uploads: a PDF over its limit is SDV-INT-014. */
    SYS_019("SDV-SYS-019", false, true),
    /** Task 0074: many requests to the intake addresses could not be linked to a sending system (a check, never an answer). */
    SYS_020("SDV-SYS-020", true),
    /** Task 0036: no ACTIVE storage volume can take the document (503). */
    STO_001("SDV-STO-001", true),
    /**
     * Task 0040 part 2: the storage volume picked for an upload names a storage provider this install doesn't have
     * (503, no Retry-After; the installer must fix it). It used to be 409 CONFLICT.
     */
    STO_004("SDV-STO-004", true),
    /** Task 0037: the storage placement preview's size is negative (400). Other volume settings follow in their task. */
    STO_005("SDV-STO-005"),
    /** Task 0062: the folder may not be used for documents (400), whatever the reason. */
    STO_008("SDV-STO-008", false, true),
    /** Task 0074: a storage volume is nearly full, full or can't be used (a check, never an answer). */
    STO_009("SDV-STO-009", true),
    /** Task 0077: a field's settings are not valid, or not for its type (400). */
    /** Task 0080: the erase log doesn't let Vero take work. */
    ERS_001("SDV-ERS-001", true),
    /** Task 0080: the erase log's folder is in a document volume. */
    ERS_002("SDV-ERS-002", true),
    /** Task 0080: a logged erasure can't be finished. */
    ERS_003("SDV-ERS-003", true),
    /** Task 0080: the check refused an erasure before it was logged. */
    ERS_004("SDV-ERS-004", false, true),
    FLD_001("SDV-FLD-001", false, true),
    /** Task 0077: the field has values, so its type, its identifier flag and its sensitivity are locked (409). */
    FLD_002("SDV-FLD-002", false, true),
    /** Task 0077: the key is taken by another field, differs from one only in capitals, or somebody changed the field meanwhile (409). */
    FLD_003("SDV-FLD-003", false, true),
    /** Task 0077: the field list file can't be read (400). Nothing was changed. */
    FLD_004("SDV-FLD-004", false, true),
    /** Task 0075: the licence doesn't allow intake now (503, no Retry-After); everything else goes on. */
    LIC_001("SDV-LIC-001", true),
    /** Task 0075: the stored licence can't be read or checked, so intake is refused (503, no Retry-After). */
    LIC_002("SDV-LIC-002", true),
    /** Task 0075: the usage export's anchor is missing or is not the last export's (409). Never repaired by itself. */
    LIC_003("SDV-LIC-003", true),
    /** Task 0075: no finished month is left to export (400). */
    LIC_004("SDV-LIC-004", false, true),
    /** Task 0037: no key service is set up: WARN at a local start, and a packaged start refuses. */
    KEY_001("SDV-KEY-001", true),
    /** Task 0078: the key service can't be used now, so a sensitive value can't be stored, searched or opened (503). Nothing was stored. */
    KEY_002("SDV-KEY-002", true);

    /**
     * Task 0039: the line an expected refusal (no person must act) gets: none, DEBUG or INFO. Task 0043: WARN or ERROR
     * for a document action Vero itself couldn't finish, also when it is answered with a 4xx status (422).
     */
    private enum Log { NONE, DEBUG, INFO, WARN, ERROR }

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

    /** Task 0043: a document action refused or stopped that gets a WARN line (SDV-DOC-005, SDV-DOC-008). */
    public boolean loggedAtWarn() {
        return refusalLog == Log.WARN;
    }

    /** Task 0043: a document action Vero couldn't finish: ERROR, whatever the status (DOC-003, -004, -007, -009). */
    public boolean loggedAtError() {
        return refusalLog == Log.ERROR;
    }
}
