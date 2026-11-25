package com.sdv.platform.web.monitoring;

import com.documentplatform.errors.PlatformCode;
import com.sdv.platform.domain.error.SdvErrorCode;

import java.util.OptionalInt;

/** Task 0074: what Vero's own checks find, under Vero's own error codes (docs/api/error-codes.md). */
public enum VeroCheckCode implements PlatformCode {
    SCHEMA_OLDER(SdvErrorCode.SYS_001, "The database is older than this Vero version.", "Run the schema step of this version, then start Vero again."),
    SCHEMA_NEWER(SdvErrorCode.SYS_002, "The database is newer than this Vero version.", "Start the Vero version the database was updated for."),
    DATABASE_AWAY(SdvErrorCode.SYS_014, "The database can't be reached.", "Check that the database runs and can be reached from this server. Vero goes on by itself."),
    POOL_BUSY(SdvErrorCode.SYS_018, "Requests answered busy in the last hour: {count}.", "If it keeps happening, raise SDV_DB_POOL_MAX within the database's own limit, or spread the load."),
    SIGN_IN_KEYS(SdvErrorCode.AUT_004, "The login system's keys at {host} can't be fetched ({reason}).", "Check that the login system runs and can be reached from this server. Vero goes on by itself."),
    STORAGE(SdvErrorCode.STO_009, "Storage volumes nearly full: {warning}. Full or not usable: {stopped}. Active: {active}.", "IT frees space, adds a volume or makes the folder reachable (IT, Storage)."),
    INTAKE_ATTEMPTS(SdvErrorCode.SYS_020, "Intake requests in the last hour that no sending system could be found for: {count}.", "See Problems, Unidentified intake attempts: check the sending system's sign-in, or who is calling."),
    ERASE_LOG(SdvErrorCode.ERS_001, "The erase log stops all work ({code}).", "Runbook, Restore and Erase log. After a restore: run the erase replay."),
    ERASE_PENDING(SdvErrorCode.ERS_003, "Entry {seq} of the erase log can't be finished ({code}): {kind}, {record}. Nothing after it is erased.", "Fix what the code says (a folder or share, or its rights). The next erasure or cleanup goes on by itself."),
    KEY_SERVICE(SdvErrorCode.KEY_001, "The key service does not work or isn't set up ({state}).", "Check the key service: reachable, unsealed, and Vero's login accepted.");

    private final SdvErrorCode code;
    private final String cause;
    private final String fix;

    VeroCheckCode(SdvErrorCode code, String cause, String fix) {
        this.code = code;
        this.cause = cause;
        this.fix = fix;
    }

    @Override public String code() { return code.code(); }
    @Override public Kind kind() { return Kind.NEEDS_PERSON; }
    @Override public String userMessage() { return "Something Vero needs is not working properly. Your IT team can see it."; }
    @Override public String cause() { return cause; }
    @Override public String fix() { return fix; }
    @Override public String supportNote() { return "docs/api/error-codes.md, " + code.code() + "."; }
    @Override public OptionalInt httpStatus() { return OptionalInt.empty(); }
    @Override public String family() { return "VERO"; }
}
