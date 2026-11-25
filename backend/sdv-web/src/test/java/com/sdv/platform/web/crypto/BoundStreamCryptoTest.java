package com.sdv.platform.web.crypto;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.envelope.StreamCipher;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.sdv.platform.application.port.StreamingCryptoPort.Binding;
import com.sdv.platform.application.port.StreamingCryptoPort.CheckFailed;
import com.sdv.platform.application.port.StreamingCryptoPort.CryptoEnvelope;
import com.sdv.platform.domain.error.InternalCheckFailed;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0076 (VSEC-13): a stored document opens only as what it was written as, and nothing is handed out before that is known. */
class BoundStreamCryptoTest {
    static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-VERO-DEMO-0001");
    final InMemoryKeyService keys = new InMemoryKeyService().createKeysOnDemand();
    final StreamCipher cipher = new StreamCipher(keys);
    final BoundStreamCrypto crypto = new BoundStreamCrypto(cipher, CUSTOMER);
    final UUID entity = UUID.randomUUID(), document = UUID.randomUUID(), sealed = UUID.randomUUID();

    record Stored(CryptoEnvelope envelope, byte[] bytes) { }

    Stored write(Binding binding, byte[] clear) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        return new Stored(crypto.encrypt(binding, new ByteArrayInputStream(clear), out), out.toByteArray());
    }

    byte[] read(Binding binding, Stored s) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        crypto.decrypt(binding, s.envelope(), new ByteArrayInputStream(s.bytes()), out);
        return out.toByteArray();
    }

    void refused(Binding binding, Stored s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(CheckFailed.class, () -> crypto.decrypt(binding, s.envelope(), new ByteArrayInputStream(s.bytes()), out));
        assertEquals(0, out.size(), "not a byte is handed out");
    }

    @Test
    void everySizeComesBackAndTheContentIsNotReadable() throws Exception {
        Random random = new Random(76);
        for (int size : new int[]{0, 1, 56, 57, 58, 65535 - 57, 65536 - 57, 65536, 65537, 3 * 65536 + 11, random.nextInt(400_000)}) {
            byte[] clear = new byte[size];
            random.nextBytes(clear);
            Stored s = write(Binding.document(entity, document), clear);
            assertArrayEquals(clear, read(Binding.document(entity, document), s), "size " + size);
            assertEquals(s.bytes().length, s.envelope().encryptedSizeBytes());
        }
        Stored s = write(Binding.document(entity, document), "%PDF-1.7 highly sensitive".getBytes());
        assertFalse(new String(s.bytes(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("sensitive"));
        assertEquals("platform-stream", s.envelope().cryptoProfile());
        assertTrue(s.envelope().protectedDataKeyReference().length() <= 700, "fits its column");
    }

    @Test
    void aStoredCopyIsOnlyWhatItWasWrittenAs() throws Exception {
        byte[] clear = new byte[100_000];
        Stored doc = write(Binding.document(entity, document), clear);
        Stored original = write(Binding.sealed(entity, document, sealed), clear);
        for (Binding other : new Binding[]{Binding.document(entity, UUID.randomUUID()), Binding.document(UUID.randomUUID(), document), Binding.sealed(entity, document, sealed)}) refused(other, doc);
        for (Binding other : new Binding[]{Binding.document(entity, document), Binding.sealed(entity, document, UUID.randomUUID()), Binding.sealed(entity, UUID.randomUUID(), sealed)}) refused(other, original);
        assertArrayEquals(clear, read(Binding.sealed(entity, document, sealed), original));
    }

    @Test
    void withoutAHeaderOrWithAShortOneItIsRefused() throws Exception {
        for (int size : new int[]{0, 10, 56, 57, 5000}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            var info = cipher.encrypt(CUSTOMER, new ByteArrayInputStream(new byte[size]), out);
            refused(Binding.document(entity, document), new Stored(new CryptoEnvelope(info.format(), info.formatVersion(), info.keyService(), info.wrappedKey(),
                    String.valueOf(info.keyVersion()), info.encryptedBytes(), info.ciphertextSha256()), out.toByteArray()));
        }
    }

    @Test
    void anyChangedByteAnotherCustomerAndTheOldFormatAreRefused() throws Exception {
        Binding binding = Binding.document(entity, document);
        Stored s = write(binding, "content".getBytes());
        for (int i = 0; i < s.bytes().length; i++) {
            byte[] changed = s.bytes().clone();
            changed[i] ^= 1;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThrows(java.io.IOException.class, () -> crypto.decrypt(binding, s.envelope(), new ByteArrayInputStream(changed), out), "byte " + i);
        }
        BoundStreamCrypto other = new BoundStreamCrypto(cipher, CustomerId.fromTenantId("TEN-OTHER-0000-0001"));
        assertThrows(CheckFailed.class, () -> other.decrypt(binding, s.envelope(), new ByteArrayInputStream(s.bytes()), new ByteArrayOutputStream()));
        refused(binding, new Stored(new CryptoEnvelope("SDV-ENC-001", 1, "VAULT_TRANSIT", "vault:v1:x", "v1", 10, "0".repeat(64)), s.bytes()));
    }

    @Test
    void theKeyServiceAwayIsNoFailedCheckAndADestroyedKeyIs() throws Exception {
        Binding binding = Binding.document(entity, document);
        Stored s = write(binding, "content".getBytes());
        keys.down(true);
        assertThrows(BoundStreamCrypto.KeyServiceUnusable.class, () -> read(binding, s));
        assertThrows(BoundStreamCrypto.KeyServiceUnusable.class, () -> write(binding, new byte[1]));
        keys.down(false).denied(true);
        assertThrows(BoundStreamCrypto.KeyServiceUnusable.class, () -> read(binding, s));
        keys.denied(false);
        keys.destroyCustomerKeys(CUSTOMER);
        refused(binding, s);
    }

    /** The header's bytes never change: stored documents carry them. */
    @Test
    void theHeaderIsFixed() {
        UUID a = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), b = UUID.fromString("01020304-0506-0708-090a-0b0c0d0e0f10");
        assertEquals("5645524f444f430101" + "00112233445566778899aabbccddeeff" + "0102030405060708090a0b0c0d0e0f10" + "0".repeat(32),
                HexFormat.of().formatHex(BoundStreamCrypto.header(Binding.document(a, b))));
        assertEquals("5645524f444f430102" + "00112233445566778899aabbccddeeff" + "0102030405060708090a0b0c0d0e0f10" + "ffffffffffffffffffffffffffffffff",
                HexFormat.of().formatHex(BoundStreamCrypto.header(Binding.sealed(a, b, new UUID(-1, -1)))));
        assertThrows(InternalCheckFailed.class, () -> Binding.document(null, b));
        assertThrows(InternalCheckFailed.class, () -> Binding.document(a, null));
    }
}
