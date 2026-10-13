package com.sdv.platform.application.storage;

public class NoWritableStorageException extends IllegalStateException {
    public NoWritableStorageException(String message) {
        super(message);
    }
}
