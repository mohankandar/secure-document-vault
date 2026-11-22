package com.sdv.platform.application.document;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;

import java.io.IOException;

/**
 * The PDF is bigger than the tenant's or the sending system's limit (413). Task 0039: SDV-INT-014 (it was the code
 * PDF_TOO_LARGE). It stays an IOException because it is thrown while the upload is read.
 */
public class PdfTooLargeException extends IOException implements CodedRefusal {
    private final long limitBytes;

    public PdfTooLargeException(long limitBytes) {
        super("PDF exceeds the configured maximum of " + limitBytes + " bytes.");
        this.limitBytes = limitBytes;
    }

    @Override
    public SdvErrorCode errorCode() {
        return SdvErrorCode.INT_014;
    }

    @Override
    public String logDetail() {
        return "limitBytes=" + limitBytes;
    }
}
