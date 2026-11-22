package com.sdv.platform.application.download;

import com.sdv.platform.domain.error.CodedFault;
import com.sdv.platform.domain.error.SdvErrorCode;

/**
 * The download couldn't be done. Task 0043: it carries its cause's code (it was one DOWNLOAD_UNAVAILABLE for every
 * cause): SDV-DOC-007 (nothing was sent; owner answer 1 to report 0043: 503 for storage or the key service, 500 for a
 * failed check) or SDV-DOC-009 (it stopped after part of the file was sent; the answer has started, so only the log
 * line and the audit event carry the code; 422 if anything can still be written).
 */
public class DocumentDownloadUnavailableException extends CodedFault {
    public DocumentDownloadUnavailableException(SdvErrorCode errorCode, int httpStatus, String message, String logDetail) {
        this(errorCode, httpStatus, message, logDetail, null);
    }

    public DocumentDownloadUnavailableException(SdvErrorCode errorCode, int httpStatus, String message, String logDetail,
                                                Throwable cause) {
        super(errorCode, httpStatus, message, logDetail, cause);
    }
}
