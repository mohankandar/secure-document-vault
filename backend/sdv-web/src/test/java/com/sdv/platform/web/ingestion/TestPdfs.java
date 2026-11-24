package com.sdv.platform.web.ingestion;

import com.sdv.platform.application.pdf.EncryptedScratch;
import com.sdv.platform.application.pdf.PdfIntakeChecks;
import com.sdv.platform.application.pdf.PdfInspector;
import com.sdv.platform.application.pdf.PdfWork;
import com.sdv.platform.domain.source.IngestionSource;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Task 0063: for intake tests. */
public final class TestPdfs {
    private TestPdfs() { }

    /** No checks: for tests of something else, whose "PDF" is only a header. */
    public static final PdfIntakeChecks UNCHECKED = new PdfIntakeChecks() {
        @Override
        public void check(UUID tenantId, IngestionSource source, int maxPages, RandomAccessRead pdf, EncryptedScratch scratch) { }

        @Override
        public EncryptedScratch scratch() {
            return PdfWork.defaults().scratch();
        }
    };

    /** The real checks, with the default settings. */
    public static PdfIntakeChecks real() {
        return new PdfInspector(PdfWork.defaults(), "");
    }

    public interface Fill {
        void in(PDDocument pdf) throws IOException;
    }

    public static byte[] pdf(int pages, Fill fill) {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) pdf.addPage(new PDPage());
            fill.in(pdf);
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
