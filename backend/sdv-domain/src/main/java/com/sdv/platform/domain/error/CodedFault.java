package com.sdv.platform.domain.error;

import java.util.Objects;

/**
 * Task 0043: a document action Vero itself couldn't finish (the stored copy can't be read, fails its check, can't be
 * drawn, or a download stopped). The person was allowed to do it and did nothing wrong. It is answered 422 (the
 * status these answers had before), with its code and its message, and logged at the code's level (ERROR, or WARN
 * for SDV-DOC-005) with the reference and the exact cause.
 * <p>
 * The message is sent, so it is one of the catalog's plain sentences: never a path, a key, an exception's text or
 * document content. The log detail (which check, which page, the document ID, whether a grant was used) is never sent.
 */
public class CodedFault extends RuntimeException {
    private final SdvErrorCode errorCode;
    private final String logDetail;

    public CodedFault(SdvErrorCode errorCode, String message, String logDetail, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.logDetail = logDetail;
    }

    public SdvErrorCode errorCode() {
        return errorCode;
    }

    /** The exact cause for the log line; never sent. */
    public String logDetail() {
        return logDetail;
    }
}
