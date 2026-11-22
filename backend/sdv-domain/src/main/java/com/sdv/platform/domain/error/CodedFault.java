package com.sdv.platform.domain.error;

import java.util.Objects;

/**
 * Task 0043: a document action Vero itself couldn't finish (the stored copy can't be read, fails its check, can't be
 * drawn, or a download stopped). The person was allowed to do it and did nothing wrong. It is answered with its own
 * status, its code and its message, and logged at the code's level (ERROR, or WARN for SDV-DOC-005) with the reference
 * and the exact cause.
 * <p>
 * Status (owner answer 1 to report 0043): {@link #UNAVAILABLE} 503 when storage or the key service can't be reached
 * (SDV-DOC-003, and SDV-DOC-007 for those causes), {@link #INTERNAL} 500 when the stored copy fails its check
 * (SDV-DOC-004, and SDV-DOC-007 for a failed check), and {@link #UNPROCESSABLE} 422 for what belongs to the document
 * itself: too large to view (SDV-DOC-004) or can't be drawn (SDV-DOC-005). No 503 here carries Retry-After; that is
 * only for the busy answer (SDV-SYS-018).
 * <p>
 * The message is sent, so it is one of the catalog's plain sentences: never a path, a key, an exception's text or
 * document content. The log detail (which check, which page, the document ID, whether a grant was used) is never sent.
 */
public class CodedFault extends RuntimeException {
    public static final int UNPROCESSABLE = 422;
    public static final int INTERNAL = 500;
    public static final int UNAVAILABLE = 503;

    private final SdvErrorCode errorCode;
    private final String logDetail;
    private final int httpStatus;

    public CodedFault(SdvErrorCode errorCode, int httpStatus, String message, String logDetail, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        if (httpStatus != UNPROCESSABLE && httpStatus != INTERNAL && httpStatus != UNAVAILABLE) {
            throw new IllegalArgumentException("A document fault is answered 422, 500 or 503.");
        }
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.logDetail = logDetail;
        this.httpStatus = httpStatus;
    }

    public SdvErrorCode errorCode() {
        return errorCode;
    }

    /** The exact cause for the log line; never sent. */
    public String logDetail() {
        return logDetail;
    }

    /** The answer's HTTP status: 422, 500 or 503. */
    public int httpStatus() {
        return httpStatus;
    }
}
