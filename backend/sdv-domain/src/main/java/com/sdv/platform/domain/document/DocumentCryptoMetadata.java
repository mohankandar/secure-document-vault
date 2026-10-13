package com.sdv.platform.domain.document;

import java.util.UUID;

public record DocumentCryptoMetadata(
        UUID documentId,
        UUID tenantId,
        String cryptoProfile,
        int cryptoVersion,
        String keyProviderId,
        String protectedDataKeyReference,
        String keyProviderVersion) {
}
