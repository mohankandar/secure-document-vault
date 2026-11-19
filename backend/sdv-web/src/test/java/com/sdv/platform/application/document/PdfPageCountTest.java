package com.sdv.platform.application.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PdfPageCountTest {
    private static byte[] pdfWithPages(int pages) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void countsThePages() throws Exception {
        assertEquals(1, PdfPageCount.of(pdfWithPages(1)));
        assertEquals(12, PdfPageCount.of(pdfWithPages(12)));
    }

    @Test
    void aPdfThatCantBeReadHasNoCount() {
        assertNull(PdfPageCount.of("%PDF-1.7 not really a pdf".getBytes(StandardCharsets.US_ASCII)));
        assertNull(PdfPageCount.of(new byte[0]));
        assertNull(PdfPageCount.of(null));
    }
}
