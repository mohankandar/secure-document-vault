package com.sdv.platform.application.download;

public class DocumentDownloadUnavailableException extends RuntimeException {
    public DocumentDownloadUnavailableException(String message) {
        super(message);
    }

    public DocumentDownloadUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
