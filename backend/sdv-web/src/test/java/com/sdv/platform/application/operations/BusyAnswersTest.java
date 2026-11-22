package com.sdv.platform.application.operations;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0040 part 2 (owner answers 3 and 4 to report 0040 part 2): the busy answer's Retry-After is a setting
 * (sdv.busy.retry-after-seconds, SDV_BUSY_RETRY_AFTER_SECONDS), a whole number from 1 to 300, default 5; anything
 * else stops the start with SDV-SYS-005. Busy answers are counted for Problems: the last hour and since start.
 */
class BusyAnswersTest {
    /** A clock the test moves by hand. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-22T10:00:30Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void plus(Duration d) { now = now.plus(d); }
    }

    @Test
    void theDefaultIs5Seconds() {
        assertEquals(5, BusyAnswers.DEFAULT_RETRY_AFTER_SECONDS);
        assertEquals(5, new BusyAnswers().retryAfterSeconds());
        assertEquals(5, new BusyAnswers("5").retryAfterSeconds());
    }

    @Test
    void aWholeNumberFrom1To300IsTaken() {
        assertEquals(1, new BusyAnswers("1").retryAfterSeconds());
        assertEquals(30, new BusyAnswers(" 30 ").retryAfterSeconds());
        assertEquals(300, new BusyAnswers("300").retryAfterSeconds());
    }

    @Test
    void anythingElseStopsTheStart() {
        for (String bad : new String[]{"", " ", "0", "-1", "301", "999", "1000", "1e2", "2.5", "5s", "05", "abc"}) {
            StartupFailure failure = assertThrows(StartupFailure.class, () -> new BusyAnswers(bad), "\"" + bad + "\"");
            assertEquals(SdvErrorCode.SYS_005, failure.errorCode());
            assertTrue(failure.getMessage().contains("SDV_BUSY_RETRY_AFTER_SECONDS"), failure.getMessage());
        }
        assertThrows(StartupFailure.class, () -> new BusyAnswers((String) null));
    }

    @Test
    void theCountKeepsTheLastHourAndEverythingSinceStart() {
        TestClock clock = new TestClock();
        BusyAnswers busy = new BusyAnswers("5", clock);
        assertEquals(new BusyAnswers.Count(0, 0, null, 5), busy.count());

        busy.record();
        busy.record();
        clock.plus(Duration.ofMinutes(30));
        busy.record();
        assertEquals(3, busy.count().lastHour());
        assertEquals(clock.now, busy.count().lastAt());

        // At 11:00:30 the 10:00 minute has left the hour (the first two); 10:30 stays.
        clock.plus(Duration.ofMinutes(30));
        assertEquals(1, busy.count().lastHour(), "10:00 is out of the hour at 11:00");
        assertEquals(3, busy.count().sinceStart());

        // A slot reused an hour later starts again from 0.
        busy.record();
        assertEquals(2, busy.count().lastHour());
        clock.plus(Duration.ofHours(5));
        assertEquals(0, busy.count().lastHour());
        assertEquals(4, busy.count().sinceStart());
    }
}
