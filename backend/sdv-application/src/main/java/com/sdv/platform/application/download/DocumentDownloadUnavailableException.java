package com.sdv.platform.application.download;

import com.sdv.platform.domain.error.CodedFault;
import com.sdv.platform.domain.error.SdvErrorCode;

/**
 * The download couldn't be done. Task 0043: it carries its cause's code (it was one DOWNLOAD_UNAVAILABLE for every
 * cause): SDV-DOC-007 (the stored copy can't be read or verified, nothing was sent) or SDV-DOC-009 (it stopped after
 * part of the file was sent; the answer can't carry the code any more, so the log line and the audit event do).
 */
public class DocumentDownloadUnavailableException extends CodedFault {
    public DocumentDownloadUnavailableException(SdvErrorCode errorCode, String message, String logDetail) {
        this(errorCode, message, logDetail, null);
    }

    public DocumentDownloadUnavailableException(SdvErrorCode errorCode, String message, String logDetail, Throwable cause) {
        super(errorCode, message, logDetail, cause);
    }
}
