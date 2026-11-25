package com.sdv.platform.web.erase;

import com.documentplatform.eraselog.EraseRequest;
import com.sdv.platform.domain.error.SdvErrorCode;

import java.util.UUID;

/** Task 0080: the kinds of Vero's erase log entries and what their record IDs hold: UUIDs and a version number. */
public final class EraseIds {
    static final String DOCUMENT = "document";
    static final String SEALED_ORIGINAL = "sealed-original";
    static final String REPLACED_VERSION = "replaced-version";

    private EraseIds() { }

    /** platform-core takes handler codes without hyphens: SDV_ERS_003. */
    static String handlerCode(SdvErrorCode code) {
        return code.code().replace('-', '_');
    }

    /** Vero's own codes again as everywhere else (SDV-ERS-003); platform-core's as they are. */
    public static String shown(String handlerCode) {
        return handlerCode.startsWith("SDV_") ? handlerCode.replace('_', '-') : handlerCode;
    }

    static String sealed(UUID document, UUID sealed) {
        return document + ":" + sealed;
    }

    static String version(UUID document, int version) {
        return document + ":" + version;
    }

    static UUID document(EraseRequest request) {
        return UUID.fromString(request.recordId().split(":", 2)[0]);
    }

    static UUID sealed(EraseRequest request) {
        return UUID.fromString(request.recordId().split(":", 2)[1]);
    }

    static int version(EraseRequest request) {
        return Integer.parseInt(request.recordId().split(":", 2)[1]);
    }
}
