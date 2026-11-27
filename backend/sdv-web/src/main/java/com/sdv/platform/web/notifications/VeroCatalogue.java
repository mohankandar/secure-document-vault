package com.sdv.platform.web.notifications;

import com.documentplatform.licensing.spring.notifications.LicensingEvents;
import com.documentplatform.notifications.ConditionTiming;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.EventKind;
import com.documentplatform.notifications.EventType;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.Schedule;
import com.sdv.platform.application.notifications.VeroEvent;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Task 0081: Vero's event catalogue for platform-core's notifications, plus platform-core's licensing events.
 * <p>
 * <b>Two event types for an event with an identifier</b> (plan 0069 section 13, answer 4): the module puts a record's
 * identifier in every channel of its event type, so Vero registers the e-mail one ({@link VeroEvent#id()}, which may
 * carry the identifier) and its Teams and Slack twin ({@link VeroEvent#channelId()}, which never does). The twin is
 * off until the admin picks a Teams or Slack channel for the event; Vero's settings never give the e-mail type a
 * channel ({@link NotificationSettingsService}).
 * <p>
 * Defaults: critical ones Immediately to the IT Admin; security events (incidents, holds) Immediately; approvals every
 * 4 hours; roles withheld and unidentified attempts every hour; the cleanup's failures once a day at 08:00.
 */
public final class VeroCatalogue {
    public static final RoleId IT_ADMIN = RoleId.of("IT_ADMIN");
    public static final RoleId BUSINESS_ADMIN = RoleId.of("BUSINESS_ADMIN");
    public static final LocalTime DAILY_AT = LocalTime.of(8, 0);

    private VeroCatalogue() { }

    public static EventCatalogue build() {
        EventCatalogue.Builder catalogue = EventCatalogue.builder(IT_ADMIN);
        for (VeroEvent event : VeroEvent.values()) types(event).forEach(catalogue::register);
        return LicensingEvents.register(catalogue, IT_ADMIN, BUSINESS_ADMIN).build();
    }

    /** The event's type, and for an event with an identifier its Teams and Slack twin. */
    static List<EventType> types(VeroEvent event) {
        List<EventType> types = new ArrayList<>();
        types.add(type(event, event.id(), false));
        event.channelId().ifPresent(id -> types.add(type(event, id, true)));
        return types;
    }

    private static EventType type(VeroEvent event, String id, boolean channelTwin) {
        EventType.Builder b = EventType.builder(id, event.label(),
                event.kind() == VeroEvent.Kind.BUSINESS ? EventKind.BUSINESS : EventKind.TECHNICAL);
        RoleId role = event.audience() == VeroEvent.Audience.IT_ADMIN ? IT_ADMIN : BUSINESS_ADMIN;
        if (event.critical()) {
            b.critical().recipients(IT_ADMIN);
        } else {
            b.schedule(schedule(event)).recipients(role);
        }
        if (event.continuous()) {
            b.continuous(event.critical() ? new ConditionTiming(Duration.ofMinutes(5), Duration.ofHours(4)) : ConditionTiming.DEFAULT);
        }
        if (channelTwin) b.offByDefault();
        for (String count : event.counts()) b.count(count, countLabel(count));
        for (String time : event.times()) b.time(time, "Decide by");
        return b.build();
    }

    static Schedule schedule(VeroEvent event) {
        return switch (event) {
            case APPROVAL_WAITING -> Schedule.every4Hours();
            case ROLES_WITHHELD, UNIDENTIFIED_ATTEMPTS -> Schedule.everyHour();
            case CLEANUP_FAILURES -> Schedule.onceADay(DAILY_AT);
            default -> Schedule.immediately();
        };
    }

    private static String countLabel(String count) {
        return switch (count) {
            case VeroEvent.COUNT_DOCUMENTS -> "Documents";
            case VeroEvent.COUNT_DELIVERIES -> "Deliveries";
            case VeroEvent.COUNT_ATTEMPTS -> "Attempts";
            case VeroEvent.COUNT_FAILED -> "Not deleted";
            case VeroEvent.COUNT_PERCENT_USED -> "Percent used";
            case VeroEvent.COUNT_PEOPLE -> "People";
            default -> throw new IllegalArgumentException("no label for count " + count);
        };
    }
}
