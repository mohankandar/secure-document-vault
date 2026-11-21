package com.sdv.platform.domain.error;

/**
 * Task 0036: an internal check that "can't fail" did fail: a bug, or data changed outside Vero. It is not a refusal:
 * the caller did nothing wrong, so it is answered 500 with SDV-SYS-016 (or SDV-SYS-017 when the action's audit
 * couldn't be written, so the action was not done). The message is the check's own sentence; it goes to the log for
 * IT and is never sent to the caller. It replaces IllegalStateException for these checks, which was answered 409.
 * <p>
 * It deliberately does not extend IllegalStateException, so nothing that handles a conflict handles it.
 */
public final class InternalCheckFailed extends RuntimeException {
    private final SdvErrorCode errorCode;

    /** An internal check failed (SDV-SYS-016). */
    public InternalCheckFailed(String check) {
        this(SdvErrorCode.SYS_016, check, null);
    }

    /** An internal check failed because of {@code cause} (SDV-SYS-016). */
    public InternalCheckFailed(String check, Throwable cause) {
        this(SdvErrorCode.SYS_016, check, cause);
    }

    private InternalCheckFailed(SdvErrorCode errorCode, String check, Throwable cause) {
        super(check, cause);
        this.errorCode = errorCode;
    }

    /** The action's audit couldn't be written, so the action was not done (SDV-SYS-017). */
    public static InternalCheckFailed auditNotWritten(String check, Throwable cause) {
        return new InternalCheckFailed(SdvErrorCode.SYS_017, check, cause);
    }

    public SdvErrorCode errorCode() {
        return errorCode;
    }
}
