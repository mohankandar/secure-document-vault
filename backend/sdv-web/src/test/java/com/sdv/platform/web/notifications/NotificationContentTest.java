package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.OutgoingMessage;
import com.sdv.platform.application.notifications.VeroEvent;
import com.sdv.platform.application.notifications.VeroNotice;
import com.sdv.platform.domain.document.SensitivityLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0081, item 2: every Vero event through e-mail, Teams and Slack, and the text of every message read. A message
 * carries the event, counts, times and a link; the record's identifier by e-mail only, never for a Highly Confidential
 * record, never in Teams or Slack (answer 4); never a title, name, file name, reason, level or address.
 */
class NotificationContentTest {
    static final String RECORD = "LN 12345";
    static final EntityId ENTITY = EntityId.of(NotificationKit.ENTITY.toString());
    static final List<String> CHANNELS = List.of(NotificationKit.TEAMS.value(), NotificationKit.SLACK.value());

    private static VeroNotice notice(VeroEvent event, String identifier, SensitivityLevel level) {
        VeroNotice.Builder b = VeroNotice.of(event, event.kind() == VeroEvent.Kind.BUSINESS || event == VeroEvent.UNIDENTIFIED_ATTEMPTS
                ? NotificationKit.ENTITY : null).record(identifier, level);
        event.counts().forEach(c -> b.count(c, 3));
        event.times().forEach(t -> b.time(t, Instant.parse("2026-12-04T15:00:00Z")));
        if (event.audience() == VeroEvent.Audience.REQUESTER) b.to("rs-1");
        return b.build();
    }

    /** Immediately, by e-mail and to both channels, so every message goes at once. */
    private static void everywhere(NotificationKit kit, VeroEvent event) {
        var change = new NotificationSettingsService.Change(true, event.critical() ? "IMMEDIATELY" : "IMMEDIATELY", null, true, CHANNELS);
        kit.screens.change(NotificationKit.TENANT, event.kind() == VeroEvent.Kind.BUSINESS ? ENTITY : null, event.id(), change);
    }

