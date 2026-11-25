package com.sdv.platform.domain.document;

import java.util.UUID;

public record DocumentCryptoMetadata(
        UUID documentId,
        UUID tenantId,
        String cryptoProfile,
        int cryptoVersion,
        String keyProviderId,
        String protectedDataKeyReference,
        String keyProviderVersion,
        /* Task 0091: the version of the stored document its ciphertext is bound to; 0 for a sealed original. */
        int objectVersion) {
    public static final int FIRST_VERSION = 1;

    /** The first version. */
    public DocumentCryptoMetadata(UUID documentId, UUID tenantId, String cryptoProfile, int cryptoVersion, String keyProviderId,
                                  String protectedDataKeyReference, String keyProviderVersion) {
        this(documentId, tenantId, cryptoProfile, cryptoVersion, keyProviderId, protectedDataKeyReference, keyProviderVersion, FIRST_VERSION);
    }
}
