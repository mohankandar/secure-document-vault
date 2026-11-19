package com.sdv.platform.application.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * A stored document's page count, worked out once when the document is stored (task 0018 follow-up) and kept on the
 * document, so lists can show it without opening the document. A PDF that can't be read gives no count (null); the
 * document is stored all the same, and lists then show nothing for its pages.
 */
public final class PdfPageCount {
    private PdfPageCount() { }

    public static Integer of(byte[] pdf) {
        if (pdf == null || pdf.length == 0) return null;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            return pages > 0 ? pages : null;
        } catch (Exception e) {
            return null;
        }
    }
}
