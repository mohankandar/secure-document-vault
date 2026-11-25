package com.sdv.platform.application.port;

import com.sdv.platform.domain.error.SdvErrorCode;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Task 0080: the only way to erase. Each call is written to the erase log first (IDs only, kept outside the database), then erased and
 * checked, so that a restored backup is brought up to date before anyone works with it. {@code auditId} is the ID of the audit event.
 */
public interface ErasePort {
    void document(UUID tenantId, UUID documentId, UUID auditId);

    void sealedOriginal(UUID tenantId, UUID documentId, UUID sealedId, UUID auditId);

    /** The version a redaction replaces. */
    void replacedVersion(UUID tenantId, UUID documentId, int version, UUID auditId);

    /** The values of a record without documents. */
    void caseFields(UUID tenantId, UUID sourceId, UUID recordId, UUID auditId);

    /** Erasing is one at a time. A transaction that erases takes its turn before it opens, so that nobody waits inside one. */
    <T> T inTurn(Supplier<T> work);

    /** Not done, or not finished. Codes only: the platform's or Vero's own. */
    final class EraseFailed extends RuntimeException {
        private final String code;

        public EraseFailed(String code, Throwable cause) {
            super("Erasing stopped: " + code, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }

        /** Refused by the check, before anything was logged: this one is kept, the next may go. */
        public boolean refused() {
            return code.equals(SdvErrorCode.ERS_004.code()) || code.equals("VALUES_LEGAL_HOLD");
        }
    }
}
