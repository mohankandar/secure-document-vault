package com.sdv.platform.application.operations;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Task 0040 part 2 (owner answers 3 and 4 to report 0040 part 2): the busy answer (503 SDV-SYS-018).
 * <ul>
 *   <li><b>Retry-After</b>: the seconds the answer tells the caller to wait, setting
 *       {@code sdv.busy.retry-after-seconds} ({@code SDV_BUSY_RETRY_AFTER_SECONDS}), a whole number from 1 to
 *       {@value #MAX_RETRY_AFTER_SECONDS}, default {@value #DEFAULT_RETRY_AFTER_SECONDS}. Anything else stops the start
 *       (SDV-SYS-005). The database pool's own wait is not changed by it.</li>
 *   <li><b>The count</b>: how many busy answers Vero gave in the last {@value #WINDOW_MINUTES} minutes and since it
 *       started, so sustained busy shows in Problems for Tenant IT (and Platform operations). Kept in memory, for this
 *       Vero process: a busy answer means the database has no connection free, so nothing is written there.</li>
 * </ul>
 */
@Component
public class BusyAnswers {
    public static final int DEFAULT_RETRY_AFTER_SECONDS = 5;
    public static final int MAX_RETRY_AFTER_SECONDS = 300;
    /** The count's window: per minute, for the last hour. */
    public static final int WINDOW_MINUTES = 60;

    private final int retryAfterSeconds;
    private final Clock clock;
    /** Slot i holds the count for the epoch minute in minuteOf[i]; a slot from an older minute counts as 0. */
    private final long[] minuteOf = new long[WINDOW_MINUTES];
    private final long[] perMinute = new long[WINDOW_MINUTES];
    private long sinceStart;
    private Instant lastAt;

    /** The defaults: Retry-After {@value #DEFAULT_RETRY_AFTER_SECONDS} seconds, the system clock. */
    public BusyAnswers() {
        this(String.valueOf(DEFAULT_RETRY_AFTER_SECONDS), Clock.systemUTC());
    }

    @Autowired
    public BusyAnswers(@Value("${sdv.busy.retry-after-seconds:" + DEFAULT_RETRY_AFTER_SECONDS + "}") String retryAfter) {
        this(retryAfter, Clock.systemUTC());
    }

    BusyAnswers(String retryAfter, Clock clock) {
        this.retryAfterSeconds = retryAfterSeconds(retryAfter);
        this.clock = clock;
        java.util.Arrays.fill(minuteOf, Long.MIN_VALUE);
    }

    static int retryAfterSeconds(String value) {
        String v = value == null ? "" : value.strip();
        if (v.matches("[1-9][0-9]{0,2}")) {
            int parsed = Integer.parseInt(v);
            if (parsed <= MAX_RETRY_AFTER_SECONDS) return parsed;
        }
        throw new StartupFailure(SdvErrorCode.SYS_005, "sdv.busy.retry-after-seconds (SDV_BUSY_RETRY_AFTER_SECONDS) "
                + "must be a whole number of seconds from 1 to " + MAX_RETRY_AFTER_SECONDS + "; it is \""
                + (v.length() > 20 ? v.substring(0, 20) : v) + "\".");
    }

    /** The seconds in the busy answer's Retry-After header. */
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** One busy answer was given now. */
    public synchronized void record() {
        Instant now = clock.instant();
        long minute = Math.floorDiv(now.getEpochSecond(), 60);
        int slot = (int) Math.floorMod(minute, WINDOW_MINUTES);
        if (minuteOf[slot] != minute) {
            minuteOf[slot] = minute;
            perMinute[slot] = 0;
        }
        perMinute[slot]++;
        sinceStart++;
        lastAt = now;
    }

    /** The count now. */
    public synchronized Count count() {
        long minute = Math.floorDiv(clock.instant().getEpochSecond(), 60);
        long lastHour = 0;
        for (int i = 0; i < WINDOW_MINUTES; i++) {
            if (minuteOf[i] > minute - WINDOW_MINUTES && minuteOf[i] <= minute) lastHour += perMinute[i];
        }
        return new Count(lastHour, sinceStart, lastAt, retryAfterSeconds);
    }

    /**
     * Busy answers (503 SDV-SYS-018) from this Vero process: in the last hour, since it started, the last one (null
     * if none), and the Retry-After seconds they carried.
     */
    public record Count(long lastHour, long sinceStart, Instant lastAt, int retryAfterSeconds) {
        public static final Count NONE = new Count(0, 0, null, DEFAULT_RETRY_AFTER_SECONDS);
    }
}
