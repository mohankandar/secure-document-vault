package com.sdv.platform.application.port;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Encryption of stored documents. Task 0076: what the ciphertext belongs to ({@link Binding}) is inside it and checked before
 * a byte is handed out. Reading: {@link CheckFailed} when the ciphertext, its key or its binding is not the right one; an
 * unchecked exception when the key service can't be used.
 */
public interface StreamingCryptoPort {
    CryptoEnvelope encrypt(Binding binding, InputStream plaintext, OutputStream ciphertext) throws IOException;

    void decrypt(Binding binding, CryptoEnvelope envelope, InputStream ciphertext, OutputStream plaintext) throws IOException;

    /** The entity, the document and its version (task 0091); for a sealed original its own ID and no version (0). */
    record Binding(UUID entityId, UUID documentId, int version, UUID sealedId) {
        public Binding {
            if (entityId == null || documentId == null) {
                throw new com.sdv.platform.domain.error.InternalCheckFailed("A stored document's ciphertext needs its entity and document.");
            }
            if (sealedId == null ? version < 1 : version != 0) {
                throw new com.sdv.platform.domain.error.InternalCheckFailed("A stored document's ciphertext needs its version, a sealed original's has none.");
            }
        }

        public static Binding document(UUID entityId, UUID documentId, int version) {
            return new Binding(entityId, documentId, version, null);
        }

        public static Binding sealed(UUID entityId, UUID documentId, UUID sealedId) {
            return new Binding(entityId, documentId, 0, java.util.Objects.requireNonNull(sealedId, "sealedId"));
        }
    }

    /** The stored copy isn't what it should be. The text is for the log: a step or a code, never content or a key. */
    final class CheckFailed extends IOException {
        public CheckFailed(String check) {
            super(check);
        }
    }

    record CryptoEnvelope(
            String cryptoProfile,
            int cryptoVersion,
            String keyProviderId,
            String protectedDataKeyReference,
            String keyProviderVersion,
            long encryptedSizeBytes,
            String ciphertextSha256) { }
}
