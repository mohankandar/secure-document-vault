package com.sdv.platform.application.redaction;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfSanitizingRedactorTest {
    @Test
    void rebuildsPdfFromRasterizedPagesAndPermanentlyRemovesTextLayer() throws Exception {
        byte[] source = sourcePdf();
        PdfSanitizingRedactor redactor = new PdfSanitizingRedactor();

        byte[] sanitized = redactor.sanitize(
                source,
                List.of(new RedactionRegion(1, 0.08, 0.06, 0.72, 0.10)),
                10 * 1024 * 1024L);

        assertNotEquals(new String(source), new String(sanitized));

        try (PDDocument result = Loader.loadPDF(sanitized)) {
            assertEquals(2, result.getNumberOfPages());
            String text = new PDFTextStripper().getText(result);
            assertFalse(text.contains("SECRET-ALPHA-7391"));
            assertFalse(text.contains("VISIBLE-OMEGA-8842"),
                    "Sanitization intentionally rebuilds all pages from pixels so no old PDF text objects survive.");

            BufferedImage page = new PDFRenderer(result).renderImageWithDPI(0, 96, ImageType.RGB);
            int x = (int) (page.getWidth() * 0.40);
            int y = (int) (page.getHeight() * 0.10);
            Color sample = new Color(page.getRGB(x, y));
            assertTrue(sample.getRed() < 20 && sample.getGreen() < 20 && sample.getBlue() < 20,
                    "Selected corrective-redaction area must render black in the rebuilt PDF.");
            page.flush();
        }
    }


    @Test
    void appliesNormalizedRedactionCoordinatesToRotatedPage() throws Exception {
        byte[] source = rotatedSourcePdf();
        PdfSanitizingRedactor redactor = new PdfSanitizingRedactor();

        byte[] sanitized = redactor.sanitize(
                source,
                List.of(new RedactionRegion(1, 0.10, 0.10, 0.25, 0.20)),
                10 * 1024 * 1024L);

        try (PDDocument result = Loader.loadPDF(sanitized)) {
            assertEquals(1, result.getNumberOfPages());
            assertEquals(0, result.getPage(0).getRotation(), "Sanitized output is rebuilt in rendered orientation.");
            BufferedImage page = new PDFRenderer(result).renderImageWithDPI(0, 96, ImageType.RGB);
            int x = (int) (page.getWidth() * 0.20);
            int y = (int) (page.getHeight() * 0.18);
            Color sample = new Color(page.getRGB(x, y));
            assertTrue(sample.getRed() < 20 && sample.getGreen() < 20 && sample.getBlue() < 20,
                    "Normalized corrective-redaction region must remain black after rendering a rotated source page.");
            page.flush();
        }
    }

    private static byte[] rotatedSourcePdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            page.setRotation(90);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                content.beginText();
                content.setFont(font, 18);
                content.newLineAtOffset(72, 720);
                content.showText("ROTATED-SECRET-4412");
                content.endText();
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static byte[] sourcePdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            PDPage page1 = new PDPage(PDRectangle.LETTER);
            document.addPage(page1);
            try (PDPageContentStream content = new PDPageContentStream(document, page1)) {
                content.beginText();
                content.setFont(font, 18);
                content.newLineAtOffset(72, 720);
                content.showText("SECRET-ALPHA-7391");
                content.endText();
            }

            PDPage page2 = new PDPage(PDRectangle.LETTER);
            document.addPage(page2);
            try (PDPageContentStream content = new PDPageContentStream(document, page2)) {
                content.beginText();
                content.setFont(font, 18);
                content.newLineAtOffset(72, 720);
                content.showText("VISIBLE-OMEGA-8842");
                content.endText();
            }

            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
