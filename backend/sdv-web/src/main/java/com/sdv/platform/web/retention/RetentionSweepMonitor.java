package com.sdv.platform.web.retention;

import com.sdv.platform.application.retention.RetentionDeletionService;
import com.sdv.platform.web.schema.DatabaseSchemaCheck;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * The last cleanup run, and (task 0023) when the next one is due.
 *
 * Cleanup runs once a day at a fixed time (RetentionSchedule, {@code sdv.retention.daily-at}, default 02:00 server
 * time). The next run is the schedule's next time after now, from the same setting the scheduler uses. It is null when
 * it can't be said: a run is going on now, or the database doesn't match this version, so the runs are skipped.
 */
@Component
public class RetentionSweepMonitor {
    private final AtomicReference<Status> last = new AtomicReference<>(Status.never());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final RetentionSchedule schedule;
    private final BooleanSupplier schemaCurrent;
    private final Clock clock;

    @Autowired
    public RetentionSweepMonitor(RetentionSchedule schedule, DatabaseSchemaCheck schema) {
        this(schedule, schema::current, Clock.systemUTC());
    }

    RetentionSweepMonitor(RetentionSchedule schedule, BooleanSupplier schemaCurrent, Clock clock) {
        this.schedule = schedule;
        this.schemaCurrent = schemaCurrent;
        this.clock = clock;
    }

    public void started() {
        running.set(true);
    }

    public void success(Instant startedAt, Instant completedAt, RetentionDeletionService.SweepResult result) {
        last.set(new Status("SUCCESS", startedAt, completedAt, result.candidates(), result.completed(), result.failed(),
                result.heldSkipped(), null, null));
        running.set(false);
    }

    public void failure(Instant startedAt, Instant completedAt) {
        last.set(new Status("FAILED", startedAt, completedAt, 0, 0, 1, 0, "RETENTION_SWEEP_FAILED", null));
        running.set(false);
    }

    public Status status() {
        Status s = last.get();
        return s.withNextRunAt(nextRunAt());
    }

    private Instant nextRunAt() {
        if (running.get() || !schemaCurrent.getAsBoolean()) return null;
        return schedule.next(clock.instant());
    }

    /** heldSkipped (task 0027): documents due for deletion that the run kept because they are on legal hold. */
    public record Status(String outcome, Instant startedAt, Instant completedAt, int candidates, int deleted, int failed,
                         long heldSkipped, String reasonCode, Instant nextRunAt) {
        static Status never() { return new Status("NEVER_RUN", null, null, 0, 0, 0, 0, null, null); }

        Status withNextRunAt(Instant next) {
            return new Status(outcome, startedAt, completedAt, candidates, deleted, failed, heldSkipped, reasonCode, next);
        }
    }
}
