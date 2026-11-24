package com.sdv.platform.web.schema;

import org.springframework.boot.health.contributor.Status;

/**
 * Task 0039 (owner answer to report 0038, 4): the status of a check that belongs to readiness only, the database ping
 * ({@code databaseConnection}) and the sign-in hub's keys ({@code signInKeys}).
 * <ul>
 *   <li><b>Readiness</b> ({@code /actuator/health/readiness}) lists NOT_READY in its status order and maps it to 503,
 *       so it is not ready while the database or the hub is away.</li>
 *   <li><b>Overall</b> ({@code /actuator/health}) and <b>liveness</b> don't list it, and Spring's aggregation ignores a
 *       status not in its order, so neither reports DOWN for these two: an orchestrator never restarts Vero because the
 *       hub or the database is away (Vero recovers by itself, task 0037).</li>
 * </ul>
 * The groups are set in application.yml ({@code management.endpoint.health.group.readiness.status}).
 */
public final class ReadinessOnly {
    public static final Status NOT_READY = new Status("NOT_READY", "Vero can't take work until this is back");
    /**
     * Task 0040 part 2 (master section item 2, owner answers third round): the database is there, but no connection is
     * free (pool full, or PostgreSQL has none left). Readiness orders it after NOT_READY, so it is the answer only when
     * busy is the one reason; {@link ReadinessBusyAnswer} then adds SDV-SYS-018 and Retry-After to the 503.
     */
    public static final Status BUSY = new Status("BUSY", "Vero is busy: no database connection is free just now");

    private ReadinessOnly() { }
}
