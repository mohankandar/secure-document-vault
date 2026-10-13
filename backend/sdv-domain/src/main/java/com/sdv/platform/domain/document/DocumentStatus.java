package com.sdv.platform.domain.document;

public enum DocumentStatus {
    RECEIVING,
    RECEIVED,
    PROCESSING,
    AVAILABLE,
    REDACTION_PROCESSING,
    RETENTION_DELETING,
    PROCESSING_FAILED,
    EXPIRED_DELETED
}
