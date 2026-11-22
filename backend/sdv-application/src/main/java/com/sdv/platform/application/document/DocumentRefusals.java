package com.sdv.platform.application.document;

import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;

import java.util.UUID;

/**
 * Task 0043: the two refusals every action on one document shares (viewer, download, print, tags, comments,
 * activity): SDV-DOC-001, no such document, and SDV-DOC-002, the document isn't available. They come after the level
 * check, so a restricted document still reveals nothing (SDV-SEN-008 first).
 */
public final class DocumentRefusals {
    static final String NOT_FOUND = "This document isn't there any more, or the link is wrong. Open the record again "
            + "from search.";

    private DocumentRefusals() { }

    /** SDV-DOC-001 (404). The log line names the document ID, which the person sent. */
    public static CodedRefusal.NotFound notFound(UUID documentId) {
        return new CodedRefusal.NotFound(SdvErrorCode.DOC_001, NOT_FOUND, "document=" + documentId);
    }

    /** SDV-DOC-002 (409): the state in plain words in the message, the state's name in the log line. */
    public static CodedRefusal.Conflict notAvailable(UUID documentId, DocumentStatus status) {
        return new CodedRefusal.Conflict(SdvErrorCode.DOC_002, "This document can't be opened yet: " + stateWords(status)
                + ".", "document=" + documentId + " status=" + status);
    }

    /** A document's state, in the words the person reads. */
    static String stateWords(DocumentStatus status) {
        if (status == null) return "Vero doesn't know its state";
        return switch (status) {
            case RECEIVING, RECEIVED, PROCESSING -> "it is still being stored";
            case REDACTION_PROCESSING -> "a permanent correction is being made to it";
            case PROCESSING_FAILED -> "Vero couldn't store it (see Problems)";
            case RETENTION_DELETING -> "it is being deleted, as the keeping rule says";
            case EXPIRED_DELETED -> "it was deleted, as the keeping rule says";
            case AVAILABLE -> "it is available";
        };
    }
}
