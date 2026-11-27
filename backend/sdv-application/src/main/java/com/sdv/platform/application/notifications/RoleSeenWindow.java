package com.sdv.platform.application.notifications;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Task 0100 (VSEC-203, answer Q-5): how long a sign-in confirms a list role. A person not seen with the role at a
 * sign-in for this many days ({@code sdv.notifications.role-seen-days}, {@code SDV_NOTIFICATIONS_ROLE_SEEN_DAYS}, 7 to 90,
 * 30 by default) counts as not confirmed: their list entries get nothing, so someone whose role was taken away and who
 * never signs in again stops getting messages too. Anything outside 7 to 90 stops the start (SDV-SYS-005).
 */
@Component
public final class RoleSeenWindow {
    public static final int DEFAULT_DAYS = 30;
    static final String INVALID = "sdv.notifications.role-seen-days (SDV_NOTIFICATIONS_ROLE_SEEN_DAYS) must be a whole number of days from 7 to 90.";

    private final Duration window;
    private final Clock clock;

    @Autowired
    public RoleSeenWindow(@Value("${sdv.notifications.role-seen-days:" + DEFAULT_DAYS + "}") String days) {
        this(parse(days), Clock.systemUTC());
    }

    public RoleSeenWindow(int days, Clock clock) {
        if (days < 7 || days > 90) throw new StartupFailure(SdvErrorCode.SYS_005, INVALID);
        this.window = Duration.ofDays(days);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private static int parse(String days) {
        try {
            return Integer.parseInt(days == null ? "" : days.trim());
        } catch (NumberFormatException ex) {
            throw new StartupFailure(SdvErrorCode.SYS_005, INVALID);
        }
    }

    /** Sign-ins at or after this confirm a role now. */
    public Instant since() {
        return clock.instant().minus(window);
    }

    public int days() {
        return (int) window.toDays();
    }
}
