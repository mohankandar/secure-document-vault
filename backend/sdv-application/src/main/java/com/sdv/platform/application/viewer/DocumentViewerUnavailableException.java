package com.sdv.platform.application.viewer;

import com.sdv.platform.domain.error.CodedFault;
import com.sdv.platform.domain.error.SdvErrorCode;

/**
 * The viewer couldn't show the document. Task 0043: it carries its cause's code (it was one VIEWER_UNAVAILABLE for
 * every cause): SDV-DOC-003 (storage can't be read), SDV-DOC-004 (the stored copy failed its check, or is too big to
 * view) or SDV-DOC-005 (the PDF or a page can't be drawn).
 */
public class DocumentViewerUnavailableException extends CodedFault {
    public DocumentViewerUnavailableException(SdvErrorCode errorCode, String message, String logDetail) {
        this(errorCode, message, logDetail, null);
    }

    public DocumentViewerUnavailableException(SdvErrorCode errorCode, String message, String logDetail, Throwable cause) {
        super(errorCode, message, logDetail, cause);
    }
}
