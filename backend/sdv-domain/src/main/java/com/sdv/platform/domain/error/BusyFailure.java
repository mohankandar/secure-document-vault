package com.sdv.platform.domain.error;

/**
 * Task 0063: Vero has no room for this work just now; answered with the busy answer (503 SDV-SYS-018, Retry-After). The
 * message is for the log.
 */
public final class BusyFailure extends RuntimeException {
    public BusyFailure(String logDetail) {
        super(logDetail);
    }
}
