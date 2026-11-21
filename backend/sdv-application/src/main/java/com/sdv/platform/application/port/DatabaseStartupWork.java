package com.sdv.platform.application.port;

/**
 * Task 0037: work Vero does once at start that reads or writes the database. It waits for the start-up schema
 * check, like the rest of Vero: it runs as soon as the database matches this version, at start or later when the
 * database comes back without a restart. While the database doesn't match it never runs, so it can't stop the start
 * with a raw database error (SDV-SYS-012).
 * <p>
 * Fail closed: if the work itself fails, Vero doesn't start (or, when the database came back after the start, Vero
 * stops), with SDV-SYS-012 and the exact cause in the log.
 */
public interface DatabaseStartupWork {
    /** A short name for the log, for example "Restricted Steward sign-in group check". */
    String name();

    void run();
}
