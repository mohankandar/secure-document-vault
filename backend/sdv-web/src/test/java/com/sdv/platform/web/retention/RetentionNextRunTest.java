package com.sdv.platform.web.retention;

import com.sdv.platform.application.retention.RetentionDeletionService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0023: the next cleanup run, worked out from the scheduler's own setting (sample schedules). */
class RetentionNextRunTest {
    private static final Instant START = Instant.parse("2026-11-19T06:00:00Z");

    /** A clock the test moves forward. */
    private static final class TestClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(START);
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
        void set(Instant at) { now.set(at); }
    }

    private static final RetentionDeletionService.SweepResult THREE_DELETED = new RetentionDeletionService.SweepResult(3, 3, 0);

    @Test
    void dailySample_nextRunIsOneDayAfterTheLastRunEnded() {
        TestClock clock = new TestClock();
        RetentionSweepMonitor monitor = new RetentionSweepMonitor(Duration.ofHours(1), Duration.ofDays(1), clock);
        monitor.schedulerStarted(START);
        Instant ran = Instant.parse("2026-11-19T07:00:00Z");
        Instant ended = Instant.parse("2026-11-19T07:00:02Z");
        clock.set(ended);
        monitor.started();
        monitor.success(ran, ended, THREE_DELETED);
        clock.set(Instant.parse("2026-11-19T12:00:00Z"));
        assertEquals(Instant.parse("2026-11-20T07:00:02Z"), monitor.status().nextRunAt());
        assertEquals("SUCCESS", monitor.status().outcome());
        assertEquals(3, monitor.status().deleted());
    }

    @Test
    void beforeTheFirstRun_nextRunIsTheInitialDelayAfterTheSchedulerStarted() {
        TestClock clock = new TestClock();
        RetentionSweepMonitor monitor = new RetentionSweepMonitor(Duration.ofSeconds(15), Duration.ofMinutes(1), clock);
        monitor.schedulerStarted(START);
        clock.set(START.plusSeconds(5));
        assertEquals("NEVER_RUN", monitor.status().outcome());
        assertEquals(START.plusSeconds(15), monitor.status().nextRunAt());
    }

    @Test
    void afterAFailedRun_theNextRunIsStillOneDelayAfterItEnded() {
        TestClock clock = new TestClock();
        RetentionSweepMonitor monitor = new RetentionSweepMonitor(Duration.ofSeconds(15), Duration.ofMinutes(1), clock);
        monitor.schedulerStarted(START);
        monitor.started();
        monitor.failure(START.plusSeconds(15), START.plusSeconds(16));
        clock.set(START.plusSeconds(20));
        assertEquals(START.plusSeconds(76), monitor.status().nextRunAt());
    }

    @Test
    void nothingIsGuessed_whileARunIsGoingOn() {
        TestClock clock = new TestClock();
        RetentionSweepMonitor monitor = new RetentionSweepMonitor(Duration.ofSeconds(15), Duration.ofMinutes(1), clock);
        monitor.schedulerStarted(START);
        monitor.started();
        assertNull(monitor.status().nextRunAt());
    }

    @Test
    void nothingIsGuessed_beforeTheSchedulerStarted() {
        RetentionSweepMonitor monitor = new RetentionSweepMonitor(Duration.ofSeconds(15), Duration.ofMinutes(1), new TestClock());
        assertNull(monitor.status().nextRunAt());
    }

    @Test
    void nothingIsGuessed_whenTheTimeWorkedOutHasPassed() {
        // For example the database doesn't match this version, so every run is skipped and none is recorded.
        TestClock clock = new TestClock();
        RetentionSweepMonitor monitor = new RetentionSweepMonitor(Duration.ofSeconds(15), Duration.ofMinutes(1), clock);
        monitor.schedulerStarted(START);
        clock.set(START.plusSeconds(60));
        assertNull(monitor.status().nextRunAt());
    }

    @Test
    void nothingIsGuessed_whenTheSettingCantBeRead() {
        TestClock clock = new TestClock();
        RetentionSweepMonitor monitor = new RetentionSweepMonitor(RetentionSweepMonitor.delay("soon"), RetentionSweepMonitor.delay(""), clock);
        monitor.schedulerStarted(START);
        assertNull(monitor.status().nextRunAt());
        monitor.started();
        monitor.success(START, START.plusSeconds(1), THREE_DELETED);
        assertNull(monitor.status().nextRunAt());
    }

    @Test
    void theSettingIsReadAsTheSchedulerReadsIt_millisecondsOrAnIsoDuration() {
        assertEquals(Duration.ofMinutes(1), RetentionSweepMonitor.delay("60000"));
        assertEquals(Duration.ofHours(24), RetentionSweepMonitor.delay("PT24H"));
        assertNull(RetentionSweepMonitor.delay("every day"));
        assertNull(RetentionSweepMonitor.delay(null));
    }

    @Test
    void theDefaultSchedule_isTheSchedulersOwnSetting() {
        // The monitor reads the very same placeholders the @Scheduled annotation uses.
        assertEquals("${sdv.retention.initial-delay-ms:15000}", RetentionScheduler.INITIAL_DELAY_MS);
        assertEquals("${sdv.retention.sweep-delay-ms:60000}", RetentionScheduler.SWEEP_DELAY_MS);
        RetentionSweepMonitor monitor = new RetentionSweepMonitor("15000", "60000");
        monitor.schedulerStarted(Instant.now());
        Instant next = monitor.status().nextRunAt();
        assertNotNull(next);
        assertTrue(Duration.between(Instant.now(), next).compareTo(Duration.ofSeconds(15)) <= 0);
    }
}
