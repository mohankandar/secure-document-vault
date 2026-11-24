package com.sdv.platform.web.crypto;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.envelope.EnvelopeInfo;
import com.documentplatform.crypto.envelope.StreamCipher;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.domain.error.InternalCheckFailed;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0076: documents through platform-core's {@link StreamCipher}, with the customer's key. The cipher knows only the
 * customer, so Vero puts a header first inside the encrypted content: {@code VERODOC} and version 1, the kind (document or sealed
 * original), and the IDs of entity, document and sealed original (zeros for a document). Nothing of the document is
 * handed out before the header was authenticated and is exactly the expected one. Sizes and the checksum are the document's own.
 */
public final class BoundStreamCrypto implements StreamingCryptoPort {
    static final int HEADER_BYTES = 8 + 1 + 3 * 16;
    private static final byte[] MAGIC = {'V', 'E', 'R', 'O', 'D', 'O', 'C', 1};
    /** The stored copy or its key is not the right one: a failed check. Everything else is the key service. */
    private static final Set<CryptoError> CHECK = EnumSet.of(CryptoError.CRYPTO_CIPHERTEXT_INVALID, CryptoError.CRYPTO_FORMAT_UNKNOWN,
            CryptoError.CRYPTO_WRONG_CUSTOMER, CryptoError.CRYPTO_KEY_SERVICE_MISMATCH, CryptoError.CRYPTO_KEY_MISSING, CryptoError.CRYPTO_KEY_DISABLED);

    private final StreamCipher cipher;
    private final CustomerId customer;

    public BoundStreamCrypto(StreamCipher cipher, CustomerId customer) {
        this.cipher = cipher;
        this.customer = customer;
    }

    static byte[] header(Binding binding) {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put((byte) (binding.sealedId() == null ? 1 : 2));
        for (UUID id : new UUID[]{binding.entityId(), binding.documentId(), binding.sealedId() == null ? new UUID(0, 0) : binding.sealedId()}) {
            header.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        }
        return header.array();
    }

    @Override
    public CryptoEnvelope encrypt(Binding binding, InputStream plaintext, OutputStream ciphertext) throws IOException {
        if (binding == null || plaintext == null || ciphertext == null) throw new InternalCheckFailed("binding, plaintext and ciphertext are required.");
        EnvelopeInfo info;
        try {
            info = cipher.encrypt(customer, new SequenceInputStream(new ByteArrayInputStream(header(binding)), plaintext), ciphertext);
        } catch (CryptoException ex) {
            throw new KeyServiceUnusable(ex);
        }
        return new CryptoEnvelope(info.format(), info.formatVersion(), info.keyService(), info.wrappedKey(), String.valueOf(info.keyVersion()),
                info.encryptedBytes(), info.ciphertextSha256());
    }

    @Override
    public void decrypt(Binding binding, CryptoEnvelope envelope, InputStream ciphertext, OutputStream plaintext) throws IOException {
        if (binding == null || envelope == null || ciphertext == null || plaintext == null) {
            throw new InternalCheckFailed("binding, envelope, ciphertext and plaintext are required.");
        }
        HeaderCheck checked = new HeaderCheck(header(binding), plaintext);
        try {
            cipher.decrypt(customer, info(envelope), ciphertext, checked);
        } catch (CryptoException ex) {
            if (CHECK.contains(ex.code())) throw new CheckFailed(ex.code().code());
            throw new KeyServiceUnusable(ex);
        }
        if (!checked.passed()) throw new CheckFailed("no binding header");
        checked.flush();
    }

    private EnvelopeInfo info(CryptoEnvelope e) throws CheckFailed {
        if (!StreamCipher.FORMAT.equals(e.cryptoProfile()) || e.cryptoVersion() != StreamCipher.VERSION) throw new CheckFailed("format " + CryptoError.CRYPTO_FORMAT_UNKNOWN.code());
        try {
            return new EnvelopeInfo(e.cryptoProfile(), e.cryptoVersion(), e.keyProviderId(), customer, e.protectedDataKeyReference(),
                    Integer.parseInt(e.keyProviderVersion()), e.encryptedSizeBytes(), e.ciphertextSha256());
        } catch (RuntimeException ex) {
            throw new CheckFailed("the stored key reference is not valid");
        }
    }

    /** The key service can't be used now; the cause names its code and what to do. Never a token or a key. */
    public static final class KeyServiceUnusable extends RuntimeException {
        KeyServiceUnusable(CryptoException cause) {
            super(cause.code().code(), cause);
        }
    }

    /** Takes the first {@link #HEADER_BYTES} bytes, compares them, and only then lets the rest through. */
    private static final class HeaderCheck extends OutputStream {
        private final byte[] expected;
        private final OutputStream out;
        private final byte[] seen = new byte[HEADER_BYTES];
        private int have;
        private boolean passed;

        HeaderCheck(byte[] expected, OutputStream out) {
            this.expected = expected;
            this.out = out;
        }

        boolean passed() { return passed; }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (!passed) {
                int n = Math.min(len, HEADER_BYTES - have);
                System.arraycopy(b, off, seen, have, n);
                have += n;
                if (have < HEADER_BYTES) return;
                if (!MessageDigest.isEqual(expected, seen)) throw new CheckFailed("bound to something else");
                passed = true;
                off += n;
                len -= n;
            }
            if (len > 0) out.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            if (passed) out.flush();
        }
    }
}
