package com.sdv.platform.crypto.key;

public record ProtectedDataKey(
        byte[] plaintextKey,
        String protectedReference,
        String providerId,
        String providerVersion) {
}
