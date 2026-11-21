package com.sdv.platform.application.approvals;

/**
 * Task 0028: what an access request asks for. PRINT and DOWNLOAD (a Restricted Steward, one Highly Confidential
 * document, one use) and LOWER_LEVEL (a document's own level) are about one document; LOWER_RECORD_LEVEL is about a
 * whole record (contract, patient file).
 */
public enum AccessRequestKind {
    PRINT(Scope.DOCUMENT),
    DOWNLOAD(Scope.DOCUMENT),
    LOWER_LEVEL(Scope.DOCUMENT),
    LOWER_RECORD_LEVEL(Scope.RECORD);

    /** One document ("One document") or the whole record ("Whole contract"). */
    public enum Scope { DOCUMENT, RECORD }

    private final Scope scope;

    AccessRequestKind(Scope scope) {
        this.scope = scope;
    }

    public Scope scope() {
        return scope;
    }

    public boolean grant() {
        return this == PRINT || this == DOWNLOAD;
    }

    public boolean lowering() {
        return this == LOWER_LEVEL || this == LOWER_RECORD_LEVEL;
    }
}
