package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.TenantSettings;
import com.documentplatform.notifications.inmemory.InMemoryChannel;
import com.documentplatform.notifications.inmemory.InMemoryConditionStore;
import com.documentplatform.notifications.inmemory.InMemoryOutboxStore;
import com.documentplatform.notifications.inmemory.InMemorySettingsStore;
import com.documentplatform.notifications.inmemory.InMemorySummaryStore;
import com.documentplatform.notifications.spring.NotificationsProperties;
import com.sdv.platform.application.port.NotificationRecipientRepository;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0081: Vero's notifications on platform-core's notifier with the testkit's in-memory stores and channels (one
 * per kind: e-mail, Teams, Slack), a clock the test moves, and recipient lists in memory.
 */
final class NotificationKit {
    static final String CUSTOMER = "TEN-NOTIFY-TEST";
    static final TenantId TENANT = TenantId.of(CUSTOMER);
    static final UUID ENTITY = UUID.fromString("10000000-0000-0000-0000-00000000a081");
    static final DestinationId TEAMS = DestinationId.of("ops-teams");
    static final DestinationId SLACK = DestinationId.of("ops-slack");

    final MovingClock clock = new MovingClock(Instant.parse("2026-11-27T15:00:00Z"));
    final InMemoryChannel email = new InMemoryChannel(ChannelKind.EMAIL);
    final InMemoryChannel teams = new InMemoryChannel(ChannelKind.TEAMS);
    final InMemoryChannel slack = new InMemoryChannel(ChannelKind.SLACK);
    final Lists lists = new Lists();
    final NotificationSettings settings = new NotificationSettings(VeroCatalogue.build(), new InMemorySettingsStore());
    final Notifier notifier = Notifier.builder(settings, new ListRecipients(lists, lists.holders))
            .channels(List.of(email, teams, slack))
            .outbox(new InMemoryOutboxStore()).summaries(new InMemorySummaryStore()).conditions(new InMemoryConditionStore())
            .clock(clock)
            .build();
    final VeroNotifications port = new VeroNotifications(notifier, settings, e -> Optional.of(CUSTOMER), CUSTOMER);
    final NotificationSettingsService screens;

    NotificationKit() {
        NotificationsProperties config = new NotificationsProperties();
        config.getMail().setHost("mail.example.test");
        config.getMail().setFromAddress("vero@example.test");
        config.getTeams().put(TEAMS.value(), "notifications/teams-ops");
        config.getSlack().put(SLACK.value(), "notifications/slack-ops");
        screens = new NotificationSettingsService(settings, config, Map::of, PublicUrl.of("https://vero.example.test", false));
        screens.connect(TENANT, ZoneId.of("UTC"));
        lists.add(null, "IT_ADMIN", "ita-1", "it.admin@example.test");
        lists.add(ENTITY, "BUSINESS_ADMIN", "ba-1", "business.admin@example.test");
        lists.add(ENTITY, "BUSINESS_ADMIN", "ba-2", "second.admin@example.test");
        lists.add(ENTITY, "RESTRICTED_STEWARD", "rs-1", "steward@example.test");
    }

    /** Sends what is due now. */
    void send() {
        notifier.tick(clock.instant());
    }

    List<OutgoingMessage> all() {
        List<OutgoingMessage> out = new ArrayList<>();
        out.addAll(email.sent());
        out.addAll(teams.sent());
        out.addAll(slack.sent());
        return out;
    }

    void clear() {
        email.clear();
        teams.clear();
        slack.clear();
    }

    static final class MovingClock extends Clock {
        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void plusMinutes(long minutes) {
            now = now.plusSeconds(minutes * 60);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** The admins' lists, in memory. */
    /** Task 0100: who holds which list role, in memory (role_holders). */
    static final class Holders implements com.sdv.platform.application.port.RoleHolderRepository {
        /** entity|user to roles; the customer is CUSTOMER. */
        final Map<String, java.util.Set<String>> seen = new java.util.HashMap<>();

         public java.util.Set<String> roles(UUID entityId, String userId) {
            return new java.util.TreeSet<>(seen.getOrDefault(entityId + "|" + userId, java.util.Set.of()));
        }

         public void replace(String customerId, UUID entityId, String userId, java.util.Set<String> roles, Instant seenAt) {
            seen.put(entityId + "|" + userId, new java.util.TreeSet<>(roles));
        }

         public java.util.Set<String> holders(String customerId, UUID entityId, String role) {
            java.util.Set<String> out = new java.util.TreeSet<>();
            seen.forEach((k, roles) -> {
                String[] parts = k.split("[|]", 2);
                if (roles.contains(role) && (entityId == null || parts[0].equals(entityId.toString()))) out.add(parts[1]);
            });
            return out;
        }

        void hold(UUID entity, String role, String user) {
            seen.computeIfAbsent((entity == null ? ENTITY : entity) + "|" + user, k -> new java.util.TreeSet<>()).add(role);
        }
    }

    static final class Lists implements NotificationRecipientRepository {
        final List<Entry> entries = new ArrayList<>();
        /** Everyone added with {@link #add} holds the role (as if they had signed in with it). */
        final Holders holders = new Holders();

        void add(UUID entity, String role, String user, String email) {
            holders.hold(entity, role, user);
            insert(new Entry(UUID.randomUUID(), CUSTOMER, entity, role, user, email, "admin", Instant.EPOCH));
        }

        @Override public List<Entry> list(String customerId, UUID entityId) {
            return entries.stream().filter(e -> e.customerId().equals(customerId) && Objects.equals(e.entityId(), entityId)).toList();
        }

        @Override public List<Entry> holders(String customerId, UUID entityId, String role) {
            return list(customerId, entityId).stream().filter(e -> e.role().equals(role)).toList();
        }

        @Override public List<Entry> person(String customerId, String userId) {
            return entries.stream().filter(e -> e.customerId().equals(customerId) && e.userId().equals(userId)).toList();
        }

        @Override public boolean insert(Entry entry) {
            boolean taken = entries.stream().anyMatch(e -> e.customerId().equals(entry.customerId())
                    && Objects.equals(e.entityId(), entry.entityId()) && e.role().equals(entry.role()) && e.userId().equals(entry.userId()));
            if (!taken) entries.add(entry);
            return !taken;
        }

        @Override public Optional<Entry> delete(String customerId, UUID entityId, UUID recipientId) {
            Optional<Entry> found = list(customerId, entityId).stream().filter(e -> e.recipientId().equals(recipientId)).findFirst();
            found.ifPresent(entries::remove);
            return found;
        }
    }
}
