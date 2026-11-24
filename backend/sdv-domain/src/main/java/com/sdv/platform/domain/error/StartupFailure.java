package com.sdv.platform.domain.error;

/**
 * Task 0037: Vero can't start, or start-up work can't be done, because of a setting, the package or the database.
 * It carries the code (SDV-SYS-004 to SDV-SYS-012, SDV-KEY-001), the catalog's sentence for it, the exact cause and
 * what to do. The start-up failure report prints them as one ERROR block, without a stack trace.
 * <p>
 * The cause names the setting and, where it isn't secret, its value. It never holds a password, a token or the
 * contents of a token file.
 * <p>
 * Like {@link InternalCheckFailed} it deliberately does not extend IllegalStateException, so nothing that answers a
 * conflict answers it.
 */
public final class StartupFailure extends RuntimeException {
    private final SdvErrorCode errorCode;
    private final String detail;

    public StartupFailure(SdvErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public StartupFailure(SdvErrorCode errorCode, String detail, Throwable cause) {
        super(errorCode.code() + " " + sentence(errorCode) + " " + detail, cause);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    public SdvErrorCode errorCode() {
        return errorCode;
    }

    /** The exact cause, as the check found it. */
    public String detail() {
        return detail;
    }

    public String sentence() {
        return sentence(errorCode);
    }

    public String action() {
        return action(errorCode);
    }

    /** The catalog's sentence for a start-up code (docs/api/error-codes.md, "Server (SYS)"). */
    public static String sentence(SdvErrorCode code) {
        return switch (code) {
            case SYS_001 -> "The database is older than this Vero version.";
            case SYS_002 -> "The database is newer than this Vero version.";
            case SYS_003 -> "Vero couldn't reach its database at start.";
            case SYS_004 -> "The changelog bundled with this Vero package can't be read.";
            case SYS_005 -> "A sign-in setting is not valid.";
            case SYS_006 -> "Sign-in is switched off (SDV_AUTH_MODE=disabled).";
            case SYS_007 -> "A key service setting is not valid.";
            case SYS_008 -> "An approvals setting is not valid.";
            case SYS_009 -> "The retention schedule setting is not valid.";
            case SYS_010 -> "The install's default time zone is not valid.";
            case SYS_012 -> "Start-up work that needs the database couldn't be done.";
            case KEY_001 -> "Encryption is switched off (SDV_CRYPTO_ENABLED=false).";
            default -> "Vero can't start.";
        };
    }

    /** What IT does about it. */
    public static String action(SdvErrorCode code) {
        return switch (code) {
            case SYS_001 -> "The database team runs the schema step (install guide, \"Update the database\"), then Vero is restarted.";
            case SYS_002 -> "Start the Vero version the database was updated for. Never roll the database back by hand.";
            case SYS_003 -> "Check that PostgreSQL is running and that Vero can reach it. Vero keeps trying and serves as soon as it can.";
            case SYS_004 -> "Reinstall the Vero package from the release.";
            case SYS_005, SYS_007, SYS_008, SYS_009 -> "Correct the setting named above, then start Vero again.";
            case SYS_006 -> "Set SDV_AUTH_MODE=enterprise-jwt and the issuer settings (install guide, \"Sign-in\"), then start Vero again.";
            case SYS_010 -> "Set SDV_DEFAULT_TIME_ZONE to an IANA name such as America/New_York, then start Vero again.";
            case SYS_012 -> "Check that PostgreSQL is running and reachable, then start Vero again.";
            case KEY_001 -> "Remove SDV_CRYPTO_ENABLED and set the key service (see the install guide), then start Vero again.";
            default -> "Give the log line to Vero support.";
        };
    }
}
