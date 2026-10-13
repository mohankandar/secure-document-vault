package com.sdv.platform.application.redaction;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class PdfSanitizingRedactor {
    static final float SANITIZE_DPI = 144.0f;
    static final double MAX_RENDER_PIXELS = 24_000_000d;

    public byte[] sanitize(byte[] sourcePdf, List<RedactionRegion> regions, long maxOutputBytes) throws IOException {
        if (sourcePdf == null || sourcePdf.length == 0) throw new IllegalArgumentException("Source PDF is required.");
        if (regions == null || regions.isEmpty()) throw new IllegalArgumentException("At least one redaction region is required.");
        if (maxOutputBytes <= 0) throw new IllegalArgumentException("maxOutputBytes must be positive.");

        Map<Integer, List<RedactionRegion>> byPage =
                regions.stream().collect(Collectors.groupingBy(RedactionRegion::pageNumber));

        try (PDDocument source = Loader.loadPDF(sourcePdf);
             PDDocument output = new PDDocument();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(sourcePdf.length, 8 * 1024 * 1024))) {

            int pageCount = source.getNumberOfPages();
            for (Integer pageNumber : byPage.keySet()) {
                if (pageNumber < 1 || pageNumber > pageCount) {
                    throw new IllegalArgumentException("Redaction region references page " + pageNumber
                            + " but document has " + pageCount + " pages.");
                }
            }

            PDFRenderer renderer = new PDFRenderer(source);
            renderer.setSubsamplingAllowed(true);

            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                PDPage sourcePage = source.getPage(pageIndex);
                PDRectangle crop = sourcePage.getCropBox();
                double expectedWidth = (crop.getWidth() / 72.0d) * SANITIZE_DPI;
                double expectedHeight = (crop.getHeight() / 72.0d) * SANITIZE_DPI;
                if (sourcePage.getRotation() == 90 || sourcePage.getRotation() == 270) {
                    double swap = expectedWidth; expectedWidth = expectedHeight; expectedHeight = swap;
                }
                if (expectedWidth <= 0 || expectedHeight <= 0
                        || expectedWidth * expectedHeight > MAX_RENDER_PIXELS) {
                    throw new IOException("Page " + (pageIndex + 1)
                            + " exceeds the safe corrective-redaction rendering boundary.");
                }

                BufferedImage image = renderer.renderImageWithDPI(pageIndex, SANITIZE_DPI, ImageType.RGB);
                try {
                    Graphics2D graphics = image.createGraphics();
                    try {
                        graphics.setComposite(AlphaComposite.Src);
                        graphics.setColor(Color.BLACK);
                        for (RedactionRegion region : byPage.getOrDefault(pageIndex + 1, List.of())) {
                            int x = clamp((int)Math.floor(region.x() * image.getWidth()), 0, image.getWidth() - 1);
                            int y = clamp((int)Math.floor(region.y() * image.getHeight()), 0, image.getHeight() - 1);
                            int right = clamp((int)Math.ceil((region.x() + region.width()) * image.getWidth()), x + 1, image.getWidth());
                            int bottom = clamp((int)Math.ceil((region.y() + region.height()) * image.getHeight()), y + 1, image.getHeight());
                            x = Math.max(0, x - 1); y = Math.max(0, y - 1);
                            right = Math.min(image.getWidth(), right + 1);
                            bottom = Math.min(image.getHeight(), bottom + 1);
                            graphics.fillRect(x, y, right - x, bottom - y);
                        }
                    } finally {
                        graphics.dispose();
                    }

                    float pointsPerPixel = 72.0f / SANITIZE_DPI;
                    PDPage sanitizedPage = new PDPage(new PDRectangle(
                            image.getWidth() * pointsPerPixel, image.getHeight() * pointsPerPixel));
                    output.addPage(sanitizedPage);
                    PDImageXObject raster = LosslessFactory.createFromImage(output, image);
                    try (PDPageContentStream content = new PDPageContentStream(output, sanitizedPage)) {
                        content.drawImage(raster, 0, 0,
                                sanitizedPage.getMediaBox().getWidth(), sanitizedPage.getMediaBox().getHeight());
                    }
                } finally {
                    image.flush();
                }
            }

            output.getDocumentInformation().setTitle(null);
            output.getDocumentInformation().setAuthor(null);
            output.getDocumentInformation().setSubject(null);
            output.getDocumentInformation().setKeywords(null);
            output.getDocumentInformation().setCreator("Secure Document Vault");
            output.getDocumentInformation().setProducer("Secure Document Vault corrective redaction");
            output.save(bytes);
            if (bytes.size() > maxOutputBytes) throw new IOException("Sanitized PDF exceeds the configured PDF size limit.");
            return bytes.toByteArray();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IOException("Corrective PDF sanitization failed safely.", ex);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
