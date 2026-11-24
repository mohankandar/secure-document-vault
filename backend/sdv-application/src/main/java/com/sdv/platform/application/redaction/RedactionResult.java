package com.sdv.platform.application.redaction;

import java.time.Instant;
import java.util.UUID;

public record RedactionResult(UUID operationId, UUID documentId, String status,
                              int regionCount, String contentSha256, Instant expiresAt,
                              /* Task 0066: set when a Business Admin must approve first; nothing was changed yet. */
                              UUID accessRequestId) {
    public static final String APPROVAL_REQUESTED = "APPROVAL_REQUESTED";
}
