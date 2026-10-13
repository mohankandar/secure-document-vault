package com.sdv.platform.application.document;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ValidatedPdfInputStream extends FilterInputStream {
    private static final byte[] PDF_MAGIC = new byte[]{'%', 'P', 'D', 'F', '-'};

    private final long maxBytes;
    private final MessageDigest digest;
    private final byte[] prefix = new byte[PDF_MAGIC.length];
    private int prefixLength;
    private long count;
    private boolean eof;

    ValidatedPdfInputStream(InputStream input, long maxBytes) {
        super(input);
        if (input == null) throw new IllegalArgumentException("PDF input is required.");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive.");
        this.maxBytes = maxBytes;
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value == -1) {
            eof = true;
            return -1;
        }
        accept(new byte[]{(byte) value}, 0, 1);
        return value;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int read = super.read(b, off, len);
        if (read == -1) {
            eof = true;
            return -1;
        }
        if (read > 0) accept(b, off, read);
        return read;
    }

    private void accept(byte[] b, int off, int len) throws IOException {
        if (count > maxBytes - len) {
            throw new PdfTooLargeException("PDF exceeds the configured maximum of " + maxBytes + " bytes.");
        }
        int copy = Math.min(len, PDF_MAGIC.length - prefixLength);
        if (copy > 0) {
            System.arraycopy(b, off, prefix, prefixLength, copy);
            prefixLength += copy;
        }
        digest.update(b, off, len);
        count += len;
    }

    void finish() throws IOException {
        if (!eof) {
            while (read(new byte[8192]) != -1) {
                // Drain only if the caller did not consume the stream fully.
            }
        }
        if (count == 0) throw new InvalidPdfException("PDF content is empty.");
        if (prefixLength != PDF_MAGIC.length) throw new InvalidPdfException("PDF content is truncated.");
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (prefix[i] != PDF_MAGIC[i]) {
                throw new InvalidPdfException("Uploaded content does not have a valid PDF header.");
            }
        }
    }

    long sizeBytes() {
        return count;
    }

    String sha256() {
        return HexFormat.of().formatHex(digest.digest());
    }
}
