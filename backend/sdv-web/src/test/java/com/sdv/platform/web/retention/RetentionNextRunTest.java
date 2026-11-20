package com.sdv.platform.web.retention;

import com.sdv.platform.application.retention.RetentionDeletionService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0023 and its follow-up: cleanup runs once a day at a fixed time (default 02:00 server time, set with
 * sdv.retention.daily-at), and Health and Overview show the next run from that same setting.
 */
class RetentionNextRunTest {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final RetentionDeletionService.SweepResult THREE_DELETED = new RetentionDeletionService.SweepResult(3, 3, 0);

    /** A clock the test moves forward. */
    private static final class TestClock extends Clock {
        final AtomicReference<Instant> now;
        TestClock(Instant start) { now = new AtomicReference<>(start); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
        void set(Instant at) { now.set(at); }
    }

    private static RetentionSchedule daily(String at, ZoneId zone) { return new RetentionSchedule(at, "", "", "", zone); }

    @Test
    void theDefault_isOnceADayAt0200ServerTime() {
        RetentionSchedule s = new RetentionSchedule(RetentionSchedule.DEFAULT_DAILY_AT, "", "", "", BERLIN);
        assertEquals("02:00", RetentionSchedule.DEFAULT_DAILY_AT);
        assertEquals("0 0 2 * * *", s.cron());
        // 12:00 Berlin on 19 Nov -> 02:00 Berlin on 20 Nov (01:00 UTC).
        assertEquals(Instant.parse("2026-11-20T01:00:00Z"), s.next(Instant.parse("2026-11-19T11:00:00Z")));
        // 01:30 Berlin -> 02:00 the same day.
        assertEquals(Instant.parse("2026-11-19T01:00:00Z"), s.next(Instant.parse("2026-11-19T00:30:00Z")));
        // Exactly at 02:00 the next run is the following day's.
        assertEquals(Instant.parse("2026-11-20T01:00:00Z"), s.next(Instant.parse("2026-11-19T01:00:00Z")));
    }

    @Test
    void theTimeIsSettable_andRunsOnceADay() {
        RetentionSchedule s = daily("23:30", ZoneOffset.UTC);
        assertEquals("0 30 23 * * *", s.cron());
        Instant first = s.next(Instant.parse("2026-11-19T12:00:00Z"));
        assertEquals(Instant.parse("2026-11-19T23:30:00Z"), first);
        assertEquals(Instant.parse("2026-11-20T23:30:00Z"), s.next(first));
    }

    @Test
    void theSchedulersTrigger_firesAtTheSameTimeTheMonitorShows() {
        RetentionSchedule s = daily("07:00", BERLIN);
        Instant now = Instant.parse("2026-11-19T12:00:00Z");
        SimpleTriggerContext ctx = new SimpleTriggerContext(Clock.fixed(now, ZoneOffset.UTC));
        assertEquals(s.next(now), s.trigger().nextExecution(ctx));
    }

    @Test
    void summerTime_keepsTheSameLocalTime() {
        RetentionSchedule s = daily("02:30", BERLIN);
        // Winter (UTC+1) and summer (UTC+2): 02:30 server time both times.
        assertEquals(Instant.parse("2026-03-20T01:30:00Z"), s.next(Instant.parse("2026-03-19T12:00:00Z")));
        Instant summer = s.next(Instant.parse("2026-04-01T12:00:00Z"));
        assertEquals(Instant.parse("2026-04-02T00:30:00Z"), summer);
        assertEquals(LocalTime.of(2, 30), summer.atZone(BERLIN).toLocalTime());
    }

    @Test
    void aTimeThatIsntHHmm_stopsTheServer() {
        for (String bad : new String[]{"2", "2:00", "02.00", "25:00", "02:60", "2am", "02:00:00", " ", ""}) {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> daily(bad, ZoneOffset.UTC), bad);
            assertTrue(ex.getMessage().contains("sdv.retention.daily-at"), ex.getMessage());
        }
        assertThrows(IllegalStateException.class, () -> daily(null, ZoneOffset.UTC));
        assertEquals(LocalTime.of(2, 0), RetentionSchedule.dailyTime(" 02:00 "));
    }

