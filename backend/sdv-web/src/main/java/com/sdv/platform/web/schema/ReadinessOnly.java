package com.sdv.platform.web.schema;

import org.springframework.boot.actuate.health.Status;

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

    private ReadinessOnly() { }
}
