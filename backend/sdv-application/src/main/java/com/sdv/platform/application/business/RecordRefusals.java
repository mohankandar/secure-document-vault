package com.sdv.platform.application.business;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;

/**
 * Task 0043: SDV-SCH-002, a record reference that is malformed (400) or names no record of this tenant (404). The
 * person reads the same sentence for both; the exact cause goes to the log line only (never the reference itself,
 * which holds identifier values).
 */
public final class RecordRefusals {
    static final String MESSAGE = "This record isn't there any more, or the link is wrong. Search for it again.";

    private RecordRefusals() { }

    /** The reference names no record of this tenant (404). */
    public static CodedRefusal.NotFound notFound(String cause) {
        return new CodedRefusal.NotFound(SdvErrorCode.SCH_002, MESSAGE, cause);
    }

    /** The reference can't be read (400). */
    public static CodedRefusal.BadRequest invalid(String cause) {
        return new CodedRefusal.BadRequest(SdvErrorCode.SCH_002, MESSAGE, cause);
    }
}
