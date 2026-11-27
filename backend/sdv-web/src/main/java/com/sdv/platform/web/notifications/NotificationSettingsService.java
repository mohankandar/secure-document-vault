package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.EventKind;
import com.documentplatform.notifications.EventSettings;
import com.documentplatform.notifications.EventType;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.Frequency;
import com.documentplatform.notifications.NotificationException;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Schedule;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.TenantSettings;
import com.documentplatform.notifications.spring.NotificationsProperties;
import com.sdv.platform.application.notifications.VeroEvent;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;

import java.net.URI;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Task 0081: the two settings screens over platform-core's {@link NotificationSettings}.
 * <ul>
 *   <li><b>IT › Notifications</b>: the channels this install has (mail server, Teams and Slack destinations from the
 *       install config, each by the path of its secret, never the secret), their health, and the app address the
 *       links are made under ({@link #connect}); the technical events for the whole customer.</li>
 *   <li><b>Business administration › Notifications</b>: per entity, which business events go, how often, by e-mail
 *       and to which Teams or Slack channels.</li>
 * </ul>
 * An event with an identifier is one row on the screen and two event types underneath ({@link VeroCatalogue}): "by
 * e-mail" switches the e-mail type, the channels switch its twin. The e-mail type is never given a Teams or Slack
 * destination, so the identifier never reaches them.
 */
public class NotificationSettingsService {
    /** The id of the install's one e-mail destination in the tenant settings. */
    public static final DestinationId EMAIL = DestinationId.of("email");

    private final NotificationSettings settings;
    private final NotificationsProperties config;
    private final java.util.function.Supplier<Map<String, Map<String, Object>>> health;

    /** @param health the destinations of the module's health entry (id to state, code, reason, lastAttempt) */
    public NotificationSettingsService(NotificationSettings settings, NotificationsProperties config,
                                       java.util.function.Supplier<Map<String, Map<String, Object>>> health) {
        this.settings = settings;
        this.config = config;
        this.health = health;
    }

    // ------------------------------------------------------------------------------------------- channels (IT)

    /** state: Working, Having problems, Down, Not used yet. code and reason: of the last failure (fixed texts). */
    public record Destination(String id, String channel, String secretPath, String state, String code, String reason,
                              Instant lastAttempt) { }

    /** mailAuth: PASSWORD or OAUTH2_MICROSOFT, null without a mail server. */
    public record Channels(String appAddress, boolean connected, boolean mailConfigured, String mailAuth,
                           String mailSecretPath, List<Destination> destinations) { }

    public Channels channels(TenantId tenant) {
        Optional<TenantSettings> t = settings.tenant(tenant);
        Map<String, Map<String, Object>> statuses = health.get();
        List<Destination> out = new ArrayList<>();
        configured().forEach((id, kind) -> {
            Map<String, Object> s = statuses.getOrDefault(id.value(), Map.of());
            out.add(new Destination(id.value(), kind.name(), secretPath(id, kind),
                    String.valueOf(s.getOrDefault("state", "Not used yet")), text(s.get("code")), text(s.get("reason")),
                    s.get("lastAttempt") == null ? null : Instant.parse(s.get("lastAttempt").toString())));
        });
        boolean mail = mailConfigured();
        String auth = mail ? Optional.ofNullable(config.getMail().getAuth()).orElse("PASSWORD").toUpperCase(java.util.Locale.ROOT) : null;
        String mailSecret = !mail ? null : "OAUTH2_MICROSOFT".equals(auth) ? config.getMail().getOauth().getCredentialSecret()
                : config.getMail().getPasswordSecret();
        return new Channels(t.map(x -> x.appBaseUrl().toString()).orElse(null), t.isPresent(), mail, auth, mailSecret, out);
    }

    /**
     * Connects every channel of the install config to the customer, under this app address (the address people open
     * Vero on; links are made under it). The zone given is the module's own fallback; identity's zones win.
     */
    public Channels connect(TenantId tenant, String appAddress, ZoneId fallbackZone) {
        URI address;
        try {
            address = URI.create(appAddress == null ? "" : appAddress.trim());
            new TenantSettings(address, fallbackZone, Map.of());
        } catch (RuntimeException ex) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.NTF_005,
                    "Give the address people open Vero on, such as https://vero.example.com, without a path's query or #.");
        }
        settings.connectChannels(tenant, new TenantSettings(address, fallbackZone, configured()));
        return channels(tenant);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean mailConfigured() {
        NotificationsProperties.Mail m = config.getMail();
        boolean host = m.getHost() != null && !m.getHost().isBlank();
        return host || "OAUTH2_MICROSOFT".equalsIgnoreCase(m.getAuth());
    }

    /** The install's destinations, by id: the one e-mail destination, then Teams and Slack, sorted. */
    Map<DestinationId, ChannelKind> configured() {
        Map<DestinationId, ChannelKind> out = new LinkedHashMap<>();
        if (mailConfigured()) out.put(EMAIL, ChannelKind.EMAIL);
        new TreeMap<>(config.getTeams()).keySet().forEach(id -> out.put(DestinationId.of(id), ChannelKind.TEAMS));
        new TreeMap<>(config.getSlack()).keySet().forEach(id -> out.put(DestinationId.of(id), ChannelKind.SLACK));
        return out;
    }

    private String secretPath(DestinationId id, ChannelKind kind) {
        return switch (kind) {
            case EMAIL -> null;
            case TEAMS -> config.getTeams().get(id.value());
            case SLACK -> config.getSlack().get(id.value());
        };
    }

    // ------------------------------------------------------------------------------------------------- events

    /**
     * One row of a settings screen. frequency: IMMEDIATELY, EVERY_HOUR, EVERY_4_HOURS, ONCE_A_DAY (dailyAt "HH:mm").
     * email: goes by e-mail. channels: the Teams and Slack destinations it goes to. withIdentifier: its e-mail may
     * name the record (never for a Highly Confidential one).
     */
    public record EventRow(String id, String name, boolean critical, boolean continuous, boolean enabled, String frequency,
                           String dailyAt, boolean email, List<String> channels, boolean withIdentifier) { }

    public record Change(Boolean enabled, String frequency, String dailyAt, Boolean email, List<String> channels) { }

    /** The technical events (IT, the whole customer) or an entity's business events. */
    public List<EventRow> events(TenantId tenant, EntityId entity) {
        EventKind kind = entity == null ? EventKind.TECHNICAL : EventKind.BUSINESS;
        Set<String> twins = twinIds();
        List<EventRow> rows = new ArrayList<>();
        for (EventType type : settings.catalogue().all()) {
            if (type.kind() != kind || twins.contains(type.id().value())) continue;
            rows.add(row(tenant, entity, type));
        }
        return rows;
    }

    public EventRow change(TenantId tenant, EntityId entity, String eventId, Change change) {
        EventKind kind = entity == null ? EventKind.TECHNICAL : EventKind.BUSINESS;
        EventType type = settings.catalogue().find(eventTypeId(eventId))
                .filter(t -> t.kind() == kind && !twinIds().contains(t.id().value()))
                .orElseThrow(() -> new CodedRefusal.BadRequest(SdvErrorCode.NTF_005, "There is no such event on this screen."));
        EventRow now = row(tenant, entity, type);
        boolean enabled = change.enabled() == null ? now.enabled() : change.enabled();
        Schedule schedule = schedule(change.frequency() == null ? now.frequency() : change.frequency(),
                change.frequency() == null && change.dailyAt() == null ? now.dailyAt() : change.dailyAt());
        boolean email = change.email() == null ? now.email() : change.email();
        Set<DestinationId> channels = channels(tenant, change.channels() == null ? now.channels() : change.channels());
        Optional<String> twin = twinOf(type);
        try {
            if (twin.isPresent()) {
                EventSettings mail = effective(tenant, entity, type.id()).withEnabled(enabled && email).withSchedule(schedule)
                        .withDestinations();
                EventTypeId twinId = EventTypeId.of(twin.get());
                EventSettings channelSettings = effective(tenant, entity, twinId).withEnabled(enabled && !channels.isEmpty())
                        .withSchedule(schedule).withDestinations(channels.toArray(DestinationId[]::new));
                save(tenant, entity, type.id(), mail);
                save(tenant, entity, twinId, channelSettings);
            } else {
                Set<DestinationId> all = new LinkedHashSet<>();
                if (email && configured().containsKey(EMAIL)) all.add(EMAIL);
                all.addAll(channels);
                boolean on = enabled && (email || !channels.isEmpty());
                if (type.critical()) on = true;
                // Nothing chosen goes to the e-mail destination (the module's rule), so "by e-mail" alone needs none named.
                DestinationId[] named = email && channels.isEmpty() ? new DestinationId[0] : all.toArray(DestinationId[]::new);
                save(tenant, entity, type.id(), effective(tenant, entity, type.id()).withEnabled(on).withSchedule(schedule)
                        .withDestinations(named));
            }
        } catch (NotificationException ex) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.NTF_005, refusal(type, ex));
        }
        return row(tenant, entity, type);
    }

    private void save(TenantId tenant, EntityId entity, EventTypeId id, EventSettings s) {
        if (entity == null) settings.setTechnicalEvent(tenant, id, s);
        else settings.setBusinessEvent(tenant, entity, id, s);
    }

    private EventSettings effective(TenantId tenant, EntityId entity, EventTypeId id) {
        return settings.effective(tenant, entity, id);
    }

    private EventRow row(TenantId tenant, EntityId entity, EventType type) {
        EventSettings mail = effective(tenant, entity, type.id());
        Optional<String> twin = twinOf(type);
        boolean enabled;
        boolean email;
        List<String> channels;
        Schedule schedule = mail.schedule();
        if (twin.isPresent()) {
            EventSettings ch = effective(tenant, entity, EventTypeId.of(twin.get()));
            email = mail.enabled();
            channels = ch.enabled() ? ch.destinations().stream().map(DestinationId::value).sorted().toList() : List.of();
            enabled = email || !channels.isEmpty();
            if (!email && !channels.isEmpty()) schedule = ch.schedule();
        } else {
            enabled = mail.enabled();
            email = mail.destinations().isEmpty() || mail.destinations().contains(EMAIL);
            channels = mail.destinations().stream().filter(d -> !d.equals(EMAIL)).map(DestinationId::value).sorted().toList();
        }
        return new EventRow(type.id().value(), type.name(), type.critical(), type.continuous(), enabled,
                schedule.frequency().name(), schedule.dailyAt() == null ? null : schedule.dailyAt().toString(), email, channels,
                twin.isPresent());
    }

    /** The Teams and Slack destinations named, each one connected; e-mail is chosen with "email", not here. */
    private Set<DestinationId> channels(TenantId tenant, List<String> ids) {
        Map<DestinationId, ChannelKind> connected = settings.tenant(tenant).map(TenantSettings::destinations).orElse(Map.of());
        Set<DestinationId> out = new LinkedHashSet<>();
        for (String id : ids) {
            DestinationId d;
            try {
                d = DestinationId.of(id == null ? "" : id.trim());
            } catch (RuntimeException ex) {
                throw new CodedRefusal.BadRequest(SdvErrorCode.NTF_005, "There is no such channel.");
            }
            ChannelKind kind = connected.get(d);
            if (kind == null || kind == ChannelKind.EMAIL) {
                throw new CodedRefusal.BadRequest(SdvErrorCode.NTF_005,
                        "There is no such Teams or Slack channel, or IT has not connected the channels yet.");
            }
            out.add(d);
        }
        return out;
    }

    static Schedule schedule(String frequency, String dailyAt) {
        Frequency f;
        try {
            f = Frequency.valueOf(frequency == null ? "" : frequency.trim());
        } catch (IllegalArgumentException ex) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.NTF_005,
                    "Choose how often: IMMEDIATELY, EVERY_HOUR, EVERY_4_HOURS or ONCE_A_DAY.");
        }
        if (f != Frequency.ONCE_A_DAY) return new Schedule(f, null);
        try {
            return Schedule.onceADay(LocalTime.parse(dailyAt == null ? "" : dailyAt.trim()));
        } catch (DateTimeParseException ex) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.NTF_005, "Give the time of day for Once a day, such as 08:00.");
        }
    }

    private static EventTypeId eventTypeId(String id) {
        try {
            return EventTypeId.of(id == null ? "" : id.trim());
        } catch (RuntimeException ex) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.NTF_005, "There is no such event on this screen.");
        }
    }

    private static String refusal(EventType type, NotificationException ex) {
        if (type.critical()) {
            return "\"" + type.name() + "\" tells IT that something is down: it is always on, Immediately or Every hour.";
        }
        return "This setting can't be saved: " + ex.getMessage() + ".";
    }

    private static Set<String> twinIds() {
        Set<String> out = new LinkedHashSet<>();
        Arrays.stream(VeroEvent.values()).forEach(e -> e.channelId().ifPresent(out::add));
        return out;
    }

    private static Optional<String> twinOf(EventType type) {
        return Arrays.stream(VeroEvent.values()).filter(e -> e.id().equals(type.id().value())).findFirst()
                .flatMap(VeroEvent::channelId);
    }
}
