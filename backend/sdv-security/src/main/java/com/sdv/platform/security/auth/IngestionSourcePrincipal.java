package com.sdv.platform.security.auth;

import java.util.UUID;

public record IngestionSourcePrincipal(UUID tenantId, UUID sourceId, String subject) {
}
