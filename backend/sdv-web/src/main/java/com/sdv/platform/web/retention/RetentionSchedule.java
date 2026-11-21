package com.sdv.platform.web.retention;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * When automatic cleanup runs (task 0023 follow-up, the owner's answer 1): once a day at a fixed time, by default
 * 02:00 server time, set in the server configuration with {@code sdv.retention.daily-at} (HH:mm, 24-hour). There is
 * no screen for it.
 *
 * {@code sdv.retention.cron} is for the check scripts only (they need a run within seconds): a Spring cron expression
 * that replaces daily-at when it is set. It is empty in a normal installation.
 *
 * Nothing is guessed: a time that isn't HH:mm, a cron expression Spring can't read, or one of the old fixed-delay
 * settings ({@code sdv.retention.initial-delay-ms}, {@code sdv.retention.sweep-delay-ms}) still being set stops the
 * server at start-up with a message naming the setting.
 */
@Component
public class RetentionSchedule {
    static final String DEFAULT_DAILY_AT = "02:00";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);

    private final String cron;
    private final CronExpression expression;
    private final ZoneId zone;

    @Autowired
    public RetentionSchedule(
            @Value("${sdv.retention.daily-at:" + DEFAULT_DAILY_AT + "}") String dailyAt,
            @Value("${sdv.retention.cron:}") String cronOverride,
            @Value("${sdv.retention.initial-delay-ms:}") String oldInitialDelay,
            @Value("${sdv.retention.sweep-delay-ms:}") String oldSweepDelay) {
        this(dailyAt, cronOverride, oldInitialDelay, oldSweepDelay, ZoneId.systemDefault());
    }

    RetentionSchedule(String dailyAt, String cronOverride, String oldInitialDelay, String oldSweepDelay, ZoneId zone) {
        if (notBlank(oldInitialDelay) || notBlank(oldSweepDelay)) {
            throw new StartupFailure(SdvErrorCode.SYS_009, "sdv.retention.initial-delay-ms and sdv.retention.sweep-delay-ms "
                    + "(SDV_RETENTION_INITIAL_DELAY_MS, SDV_RETENTION_SWEEP_DELAY_MS) are no longer used. "
                    + "Cleanup runs once a day: remove them and set sdv.retention.daily-at (HH:mm) if 02:00 doesn't suit.");
        }
        this.zone = zone;
        if (notBlank(cronOverride)) {
            this.cron = cronOverride.trim();
            try {
                this.expression = CronExpression.parse(cron);
            } catch (IllegalArgumentException ex) {
                throw new StartupFailure(SdvErrorCode.SYS_009, "sdv.retention.cron (SDV_RETENTION_CRON) is not a cron expression Spring can read: " + ex.getMessage());
            }
        } else {
            LocalTime at = dailyTime(dailyAt);
            this.cron = "0 " + at.getMinute() + " " + at.getHour() + " * * *";
            this.expression = CronExpression.parse(cron);
        }
    }

    /** HH:mm, 24-hour, for example 02:00 or 23:30. Anything else is refused. */
    static LocalTime dailyTime(String setting) {
        if (setting == null || setting.isBlank()) {
            throw new StartupFailure(SdvErrorCode.SYS_009, "sdv.retention.daily-at (SDV_RETENTION_DAILY_AT) is empty. Set a time as HH:mm, for example 02:00.");
        }
        try {
            return LocalTime.parse(setting.trim(), HH_MM);
        } catch (DateTimeParseException ex) {
            throw new StartupFailure(SdvErrorCode.SYS_009, "sdv.retention.daily-at (SDV_RETENTION_DAILY_AT) must be a time as HH:mm (24-hour), for example 02:00. It is \"" + setting + "\".");
        }
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /** The Spring cron expression the scheduler uses, for example "0 0 2 * * *". */
    public String cron() { return cron; }

    public ZoneId zone() { return zone; }

    /** The trigger RetentionScheduler registers: the same expression and zone as next(). */
    CronTrigger trigger() { return new CronTrigger(cron, zone); }

    /** The first run strictly after {@code after}, or null if the expression has none. */
    public Instant next(Instant after) {
        ZonedDateTime n = expression.next(after.atZone(zone));
        return n == null ? null : n.toInstant();
    }
}
