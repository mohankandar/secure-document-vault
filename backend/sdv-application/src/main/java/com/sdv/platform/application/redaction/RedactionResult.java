package com.sdv.platform.application.redaction;

import java.time.Instant;
import java.util.UUID;

public record RedactionResult(UUID operationId, UUID documentId, String status,
                              int regionCount, String contentSha256, Instant expiresAt) {
}