    private static void assertNothingSensitive(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : List.of("highly", "confidential", "standard", "reason", "title", "@", "secret", "password")) {
            assertFalse(lower.contains(word), "a message never says \"" + word + "\": " + text);
        }
    }

    @Test
    void everyBusinessEventGoesThroughEachChannelAndOnlyTheEmailNamesTheRecord() {
        for (VeroEvent event : VeroEvent.values()) {
            if (event.kind() != VeroEvent.Kind.BUSINESS || event.audience() == VeroEvent.Audience.REQUESTER) continue;
            NotificationKit kit = new NotificationKit();
            everywhere(kit, event);
            kit.port.publish(notice(event, RECORD, SensitivityLevel.STANDARD));
            kit.send();
            assertEquals(2, kit.email.sent().size(), event + ": one e-mail per Business Admin (platform-core 1.1.1)");
            assertEquals(1, kit.teams.sent().size(), event + " to Teams");
            assertEquals(1, kit.slack.sent().size(), event + " to Slack");
            assertEquals(List.of("ba-1", "ba-2"), kit.email.sent().stream().flatMap(m -> m.recipients().stream()).map(r -> r.userId()).sorted().toList(),
                    "the entity's Business Admins");
            for (OutgoingMessage mail : kit.email.sent()) {
                assertEquals(1, mail.recipients().size(), "nobody sees another recipient's address");
                assertEquals(event.identifier(), mail.message().text().contains(RECORD), event + ": the e-mail names the record only for an event with one");
            }
            for (OutgoingMessage m : kit.all()) {
                String text = m.message().text();
                assertTrue(text.contains(event.label()), text);
                assertTrue(text.contains("https://vero.example.test" + event.link()), "the link into Vero: " + text);
                assertNothingSensitive(text);
            }
            for (OutgoingMessage m : List.of(kit.teams.sent().getFirst(), kit.slack.sent().getFirst())) {
                assertFalse(m.message().text().contains(RECORD), event + ": never in Teams or Slack");
                assertTrue(m.recipients().isEmpty(), "a channel has no addresses");
            }
        }
    }

    @Test
    void aHighlyConfidentialRecordOrOneOfUnknownLevelIsNeverNamed() {
        for (SensitivityLevel level : new SensitivityLevel[] { SensitivityLevel.HIGHLY_CONFIDENTIAL, null }) {
            NotificationKit kit = new NotificationKit();
            everywhere(kit, VeroEvent.APPROVAL_WAITING);
            kit.port.publish(notice(VeroEvent.APPROVAL_WAITING, RECORD, level));
            kit.send();
            assertEquals(4, kit.all().size(), "two e-mails, Teams, Slack");
            kit.all().forEach(m -> {
                assertFalse(m.message().text().contains(RECORD), "level " + level + ": " + m.message().text());
                assertNothingSensitive(m.message().text());
            });
        }
        // Confidential is named, by e-mail only.
        NotificationKit kit = new NotificationKit();
        everywhere(kit, VeroEvent.HOLD_PLACED);
        kit.port.publish(notice(VeroEvent.HOLD_PLACED, RECORD, SensitivityLevel.CONFIDENTIAL));
        kit.send();
        assertTrue(kit.email.sent().getFirst().message().text().contains(RECORD));
        assertFalse(kit.teams.sent().getFirst().message().text().contains(RECORD));
    }

    @Test
    void theRequesterIsToldByEmailAloneAndOnlyThemselves() {
        for (VeroEvent event : List.of(VeroEvent.REQUEST_APPROVED, VeroEvent.REQUEST_DECLINED)) {
            NotificationKit kit = new NotificationKit();
            kit.port.publish(notice(event, RECORD, SensitivityLevel.STANDARD));
            kit.port.publish(notice(event, "LN 99999", SensitivityLevel.HIGHLY_CONFIDENTIAL));
            kit.send();
            assertEquals(2, kit.email.sent().size());
            assertTrue(kit.teams.sent().isEmpty() && kit.slack.sent().isEmpty(), "never a channel: it is about one person");
            for (OutgoingMessage m : kit.email.sent()) {
                assertEquals(List.of("rs-1"), m.recipients().stream().map(r -> r.userId()).toList());
                assertTrue(m.message().text().contains(event.label()));
                assertFalse(m.message().text().contains("LN 99999"), "a print request is about a Highly Confidential document");
                assertNothingSensitive(m.message().text());
            }
            assertTrue(kit.email.sent().stream().anyMatch(m -> m.message().text().contains(RECORD)));
        }
    }

    @Test
    void everyTechnicalEventGoesThroughEachChannelWithoutAnIdentifier() {
        for (VeroEvent event : VeroEvent.values()) {
            if (event.kind() != VeroEvent.Kind.TECHNICAL) continue;
            NotificationKit kit = new NotificationKit();
            everywhere(kit, event);
            VeroNotice n = notice(event, RECORD, SensitivityLevel.STANDARD);
            if (event.continuous()) {
                kit.port.failing(n);
                kit.send();
                assertTrue(kit.all().isEmpty(), event + ": a condition alerts only once it has lasted");
                kit.clock.plusMinutes(16);
                kit.port.failing(n);
                kit.send();
            } else {
                kit.port.publish(n);
                kit.send();
            }
            assertEquals(1, kit.email.sent().size(), event + " by e-mail");
            assertEquals(List.of("ita-1"), kit.email.sent().getFirst().recipients().stream().map(r -> r.userId()).toList(), "the IT Admins");
            assertEquals(1, kit.teams.sent().size(), event + " to Teams");
            assertEquals(1, kit.slack.sent().size(), event + " to Slack");
            for (OutgoingMessage m : kit.all()) {
                assertTrue(m.message().text().contains(event.label()), m.message().text());
                assertFalse(m.message().text().contains(RECORD));
                assertNothingSensitive(m.message().text());
            }
            if (event.continuous()) {
                kit.clear();
                kit.port.cleared(event, n.entityId());
                kit.send();
                assertEquals(3, kit.all().size(), event + ": Recovered, once, to each");
                kit.all().forEach(m -> assertNothingSensitive(m.message().text()));
            }
        }
    }

    @Test
    void anIdentifierThatDoesNotFitTheShapeIsLeftOutAndTheMessageStillGoes() {
        NotificationKit kit = new NotificationKit();
        kit.port.publish(notice(VeroEvent.HOLD_PLACED, "name=Jane Doe", SensitivityLevel.STANDARD));
        kit.send();
        assertEquals(2, kit.email.sent().size());
        assertFalse(kit.email.sent().getFirst().message().text().contains("Jane"));
    }

    @Test
    void byDefaultABusinessEventGoesByEmailOnlyAndTheEmailTypeNeverGetsAChannel() {
        NotificationKit kit = new NotificationKit();
        kit.port.publish(notice(VeroEvent.HOLD_PLACED, RECORD, SensitivityLevel.STANDARD));
        kit.send();
        assertEquals(2, kit.email.sent().size());
        assertTrue(kit.teams.sent().isEmpty() && kit.slack.sent().isEmpty(), "the channels' twin is off until the admin picks one");

        everywhere(kit, VeroEvent.HOLD_PLACED);
        assertTrue(kit.settings.effective(NotificationKit.TENANT, ENTITY, EventTypeId.of(VeroEvent.HOLD_PLACED.id())).destinations().isEmpty(),
                "the e-mail type goes to the e-mail destination only");
        var row = kit.screens.events(NotificationKit.TENANT, ENTITY).stream().filter(r -> r.id().equals(VeroEvent.HOLD_PLACED.id())).findFirst().orElseThrow();
        assertTrue(row.email());
        assertEquals(CHANNELS.stream().sorted().toList(), row.channels());
        assertTrue(row.withIdentifier());
    }
}
