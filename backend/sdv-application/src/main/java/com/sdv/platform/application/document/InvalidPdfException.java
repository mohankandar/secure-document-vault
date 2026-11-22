package com.sdv.platform.application.document;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;

import java.io.IOException;

/**
 * The body is empty, cut short or doesn't start with a PDF header (400). Task 0039: SDV-INT-015 (it was the code
 * INVALID_PDF). It stays an IOException because it is thrown while the upload is read.
 */
public class InvalidPdfException extends IOException implements CodedRefusal {
    public InvalidPdfException(String message) {
        super(message);
    }

    @Override
    public SdvErrorCode errorCode() {
        return SdvErrorCode.INT_015;
    }
}