    @Test
    void theOldFixedDelaySettings_stopTheServerRatherThanBeingIgnored() {
        IllegalStateException a = assertThrows(IllegalStateException.class, () -> new RetentionSchedule("02:00", "", "1000", "", ZoneOffset.UTC));
        assertTrue(a.getMessage().contains("sdv.retention.initial-delay-ms"));
        assertThrows(IllegalStateException.class, () -> new RetentionSchedule("02:00", "", "", "60000", ZoneOffset.UTC));
    }

    @Test
    void theCheckScriptOverride_replacesTheDailyTime_andAnUnreadableOneStopsTheServer() {
        RetentionSchedule everySecond = new RetentionSchedule("02:00", " * * * * * * ", "", "", ZoneOffset.UTC);
        assertEquals("* * * * * *", everySecond.cron());
        assertEquals(Instant.parse("2026-11-19T12:00:01Z"), everySecond.next(Instant.parse("2026-11-19T12:00:00Z")));
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new RetentionSchedule("02:00", "every day", "", "", ZoneOffset.UTC));
        assertTrue(ex.getMessage().contains("sdv.retention.cron"));
    }

    @Test
    void beforeAndAfterARun_theNextRunIsTheNextDailyTime() {
        TestClock clock = new TestClock(Instant.parse("2026-11-19T06:00:00Z"));
        RetentionSweepMonitor monitor = new RetentionSweepMonitor(daily("07:00", ZoneOffset.UTC), () -> true, clock);
        assertEquals("NEVER_RUN", monitor.status().outcome());
        assertEquals(Instant.parse("2026-11-19T07:00:00Z"), monitor.status().nextRunAt());

        clock.set(Instant.parse("2026-11-19T07:00:00Z"));
        monitor.started();
        assertNull(monitor.status().nextRunAt(), "nothing is said while a run is going on");
        monitor.success(Instant.parse("2026-11-19T07:00:00Z"), Instant.parse("2026-11-19T07:00:02Z"), THREE_DELETED);
        clock.set(Instant.parse("2026-11-19T07:00:02Z"));
        assertEquals("SUCCESS", monitor.status().outcome());
        assertEquals(3, monitor.status().deleted());
        assertEquals(Instant.parse("2026-11-20T07:00:00Z"), monitor.status().nextRunAt(), "a long run doesn't push the next one later");
    }

    @Test
    void afterAFailedRun_theNextRunIsStillTheNextDailyTime() {
        TestClock clock = new TestClock(Instant.parse("2026-11-19T02:00:01Z"));
        RetentionSweepMonitor monitor = new RetentionSweepMonitor(daily("02:00", ZoneOffset.UTC), () -> true, clock);
        monitor.started();
        monitor.failure(Instant.parse("2026-11-19T02:00:00Z"), Instant.parse("2026-11-19T02:00:01Z"));
        assertEquals("FAILED", monitor.status().outcome());
        assertEquals(Instant.parse("2026-11-20T02:00:00Z"), monitor.status().nextRunAt());
    }

    @Test
    void nothingIsSaid_whenTheDatabaseDoesntMatchThisVersion() {
        // The scheduler skips every run then (DatabaseSchemaCheck), so no next run is promised.
        AtomicBoolean current = new AtomicBoolean(false);
        RetentionSweepMonitor monitor = new RetentionSweepMonitor(daily("02:00", ZoneOffset.UTC), current::get, new TestClock(Instant.parse("2026-11-19T12:00:00Z")));
        assertNull(monitor.status().nextRunAt());
        current.set(true);
        assertEquals(Instant.parse("2026-11-20T02:00:00Z"), monitor.status().nextRunAt());
    }
}
