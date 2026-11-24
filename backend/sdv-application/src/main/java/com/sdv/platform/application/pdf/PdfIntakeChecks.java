package com.sdv.platform.application.pdf;

import com.sdv.platform.domain.source.IngestionSource;
import org.apache.pdfbox.io.RandomAccessRead;

import java.io.IOException;
import java.util.UUID;

/** Task 0063: what Vero checks in a PDF before it keeps it. */
public interface PdfIntakeChecks {
    /**
     * Refuses (with its own code) a PDF that can't be read, is encrypted, carries active content or has too many pages.
     *
     * @param maxPages the most pages for this sending system
     */
    void check(UUID tenantId, IngestionSource source, int maxPages, RandomAccessRead pdf, EncryptedScratch scratch) throws IOException;

    /** Working space for one upload. */
    EncryptedScratch scratch();
}
