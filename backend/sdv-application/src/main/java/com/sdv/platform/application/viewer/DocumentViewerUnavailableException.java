package com.sdv.platform.application.viewer;

import com.sdv.platform.domain.error.CodedFault;
import com.sdv.platform.domain.error.SdvErrorCode;

/**
 * The viewer couldn't show the document. Task 0043: it carries its cause's code (it was one VIEWER_UNAVAILABLE for
 * every cause) and, owner answer 1 to report 0043, its status: SDV-DOC-003 (storage or the key service can't be read,
 * 503), SDV-DOC-004 (the stored copy failed its check, 500; too large to view, 422) or SDV-DOC-005 (the PDF or a page
 * can't be drawn, 422).
 */
public class DocumentViewerUnavailableException extends CodedFault {
    public DocumentViewerUnavailableException(SdvErrorCode errorCode, int httpStatus, String message, String logDetail,
                                              Throwable cause) {
        super(errorCode, httpStatus, message, logDetail, cause);
    }
}
