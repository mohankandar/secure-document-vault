package com.sdv.platform.application.storage;

import com.sdv.platform.domain.error.SdvErrorCode;

/**
 * Task 0040 part 2: the storage volume picked for a document names a storage provider this install doesn't have
 * (SDV-STO-004). Vero's setup, not the sender's fault: answered 503 (no Retry-After), not a 409 refusal, so it doesn't
 * extend IllegalStateException. The message (with the provider ID) is for the log; the sender gets a fixed sentence.
 */
public class StorageProviderMissingException extends RuntimeException {
    public StorageProviderMissingException(String message) {
        super(message);
    }

    public SdvErrorCode errorCode() {
        return SdvErrorCode.STO_004;
    }
}
