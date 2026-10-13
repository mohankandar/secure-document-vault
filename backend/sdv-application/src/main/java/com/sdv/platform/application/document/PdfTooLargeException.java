package com.sdv.platform.application.document;

import java.io.IOException;

public class PdfTooLargeException extends IOException {
    public PdfTooLargeException(String message) {
        super(message);
    }
}
