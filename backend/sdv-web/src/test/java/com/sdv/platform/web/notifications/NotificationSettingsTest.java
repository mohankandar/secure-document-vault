package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.Recipient;
import com.documentplatform.notifications.RoleId;
import com.sdv.platform.application.notifications.VeroEvent;
import com.sdv.platform.domain.error.CodedRefusal;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0081: the catalogue, the settings screens' rules and who holds a role. */
class NotificationSettingsTest {
    static final EntityId ENTITY = EntityId.of(NotificationKit.ENTITY.toString());

    @Test
    void theCatalogueHasEveryEventAndAChannelTwinForEachOneWithAnIdentifier() {
        EventCatalogue c = VeroCatalogue.build();
        for (VeroEvent e : VeroEvent.values()) {
            var type = c.require(EventTypeId.of(e.id()));
            assertEquals(e.label(), type.name());
            assertEquals(e.continuous(), type.continuous());
            assertEquals(e.critical(), type.critical());
            e.channelId().ifPresent(twin -> {
                var t = c.require(EventTypeId.of(twin));
                assertFalse(t.defaults().enabled(), "the Teams and Slack twin is off until the admin picks a channel");
                assertEquals(type.name(), t.name());
            });
        }
        assertTrue(Arrays.stream(VeroEvent.values()).filter(VeroEvent::identifier).allMatch(e -> e.kind() == VeroEvent.Kind.BUSINESS),
                "a technical event never names a record");
        assertTrue(c.find(EventTypeId.of("licensing.licence-expiring")).isPresent(), "licensing's events are registered too");
    }

    @Test
    void eachScreenShowsItsOwnEventsAsOneRowEach() {
        NotificationKit kit = new NotificationKit();
        var business = kit.screens.events(NotificationKit.TENANT, ENTITY);
        var technical = kit.screens.events(NotificationKit.TENANT, null);
        assertTrue(business.stream().noneMatch(r -> r.id().endsWith(".channel")), "a twin is never a row of its own");
        assertTrue(business.stream().anyMatch(r -> r.id().equals(VeroEvent.APPROVAL_WAITING.id())));
        assertTrue(technical.stream().anyMatch(r -> r.id().equals(VeroEvent.KEY_SERVICE_AWAY.id()) && r.critical()));
        assertTrue(technical.stream().noneMatch(r -> r.id().equals(VeroEvent.APPROVAL_WAITING.id())));
        var approval = business.stream().filter(r -> r.id().equals(VeroEvent.APPROVAL_WAITING.id())).findFirst().orElseThrow();
        assertEquals("EVERY_4_HOURS", approval.frequency());
        assertTrue(approval.email() && approval.channels().isEmpty() && approval.enabled());
    }

    @Test
    void refusedSettingsChangeNothing() {
        NotificationKit kit = new NotificationKit();
        assertEquals("SDV-NTF-005", code(() -> kit.screens.change(NotificationKit.TENANT, ENTITY, VeroEvent.KEY_SERVICE_AWAY.id(),
                new NotificationSettingsService.Change(true, "IMMEDIATELY", null, true, List.of()))), "a technical event on the business screen");
        assertEquals("SDV-NTF-005", code(() -> kit.screens.change(NotificationKit.TENANT, ENTITY, VeroEvent.HOLD_PLACED.id() + ".channel",
                new NotificationSettingsService.Change(true, "IMMEDIATELY", null, true, List.of()))), "the twin is not a row");
        assertEquals("SDV-NTF-005", code(() -> kit.screens.change(NotificationKit.TENANT, ENTITY, VeroEvent.HOLD_PLACED.id(),
                new NotificationSettingsService.Change(true, "IMMEDIATELY", null, true, List.of("email")))), "e-mail is not a channel");
        assertEquals("SDV-NTF-005", code(() -> kit.screens.change(NotificationKit.TENANT, ENTITY, VeroEvent.HOLD_PLACED.id(),
                new NotificationSettingsService.Change(true, "IMMEDIATELY", null, true, List.of("no-such-channel")))));
        assertEquals("SDV-NTF-005", code(() -> kit.screens.change(NotificationKit.TENANT, ENTITY, VeroEvent.HOLD_PLACED.id(),
                new NotificationSettingsService.Change(true, "ONCE_A_DAY", "25:00", true, List.of()))));
        assertEquals("SDV-NTF-005", code(() -> kit.screens.change(NotificationKit.TENANT, null, VeroEvent.KEY_SERVICE_AWAY.id(),
                new NotificationSettingsService.Change(true, "EVERY_4_HOURS", null, true, List.of()))), "critical: Immediately or Every hour");
        var key = kit.screens.change(NotificationKit.TENANT, null, VeroEvent.KEY_SERVICE_AWAY.id(),
                new NotificationSettingsService.Change(false, "EVERY_HOUR", null, true, List.of()));
        assertTrue(key.enabled(), "a critical event can't be switched off");
        assertEquals("EVERY_HOUR", key.frequency());
    }

    @Test
    void recipientsComeFromTheAdminsListsAndAnUnclearPersonGetsNothing() {
        NotificationKit.Lists lists = new NotificationKit.Lists();
        lists.add(null, "IT_ADMIN", "ita-1", "it@example.test");
        lists.add(NotificationKit.ENTITY, "BUSINESS_ADMIN", "ba-1", "ba@example.test");
        lists.add(NotificationKit.ENTITY, "DOCUMENT_STEWARD", "ds-1", "ds@example.test");
        ListRecipients r = new ListRecipients(lists, lists.holders, NotificationKit.WINDOW);
        assertEquals(List.of("ita-1"), r.resolve(NotificationKit.TENANT, ENTITY, VeroCatalogue.IT_ADMIN).stream().map(Recipient::userId).toList(),
                "the IT Admin is the customer's, also for an entity's event");
        assertEquals(List.of("ba-1"), r.resolve(NotificationKit.TENANT, ENTITY, VeroCatalogue.BUSINESS_ADMIN).stream().map(Recipient::userId).toList());
        assertTrue(r.resolve(NotificationKit.TENANT, null, VeroCatalogue.BUSINESS_ADMIN).isEmpty(), "a Business Admin is an entity's");
        assertTrue(r.resolve(NotificationKit.TENANT, EntityId.of("other-entity"), RoleId.of("BUSINESS_ADMIN")).isEmpty());
        assertEquals(Optional.of("ds@example.test"), r.person(NotificationKit.TENANT, "ds-1").map(Recipient::emailAddress));
        lists.add(java.util.UUID.randomUUID(), "DOCUMENT_STEWARD", "ds-1", "other@example.test");
        assertTrue(r.person(NotificationKit.TENANT, "ds-1").isEmpty(), "two addresses for one person: nobody is chosen");
        assertTrue(r.person(NotificationKit.TENANT, "nobody").isEmpty());
        assertFalse(new Recipient("ba-1", "ba@example.test").toString().contains("@"), "an address never reaches a log");
    }

    private static String code(Runnable r) {
        CodedRefusal refusal = (CodedRefusal) assertThrows(RuntimeException.class, r::run);
        return refusal.errorCode().code();
    }
}
