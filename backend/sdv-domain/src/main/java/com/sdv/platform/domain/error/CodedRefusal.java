package com.sdv.platform.domain.error;

/** A refusal that carries an {@link SdvErrorCode}; the web layer sends the code with the message. */
public interface CodedRefusal {
    SdvErrorCode errorCode();

    /**
     * Task 0038: the exact cause for the log line only (for example which tenant, and whether it was missing or
     * suspended). Never sent to the caller; never a token, a claim value or a secret. Null when the message says it all.
     */
    default String logDetail() {
        return null;
    }

    /** A request that is wrong as sent (400). */
    final class BadRequest extends IllegalArgumentException implements CodedRefusal {
        private final SdvErrorCode errorCode;
        private final String logDetail;

        public BadRequest(SdvErrorCode errorCode, String message) {
            this(errorCode, message, null);
        }

        /** Task 0039: with the exact cause for the log line (a field's key, a rule, a header's name). */
        public BadRequest(SdvErrorCode errorCode, String message, String logDetail) {
            super(message);
            this.errorCode = errorCode;
            this.logDetail = logDetail;
        }

        @Override
        public SdvErrorCode errorCode() {
            return errorCode;
        }

        @Override
        public String logDetail() {
            return logDetail;
        }
    }

    /** Task 0038: the request carries no usable sign-in (401). The message says nothing about any account. */
    final class Unauthorized extends RuntimeException implements CodedRefusal {
        private final SdvErrorCode errorCode;
        private final String logDetail;

        public Unauthorized(SdvErrorCode errorCode, String message, String logDetail) {
            super(message);
            this.errorCode = errorCode;
            this.logDetail = logDetail;
        }

        @Override
        public SdvErrorCode errorCode() {
            return errorCode;
        }

        @Override
        public String logDetail() {
            return logDetail;
        }
    }

    /** Task 0026: a request this person may not make on this item (403). The message says nothing about the item. */
    final class Forbidden extends RuntimeException implements CodedRefusal {
        private final SdvErrorCode errorCode;
        private final String logDetail;

        public Forbidden(SdvErrorCode errorCode, String message) {
            this(errorCode, message, null);
        }

        /** Task 0038: with the exact cause for the log line, which the caller never sees. */
        public Forbidden(SdvErrorCode errorCode, String message, String logDetail) {
            super(message);
            this.errorCode = errorCode;
            this.logDetail = logDetail;
        }

        @Override
        public SdvErrorCode errorCode() {
            return errorCode;
        }

        @Override
        public String logDetail() {
            return logDetail;
        }
    }

    /** Task 0027: nothing of this tenant has this reference (404). */
    final class NotFound extends java.util.NoSuchElementException implements CodedRefusal {
        private final SdvErrorCode errorCode;
        private final String logDetail;

        public NotFound(SdvErrorCode errorCode, String message) {
            this(errorCode, message, null);
        }

        /** Task 0039: with the exact cause for the log line, which the caller never sees. */
        public NotFound(SdvErrorCode errorCode, String message, String logDetail) {
            super(message);
            this.errorCode = errorCode;
            this.logDetail = logDetail;
        }

        @Override
        public SdvErrorCode errorCode() {
            return errorCode;
        }

        @Override
        public String logDetail() {
            return logDetail;
        }
    }

    /** A request that conflicts with what is already stored (409). */
    final class Conflict extends IllegalStateException implements CodedRefusal {
        private final SdvErrorCode errorCode;
        private final String logDetail;

        public Conflict(SdvErrorCode errorCode, String message) {
            this(errorCode, message, null);
        }

        /** Task 0038: with the exact cause for the log line, which the caller never sees. */
        public Conflict(SdvErrorCode errorCode, String message, String logDetail) {
            super(message);
            this.errorCode = errorCode;
            this.logDetail = logDetail;
        }

        @Override
        public SdvErrorCode errorCode() {
            return errorCode;
        }

        @Override
        public String logDetail() {
            return logDetail;
        }
    }
}
