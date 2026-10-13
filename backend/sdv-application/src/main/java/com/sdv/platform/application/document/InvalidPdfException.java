package com.sdv.platform.application.document;

import java.io.IOException;

public class InvalidPdfException extends IOException {
    public InvalidPdfException(String message) {
        super(message);
    }
}
