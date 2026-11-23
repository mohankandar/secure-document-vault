package com.sdv.platform.application.audit;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Task 0050: the sealed audit report as a PDF for people, made from the sealed CSV itself, so it holds exactly the
 * CSV's events. Page one carries the stamp (reference, who, when, the period, the filters, the number of events) and
 * the CSV's SHA-256 fingerprint; every page names the report and its page number. The events show when, what
 * happened, who, the record, the result, the document ID and the level: never content, file names, titles or reasons.
 * Characters the standard PDF font can't show are written as "?".
 */
final class SealedAuditPdf {
    private static final PDRectangle PAGE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float MARGIN = 36;
    private static final float SIZE = 7.5f;
    private static final float LEADING = 9.5f;
    /** CSV columns shown, with their width in points (770 in all). */
    private static final int[] COLUMNS = {0, 1, 2, 3, 4, 6, 7};
    private static final String[] HEADINGS = {"When (UTC)", "What happened", "Person", "Record", "Result", "Document ID", "Level"};
    private static final float[] WIDTHS = {88, 272, 95, 75, 50, 140, 50};

    private SealedAuditPdf() { }

    static byte[] write(SealedAuditReportService.Report report, byte[] csv) {
        List<List<String>> lines = SealedAuditCsv.read(csv);
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (String.join(",", lines.get(i)).equals(AuditCsv.HEADER)) { header = i; break; }
        }
        if (header < 0) throw new IllegalStateException("The sealed CSV has no event header.");
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDDocumentInformation info = pdf.getDocumentInformation();
            info.setTitle(SealedAuditReportService.TITLE + " " + report.reportId());
            info.setCreator("Vero");
            Calendar created = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            created.setTimeInMillis(report.generatedAt().toEpochMilli());
            info.setCreationDate(created);

