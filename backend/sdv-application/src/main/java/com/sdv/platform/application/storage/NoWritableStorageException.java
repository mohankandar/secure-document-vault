package com.sdv.platform.application.storage;

import com.sdv.platform.domain.error.SdvErrorCode;

/**
 * No ACTIVE storage volume can take the content (SDV-STO-001). Task 0036: a server-side failure that needs a person
 * (IT frees space or adds a volume), answered 503, not a 409 refusal: it no longer extends
 * IllegalStateException. The message is for the log; the sender gets a fixed sentence.
 */
public class NoWritableStorageException extends RuntimeException {
    public NoWritableStorageException(String message) {
        super(message);
    }

    public SdvErrorCode errorCode() {
        return SdvErrorCode.STO_001;
    }
}
