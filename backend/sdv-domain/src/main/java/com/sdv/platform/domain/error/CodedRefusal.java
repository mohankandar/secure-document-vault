package com.sdv.platform.domain.error;

/** A refusal that carries an {@link SdvErrorCode}; the web layer sends the code with the message. */
public interface CodedRefusal {
    SdvErrorCode errorCode();

    /** A request that is wrong as sent (400). */
    final class BadRequest extends IllegalArgumentException implements CodedRefusal {
        private final SdvErrorCode errorCode;

        public BadRequest(SdvErrorCode errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        @Override
        public SdvErrorCode errorCode() {
            return errorCode;
        }
    }

    /** A request that conflicts with what is already stored (409). */
    final class Conflict extends IllegalStateException implements CodedRefusal {
        private final SdvErrorCode errorCode;

        public Conflict(SdvErrorCode errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        @Override
        public SdvErrorCode errorCode() {
            return errorCode;
        }
    }
}
