package com.sdv.platform.crypto.enc;

import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.crypto.key.KeyProtectionProvider;
import com.sdv.platform.crypto.key.ProtectedDataKey;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/**
 * SDV-ENC-001 is a versioned streaming envelope around JCA AES-256-GCM.
 *
 * Plaintext is processed in bounded chunks. Every chunk has its own GCM authentication tag and a
 * nonce derived from a random per-document prefix plus the monotonic chunk index. A final
 * authenticated marker binds the stream length/chunk count so truncation is detected. No readable
 * PDF bytes are written to normal files by this component.
 */
public class SdvEnc001StreamingCrypto implements StreamingCryptoPort {
    public static final String PROFILE = "SDV-ENC-001";
    public static final int VERSION = 1;

    private static final byte[] MAGIC = "SDVENC01".getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE_PREFIX_BYTES = 8;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / 8;
    private static final int CHUNK_BYTES = 64 * 1024;
    private static final int DATA_RECORD = 1;
    private static final int FINAL_RECORD = 127;

    private final KeyProtectionProvider keyProvider;
    private final SecureRandom random;

    public SdvEnc001StreamingCrypto(KeyProtectionProvider keyProvider) {
        this(keyProvider, new SecureRandom());
    }

    SdvEnc001StreamingCrypto(KeyProtectionProvider keyProvider, SecureRandom random) {
        this.keyProvider = keyProvider;
        this.random = random;
    }

    @Override
    public CryptoEnvelope encrypt(UUID tenantId, InputStream plaintext, OutputStream ciphertext) throws IOException {
        if (tenantId == null || plaintext == null || ciphertext == null) {
            throw new IllegalArgumentException("tenantId, plaintext and ciphertext are required.");
        }

        ProtectedDataKey protectedKey = keyProvider.generateDataKey(tenantId);
        byte[] keyBytes = protectedKey.plaintextKey();
        if (keyBytes == null || keyBytes.length != 32) throw new IOException("Key provider did not supply a 256-bit data key.");

        byte[] chunk = new byte[CHUNK_BYTES];
        try {
            byte[] noncePrefix = new byte[NONCE_PREFIX_BYTES];
            random.nextBytes(noncePrefix);
            DigestingOutput digesting = new DigestingOutput(ciphertext);
            DataOutputStream data = new DataOutputStream(digesting);
            data.write(MAGIC);
            data.writeInt(VERSION);
            data.writeInt(CHUNK_BYTES);
            data.write(noncePrefix);

            int chunkIndex = 0;
            long totalPlaintextBytes = 0;
            while (true) {
                int length = readChunk(plaintext, chunk);
                if (length == 0) break;
                byte[] encrypted = encryptRecord(keyBytes, noncePrefix, tenantId, DATA_RECORD, chunkIndex, length, 0L,
                        Arrays.copyOf(chunk, length));
                data.writeByte(DATA_RECORD);
                data.writeInt(chunkIndex);
                data.writeInt(length);
                data.write(encrypted);
                Arrays.fill(encrypted, (byte) 0);
                Arrays.fill(chunk, 0, length, (byte) 0);
                totalPlaintextBytes += length;
                chunkIndex++;
            }

            byte[] finalTag = encryptRecord(keyBytes, noncePrefix, tenantId, FINAL_RECORD,
                    chunkIndex, 0, totalPlaintextBytes, new byte[0]);
            data.writeByte(FINAL_RECORD);
            data.writeInt(chunkIndex);
            data.writeLong(totalPlaintextBytes);
            data.write(finalTag);
            Arrays.fill(finalTag, (byte) 0);
            data.flush();

            return new CryptoEnvelope(
                    PROFILE, VERSION, protectedKey.providerId(), protectedKey.protectedReference(),
                    protectedKey.providerVersion(), digesting.count(), digesting.sha256());
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
            Arrays.fill(chunk, (byte) 0);
        }
    }

