package com.sdv.platform.application.viewer;

public class DocumentViewerUnavailableException extends RuntimeException {
    public DocumentViewerUnavailableException(String message) {
        super(message);
    }

    public DocumentViewerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
