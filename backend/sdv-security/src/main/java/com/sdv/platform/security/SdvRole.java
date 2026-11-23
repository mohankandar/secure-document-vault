package com.sdv.platform.security;

public enum SdvRole {
    BUSINESS_USER,
    DOCUMENT_STEWARD,
    /** Task 0025: everything a Document Steward has, plus Highly Confidential documents. Never held with an admin role. */
    RESTRICTED_STEWARD,
    BUSINESS_ADMIN,
    IT_ADMIN,
    PLATFORM_OPERATIONS_ADMIN
}
