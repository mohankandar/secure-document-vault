package com.sdv.platform.application.redaction;

public class RedactionFailedException extends RuntimeException {
    public RedactionFailedException(String message) { super(message); }
    public RedactionFailedException(String message, Throwable cause) { super(message, cause); }
}
