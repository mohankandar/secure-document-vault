package com.sdv.platform.application.port;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public interface StreamingCryptoPort {
    CryptoEnvelope encrypt(UUID tenantId, InputStream plaintext, OutputStream ciphertext) throws IOException;
    void decrypt(UUID tenantId, CryptoEnvelope envelope, InputStream ciphertext, OutputStream plaintext) throws IOException;

    record CryptoEnvelope(
            String cryptoProfile,
            int cryptoVersion,
            String keyProviderId,
            String protectedDataKeyReference,
            String keyProviderVersion,
            long encryptedSizeBytes,
            String ciphertextSha256) { }
}
