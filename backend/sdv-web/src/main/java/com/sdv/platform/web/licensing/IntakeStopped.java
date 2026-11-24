package com.sdv.platform.web.licensing;

import com.sdv.platform.domain.error.SdvErrorCode;

/** Intake is refused because of the licence (503, no Retry-After). Nothing was stored. */
public final class IntakeStopped extends RuntimeException {
    static final String ENDED = "Vero takes no new documents: its licence doesn't allow it now. Nothing was stored. Viewing goes on. Ask your Business Admin.";
    static final String UNKNOWN = "Vero can't check its licence, so it takes no new documents. Nothing was stored. Ask your IT team.";
    private final SdvErrorCode code;

    IntakeStopped(SdvErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public SdvErrorCode errorCode() { return code; }
}
