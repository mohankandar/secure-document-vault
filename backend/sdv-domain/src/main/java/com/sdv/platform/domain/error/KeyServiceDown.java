package com.sdv.platform.domain.error;

/** Task 0078: the key service can't be used now, so a sensitive value can't be stored, searched or opened (503, SDV-KEY-002). */
public final class KeyServiceDown extends RuntimeException {
    private final String cause;

    /** cause: the key service's own code; never a token, a key or a value. */
    public KeyServiceDown(String cause) {
        super("The key service can't be used: " + cause);
        this.cause = cause;
    }

    public SdvErrorCode errorCode() { return SdvErrorCode.KEY_002; }

    public String causeCode() { return cause; }
}
