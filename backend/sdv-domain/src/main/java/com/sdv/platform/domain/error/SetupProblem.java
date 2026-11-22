package com.sdv.platform.domain.error;

/**
 * Task 0039: Vero's own setup stops the request, not the caller: for example a sending system's field has a stored
 * rule (a pattern, a length) that can't be used (SDV-INT-010). The caller can't fix it by changing the request, so it
 * is not a refusal: it is answered 500 with its code and its message, and logged at ERROR so IT can fix the setup.
 * <p>
 * The message is sent to the caller, so it holds only what is safe to send (a field's key, never a value or a rule's
 * text). It deliberately does not extend IllegalStateException, so nothing that handles a conflict (409) handles it.
 */
public final class SetupProblem extends RuntimeException {
    private final SdvErrorCode errorCode;
    private final String logDetail;

    public SetupProblem(SdvErrorCode errorCode, String message, String logDetail) {
        super(message);
        this.errorCode = errorCode;
        this.logDetail = logDetail;
    }

    public SdvErrorCode errorCode() {
        return errorCode;
    }

    /** The exact cause for the log line (which field, which rule); never sent. */
    public String logDetail() {
        return logDetail;
    }
}
