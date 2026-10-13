package com.sdv.platform.crypto.enc;

import com.sdv.platform.crypto.key.KeyProtectionProvider;
import com.sdv.platform.crypto.key.ProtectedDataKey;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SdvEnc001StreamingCryptoTest {
    @Test
    void encryptsAndDecryptsWithoutPersistingReadablePayload() throws Exception {
        InMemoryKeyProvider keys = new InMemoryKeyProvider();
        var crypto = new SdvEnc001StreamingCrypto(keys);
        UUID tenant = UUID.randomUUID();
        byte[] clear = "%PDF-1.7 highly-sensitive-demo-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        var envelope = crypto.encrypt(tenant, new ByteArrayInputStream(clear), encrypted);

        assertEquals("SDV-ENC-001", envelope.cryptoProfile());
        assertEquals(1, envelope.cryptoVersion());
        assertEquals(keys.providerId(), envelope.keyProviderId());
        assertNotEquals(new String(clear, java.nio.charset.StandardCharsets.UTF_8),
                new String(encrypted.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(indexOf(encrypted.toByteArray(), clear) >= 0, "Readable payload must not appear in ciphertext.");

        ByteArrayOutputStream restored = new ByteArrayOutputStream();
        crypto.decrypt(tenant, envelope, new ByteArrayInputStream(encrypted.toByteArray()), restored);
        assertArrayEquals(clear, restored.toByteArray());
    }

    @Test
    void usesUniqueDocumentKeysAndRejectsTamperingAndCrossTenantUnwrap() throws Exception {
        InMemoryKeyProvider keys = new InMemoryKeyProvider();
        var crypto = new SdvEnc001StreamingCrypto(keys);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        byte[] clear = new byte[128 * 1024];
        new SecureRandom().nextBytes(clear);

        ByteArrayOutputStream first = new ByteArrayOutputStream();
        var firstEnvelope = crypto.encrypt(tenantA, new ByteArrayInputStream(clear), first);
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        var secondEnvelope = crypto.encrypt(tenantA, new ByteArrayInputStream(clear), second);

        assertNotEquals(firstEnvelope.protectedDataKeyReference(), secondEnvelope.protectedDataKeyReference());
        assertFalse(java.util.Arrays.equals(first.toByteArray(), second.toByteArray()));

        byte[] tampered = first.toByteArray();
        tampered[tampered.length - 1] ^= 0x01;
        assertThrows(IOException.class, () -> crypto.decrypt(
                tenantA, firstEnvelope, new ByteArrayInputStream(tampered), new ByteArrayOutputStream()));
        assertThrows(IOException.class, () -> crypto.decrypt(
                tenantB, firstEnvelope, new ByteArrayInputStream(first.toByteArray()), new ByteArrayOutputStream()));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static final class InMemoryKeyProvider implements KeyProtectionProvider {
        private final SecureRandom random = new SecureRandom();
        private final Map<String, Entry> protectedKeys = new HashMap<>();

        @Override
        public String providerId() {
            return "TEST_TRANSIT";
        }

        @Override
        public ProtectedDataKey generateDataKey(UUID tenantId) {
            byte[] key = new byte[32];
            random.nextBytes(key);
            String reference = "test:v1:" + UUID.randomUUID();
            protectedKeys.put(reference, new Entry(tenantId, key.clone()));
            return new ProtectedDataKey(key, reference, providerId(), "v1");
        }

        @Override
        public byte[] unprotectDataKey(UUID tenantId, String protectedReference) throws IOException {
            Entry entry = protectedKeys.get(protectedReference);
            if (entry == null || !entry.tenantId().equals(tenantId)) throw new IOException("Tenant-isolated key unwrap denied.");
            return entry.key().clone();
        }

        private record Entry(UUID tenantId, byte[] key) { }
    }
}