            Writer w = new Writer(pdf, regular, bold);
            w.newPage();
            w.text(bold, 15, SealedAuditReportService.TITLE);
            w.gap(6);
            for (int i = 1; i < header; i++) {
                List<String> stamp = lines.get(i);
                if (stamp.size() >= 2) w.pair(stamp.get(0), stamp.get(1));
            }
            w.pair("SHA-256 of the CSV", report.fingerprint());
            w.gap(2);
            w.text(regular, SIZE, "The fingerprint is over the CSV file of this report. To check a copy, verify the CSV in Vero (Audit report, Verify). "
                    + "Never content, file names, titles or reasons.");
            w.gap(10);
            w.row(bold, HEADINGS, true);
            for (int i = header + 1; i < lines.size(); i++) {
                List<String> line = lines.get(i);
                String[] cells = new String[COLUMNS.length];
                for (int c = 0; c < COLUMNS.length; c++) cells[c] = COLUMNS[c] < line.size() ? line.get(COLUMNS[c]) : "";
                cells[6] = levelWords(cells[6]);
                if (!w.fits(regular, cells)) { w.newPage(); w.row(bold, HEADINGS, true); }
                w.row(regular, cells, false);
            }
            if (lines.size() == header + 1) w.text(regular, SIZE, "No events in this period with these filters.");
            w.close();
            w.footers(report.reportId().toString());
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("The audit report PDF could not be made.", ex);
        }
    }

    static String levelWords(String level) {
        return switch (level == null ? "" : level) {
            case "STANDARD" -> "Standard";
            case "CONFIDENTIAL" -> "Confidential";
            case "HIGHLY_CONFIDENTIAL" -> "Highly Confidential";
            default -> "";
        };
    }

    /** Lays text down page by page, top to bottom. */
    private static final class Writer {
        private final PDDocument pdf;
        private final PDType1Font regular;
        private final PDType1Font bold;
        private PDPageContentStream stream;
        private float y;

        Writer(PDDocument pdf, PDType1Font regular, PDType1Font bold) {
            this.pdf = pdf;
            this.regular = regular;
            this.bold = bold;
        }

        void newPage() throws IOException {
            close();
            PDPage page = new PDPage(PAGE);
            pdf.addPage(page);
            stream = new PDPageContentStream(pdf, page);
            y = PAGE.getHeight() - MARGIN;
        }

        void close() throws IOException {
            if (stream != null) { stream.close(); stream = null; }
        }

        void gap(float points) {
            y -= points;
        }

        void text(PDType1Font font, float size, String value) throws IOException {
            for (String part : wrap(font, size, value, PAGE.getWidth() - 2 * MARGIN)) {
                if (y - size < MARGIN + 14) newPage();
                put(font, size, MARGIN, y - size, part);
                y -= size + 3;
            }
        }

        void pair(String name, String value) throws IOException {
            float size = 9;
            List<String> parts = wrap(regular, size, value, PAGE.getWidth() - 2 * MARGIN - 130);
            for (int i = 0; i < parts.size(); i++) {
                if (i == 0) put(bold, size, MARGIN, y - size, name);
                put(regular, size, MARGIN + 130, y - size, parts.get(i));
                y -= size + 4;
            }
        }

        boolean fits(PDType1Font font, String[] cells) throws IOException {
            return y - height(font, cells) >= MARGIN + 14;
        }

        void row(PDType1Font font, String[] cells, boolean heading) throws IOException {
            float h = height(font, cells);
            float x = MARGIN;
            for (int c = 0; c < cells.length; c++) {
                List<String> parts = wrap(font, SIZE, cells[c], WIDTHS[c] - 6);
                for (int i = 0; i < parts.size(); i++) put(font, SIZE, x, y - SIZE - i * LEADING, parts.get(i));
                x += WIDTHS[c];
            }
            y -= h;
            stream.setLineWidth(heading ? 0.8f : 0.3f);
            stream.moveTo(MARGIN, y + 2);
            stream.lineTo(PAGE.getWidth() - MARGIN, y + 2);
            stream.stroke();
            y -= 3;
        }

        private float height(PDType1Font font, String[] cells) throws IOException {
            int most = 1;
            for (int c = 0; c < cells.length; c++) most = Math.max(most, wrap(font, SIZE, cells[c], WIDTHS[c] - 6).size());
            return most * LEADING + 2;
        }

        void footers(String reportId) throws IOException {
            int total = pdf.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                try (PDPageContentStream s = new PDPageContentStream(pdf, pdf.getPage(i), PDPageContentStream.AppendMode.APPEND, true)) {
                    s.beginText();
                    s.setFont(regular, 7);
                    s.newLineAtOffset(MARGIN, MARGIN - 14);
                    s.showText(safe(regular, SealedAuditReportService.TITLE + " " + reportId + " · page " + (i + 1) + " of " + total));
                    s.endText();
                }
            }
        }

        private void put(PDType1Font font, float size, float x, float baseline, String value) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(x, baseline);
            stream.showText(safe(font, value));
            stream.endText();
        }
    }

    /** Words onto lines no wider than {@code width}; a word too long for a line is cut. */
    static List<String> wrap(PDType1Font font, float size, String value, float width) throws IOException {
        String text = safe(font, value == null ? "" : value.replace('\r', ' ').replace('\n', ' '));
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(font, size, candidate) <= width) { line.setLength(0); line.append(candidate); continue; }
            if (!line.isEmpty()) { lines.add(line.toString()); line.setLength(0); }
            String rest = word;
            while (width(font, size, rest) > width && rest.length() > 1) {
                int cut = rest.length() - 1;
                while (cut > 1 && width(font, size, rest.substring(0, cut)) > width) cut--;
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        lines.add(line.toString());
        return lines;
    }

    private static float width(PDType1Font font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000f * size;
    }

    /** Only what the standard font can show; anything else becomes "?". */
    static String safe(PDType1Font font, String value) {
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            try {
                font.encode(ch);
                out.append(ch);
            } catch (IOException | IllegalArgumentException ex) {
                out.append('?');
            }
        });
        return out.toString();
    }
}