    @Override
    public void decrypt(UUID tenantId, CryptoEnvelope envelope, InputStream ciphertext, OutputStream plaintext) throws IOException {
        if (tenantId == null || envelope == null || ciphertext == null || plaintext == null) {
            throw new IllegalArgumentException("tenantId, envelope, ciphertext and plaintext are required.");
        }
        if (!PROFILE.equals(envelope.cryptoProfile()) || envelope.cryptoVersion() != VERSION) {
            throw new IOException("Unsupported SDV crypto profile/version.");
        }
        if (!keyProvider.providerId().equals(envelope.keyProviderId())) {
            throw new IOException("Configured key provider does not match document crypto metadata.");
        }

        DataInputStream data = new DataInputStream(ciphertext);
        byte[] magic = data.readNBytes(MAGIC.length);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("Encrypted object has an invalid SDV-ENC-001 header.");
        int version;
        int chunkSize;
        try {
            version = data.readInt();
            chunkSize = data.readInt();
        } catch (EOFException ex) {
            throw new IOException("Encrypted object has a truncated SDV-ENC-001 header.", ex);
        }
        if (version != VERSION) throw new IOException("Encrypted object has an unsupported SDV-ENC-001 version.");
        if (chunkSize != CHUNK_BYTES) throw new IOException("Encrypted object has an unsupported SDV-ENC-001 chunk size.");
        byte[] noncePrefix = data.readNBytes(NONCE_PREFIX_BYTES);
        if (noncePrefix.length != NONCE_PREFIX_BYTES) throw new IOException("Encrypted object has a truncated nonce prefix.");

        byte[] keyBytes = keyProvider.unprotectDataKey(tenantId, envelope.protectedDataKeyReference());
        if (keyBytes == null || keyBytes.length != 32) throw new IOException("Key provider did not return a 256-bit data key.");
        try {
            int expectedIndex = 0;
            long totalPlaintextBytes = 0;
            while (true) {
                int recordType;
                try {
                    recordType = data.readUnsignedByte();
                } catch (EOFException ex) {
                    throw new IOException("Encrypted object is truncated before the authenticated final marker.", ex);
                }

                if (recordType == DATA_RECORD) {
                    int index = data.readInt();
                    int plainLength = data.readInt();
                    if (index != expectedIndex) throw new IOException("Encrypted object chunk sequence is invalid.");
                    if (plainLength <= 0 || plainLength > CHUNK_BYTES) throw new IOException("Encrypted object chunk length is invalid.");
                    byte[] encrypted = data.readNBytes(plainLength + TAG_BYTES);
                    if (encrypted.length != plainLength + TAG_BYTES) throw new IOException("Encrypted object contains a truncated chunk.");
                    byte[] clear = decryptRecord(keyBytes, noncePrefix, tenantId, DATA_RECORD,
                            index, plainLength, 0L, encrypted);
                    if (clear.length != plainLength) throw new IOException("Authenticated chunk length does not match envelope metadata.");
                    plaintext.write(clear);
                    Arrays.fill(clear, (byte) 0);
                    Arrays.fill(encrypted, (byte) 0);
                    totalPlaintextBytes += plainLength;
                    expectedIndex++;
                    continue;
                }

                if (recordType == FINAL_RECORD) {
                    int finalIndex = data.readInt();
                    long declaredTotal = data.readLong();
                    if (finalIndex != expectedIndex || declaredTotal != totalPlaintextBytes) {
                        throw new IOException("Encrypted object final marker does not match authenticated stream length.");
                    }
                    byte[] encryptedFinal = data.readNBytes(TAG_BYTES);
                    if (encryptedFinal.length != TAG_BYTES) throw new IOException("Encrypted object has a truncated final authentication marker.");
                    byte[] finalClear = decryptRecord(keyBytes, noncePrefix, tenantId, FINAL_RECORD,
                            finalIndex, 0, declaredTotal, encryptedFinal);
                    if (finalClear.length != 0) throw new IOException("Encrypted object final marker is invalid.");
                    Arrays.fill(encryptedFinal, (byte) 0);
                    if (data.read() != -1) throw new IOException("Encrypted object contains unexpected bytes after its final marker.");
                    plaintext.flush();
                    return;
                }

                throw new IOException("Encrypted object contains an unknown SDV-ENC-001 record type.");
            }
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private static byte[] encryptRecord(byte[] keyBytes,
                                        byte[] noncePrefix,
                                        UUID tenantId,
                                        int recordType,
                                        int index,
                                        int plainLength,
                                        long totalLength,
                                        byte[] clear) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce(noncePrefix, index)));
            cipher.updateAAD(aad(tenantId, recordType, index, plainLength, totalLength));
            return cipher.doFinal(clear);
        } catch (GeneralSecurityException ex) {
            throw new IOException("SDV-ENC-001 encryption failed.", ex);
        } finally {
            Arrays.fill(clear, (byte) 0);
        }
    }

    private static byte[] decryptRecord(byte[] keyBytes,
                                        byte[] noncePrefix,
                                        UUID tenantId,
                                        int recordType,
                                        int index,
                                        int plainLength,
                                        long totalLength,
                                        byte[] encrypted) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce(noncePrefix, index)));
            cipher.updateAAD(aad(tenantId, recordType, index, plainLength, totalLength));
            return cipher.doFinal(encrypted);
        } catch (AEADBadTagException ex) {
            throw new IOException("SDV-ENC-001 authentication failed; ciphertext or associated context is invalid.", ex);
        } catch (GeneralSecurityException ex) {
            throw new IOException("SDV-ENC-001 decryption failed.", ex);
        }
    }

    private static byte[] nonce(byte[] prefix, int index) {
        ByteBuffer buffer = ByteBuffer.allocate(NONCE_BYTES);
        buffer.put(prefix);
        buffer.putInt(index);
        return buffer.array();
    }

    private static byte[] aad(UUID tenantId, int recordType, int index, int plainLength, long totalLength) {
        return (PROFILE + "|v=" + VERSION + "|tenant=" + tenantId + "|type=" + recordType
                + "|index=" + index + "|length=" + plainLength + "|total=" + totalLength)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static int readChunk(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read == -1) break;
            if (read == 0) continue;
            offset += read;
        }
        return offset;
    }

    private static final class DigestingOutput extends OutputStream {
        private final OutputStream delegate;
        private final MessageDigest digest;
        private long count;

        private DigestingOutput(OutputStream delegate) throws IOException {
            this.delegate = delegate;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException ex) {
                throw new IOException("SHA-256 is unavailable.", ex);
            }
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            digest.update((byte) b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            digest.update(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        long count() { return count; }
        String sha256() { return HexFormat.of().formatHex(digest.digest()); }
    }
}
