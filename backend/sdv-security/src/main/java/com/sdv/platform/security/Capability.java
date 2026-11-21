package com.sdv.platform.security;

public enum Capability {
    SEARCH_RECORDS,
    VIEW_PDF,
    PERMANENT_REDACTION,
    DOWNLOAD_CURRENT_PDF,
    PRINT_DOCUMENT,
    ADD_DOCUMENT_TAG,
    ADD_DOCUMENT_COMMENT,
    CONFIGURE_METADATA_SEARCH,
    CONFIGURE_RETENTION,
    CONFIGURE_BUSINESS_LIMITS,
    CONFIGURE_ROLE_PERMISSIONS,
    CONFIGURE_INGESTION_SOURCES,
    CONFIGURE_TENANT_AUTH,
    CONFIGURE_STORAGE_KEY_PROVIDER,
    VIEW_TECHNICAL_FAILURES,
    // Task 0025 (sensitivity). Nothing filters documents on these yet; task 0026 does.
    /** See documents whose effective level is CONFIDENTIAL. */
    VIEW_CONFIDENTIAL,
    /** See documents whose effective level is HIGHLY_CONFIDENTIAL. */
    VIEW_HIGHLY_CONFIDENTIAL,
    /** Raise a document's level, or ask for it to be lowered. */
    CHANGE_SENSITIVITY_LEVEL,
    /** Ask to print or download a Highly Confidential document. */
    REQUEST_SENSITIVE_ACCESS,
    /** Approve or refuse such a request. */
    APPROVE_SENSITIVE_ACCESS,
    MANAGE_LEGAL_HOLD,
    /** Task 0032: the Reports tab (Daily intake): counts only, never contents, titles or names. Business Admin only. */
    VIEW_INTAKE_REPORTS
}
