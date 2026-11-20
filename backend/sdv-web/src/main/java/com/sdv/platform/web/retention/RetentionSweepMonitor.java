package com.sdv.platform.web.retention;

import com.sdv.platform.application.retention.RetentionDeletionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The last cleanup run, and (task 0023) when the next one is due.
 *
 * RetentionScheduler runs on a fixed delay: the first run {@code sdv.retention.initial-delay-ms} after the scheduler
 * starts, then each run {@code sdv.retention.sweep-delay-ms} after the previous one ended. The next run is worked out
 * from those same two settings. It is null when it can't be said: a run is going on now, the scheduler hasn't
 * started, or the time worked out has already passed (the scheduler is late, or the database doesn't match this
 * version and the runs are skipped).
 */
@Component
public class RetentionSweepMonitor {
    private final AtomicReference<Status> last = new AtomicReference<>(Status.never());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Instant> schedulerStartedAt = new AtomicReference<>();
    private final Duration initialDelay;
    private final Duration sweepDelay;
    private final Clock clock;

    @Autowired
    public RetentionSweepMonitor(
            @Value(RetentionScheduler.INITIAL_DELAY_MS) String initialDelay,
            @Value(RetentionScheduler.SWEEP_DELAY_MS) String sweepDelay) {
        this(delay(initialDelay), delay(sweepDelay), Clock.systemUTC());
    }

    /**
     * The setting as the scheduler reads it: milliseconds, or an ISO-8601 duration such as PT24H. Anything else is
     * not guessed at: the next run is then left unknown (null).
     */
    static Duration delay(String setting) {
        if (setting == null || setting.isBlank()) return null;
        String s = setting.trim();
        try {
            return Duration.ofMillis(Long.parseLong(s));
        } catch (NumberFormatException notMillis) {
            try {
                return Duration.parse(s);
            } catch (java.time.format.DateTimeParseException notIso) {
                return null;
            }
        }
    }

    /** initialDelay or sweepDelay may be null (unreadable setting): the next run is then never worked out from it. */
    RetentionSweepMonitor(Duration initialDelay, Duration sweepDelay, Clock clock) {
        this.initialDelay = initialDelay;
        this.sweepDelay = sweepDelay;
        this.clock = clock;
    }

    /** Spring schedules the @Scheduled methods when the context is refreshed; the initial delay counts from then. */
    @EventListener(ContextRefreshedEvent.class)
    public void schedulerStarted() {
        schedulerStarted(clock.instant());
    }

    void schedulerStarted(Instant at) {
        schedulerStartedAt.compareAndSet(null, at);
    }

    public void started() {
        running.set(true);
    }

    public void success(Instant startedAt, Instant completedAt, RetentionDeletionService.SweepResult result) {
        last.set(new Status("SUCCESS", startedAt, completedAt, result.candidates(), result.completed(), result.failed(), null, null));
        running.set(false);
    }

    public void failure(Instant startedAt, Instant completedAt) {
        last.set(new Status("FAILED", startedAt, completedAt, 0, 0, 1, "RETENTION_SWEEP_FAILED", null));
        running.set(false);
    }

    public Status status() {
        Status s = last.get();
        return s.withNextRunAt(nextRunAt(s));
    }

    private Instant nextRunAt(Status s) {
        if (running.get()) return null;
        Instant next;
        Instant schedulerStart = schedulerStartedAt.get();
        if (s.completedAt() != null) {
            if (sweepDelay == null) return null;
            next = s.completedAt().plus(sweepDelay);
        } else if (schedulerStart != null && initialDelay != null) {
            next = schedulerStart.plus(initialDelay);
        } else {
            return null;
        }
        return next.isBefore(clock.instant()) ? null : next;
    }

    public record Status(String outcome, Instant startedAt, Instant completedAt, int candidates, int deleted, int failed,
                         String reasonCode, Instant nextRunAt) {
        static Status never() { return new Status("NEVER_RUN", null, null, 0, 0, 0, null, null); }

        Status withNextRunAt(Instant next) {
            return new Status(outcome, startedAt, completedAt, candidates, deleted, failed, reasonCode, next);
        }
    }
}
