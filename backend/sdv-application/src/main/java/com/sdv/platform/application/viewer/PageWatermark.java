package com.sdv.platform.application.viewer;

import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Task 0071: the light mark on a Highly Confidential page drawn for print or high zoom: who and when. Drawn from glyph
 * outlines that come with PDFBox, so it doesn't depend on the server's fonts.
 */
final class PageWatermark {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
    static final float OPACITY = 0.16f;

    private PageWatermark() { }

    static String text(String name, String subject, Instant at) {
        String who = name == null || name.isBlank() ? subject : name.strip();
        if (who.length() > 60) who = who.substring(0, 60);
        return who + "  " + TIME.format(at);
    }

    /** Across the whole page, in rows, at an angle. */
    static void draw(BufferedImage image, String text) throws IOException {
        GeneralPath line = outline(text);
        double size = Math.max(12, Math.min(image.getWidth(), image.getHeight()) / 28.0);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, OPACITY));
            g.setColor(new Color(90, 90, 90));
            double width = Math.max(line.getBounds2D().getWidth() * size / 1000, 1);
            double diagonal = Math.hypot(image.getWidth(), image.getHeight());
            g.translate(image.getWidth() / 2.0, image.getHeight() / 2.0);
            g.rotate(-Math.PI / 6);
            for (double y = -diagonal / 2; y < diagonal / 2; y += size * 7) {
                for (double x = -diagonal / 2; x < diagonal / 2; x += width + size * 4) {
                    AffineTransform at = AffineTransform.getTranslateInstance(x, y);
                    at.scale(size / 1000, -size / 1000); // glyph outlines: 1000 to the em, y upwards
                    g.fill(at.createTransformedShape(line));
                }
            }
        } finally {
            g.dispose();
        }
    }

    static GeneralPath outline(String text) throws IOException {
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        GeneralPath line = new GeneralPath();
        // The font may stand in for Helvetica with its own units to the em (widths are always 1000 to the em).
        List<Number> matrix = font.getFontBoxFont().getFontMatrix();
        double toEm = matrix.get(0).doubleValue() * 1000;
        double x = 0;
        for (int i = 0; i < text.length(); i++) {
            String one = text.substring(i, i + 1);
            byte[] code;
            try {
                code = font.encode(one);
            } catch (IllegalArgumentException ex) {
                code = font.encode("?"); // a letter this font doesn't have
            }
            GeneralPath glyph = font.getPath(font.getEncoding().getName(code[0] & 0xff));
            AffineTransform at = AffineTransform.getTranslateInstance(x, 0);
            at.scale(toEm, toEm);
            line.append(at.createTransformedShape(glyph), false);
            x += font.getWidth(code[0] & 0xff);
        }
        return line;
    }
}
