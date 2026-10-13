package com.sdv.platform.application.document;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ValidatedPdfInputStreamTest {
    @Test
    void acceptsPdfAndProducesStableDigest() throws Exception {
        byte[] pdf = "%PDF-1.7\nhello\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        ValidatedPdfInputStream input = new ValidatedPdfInputStream(new ByteArrayInputStream(pdf), 1024);
        input.finish();
        assertEquals(pdf.length, input.sizeBytes());
        assertEquals(64, input.sha256().length());
    }

    @Test
    void rejectsNonPdf() {
        ValidatedPdfInputStream input = new ValidatedPdfInputStream(
                new ByteArrayInputStream("not-a-pdf".getBytes(StandardCharsets.US_ASCII)), 1024);
        assertThrows(InvalidPdfException.class, input::finish);
    }

    @Test
    void rejectsOversizeWithoutPersistingPlaintext() {
        byte[] pdf = "%PDF-1.7\n0123456789".getBytes(StandardCharsets.US_ASCII);
        ValidatedPdfInputStream input = new ValidatedPdfInputStream(new ByteArrayInputStream(pdf), 8);
        assertThrows(PdfTooLargeException.class, input::finish);
    }
}
