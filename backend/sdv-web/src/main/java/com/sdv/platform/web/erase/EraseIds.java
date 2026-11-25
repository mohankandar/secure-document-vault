package com.sdv.platform.web.erase;

import com.documentplatform.eraselog.EraseRequest;

import java.util.UUID;

/** Task 0080: the kinds of Vero's erase log entries and what their record IDs hold: UUIDs and a version number. */
final class EraseIds {
    static final String DOCUMENT = "document";
    static final String SEALED_ORIGINAL = "sealed-original";
    static final String REPLACED_VERSION = "replaced-version";

    private EraseIds() { }

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
