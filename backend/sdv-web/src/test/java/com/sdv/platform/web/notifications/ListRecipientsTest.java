package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.Recipient;
import com.sdv.platform.application.port.NotificationRecipientRepository.Entry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 0100 (VSEC-203): of a role's list, only those who held the role at their last sign-in get messages. */
class ListRecipientsTest {
    final NotificationKit.Lists lists = new NotificationKit.Lists();
    final ListRecipients recipients = new ListRecipients(lists, lists.holders, NotificationKit.WINDOW);
    final EntityId entity = EntityId.of(NotificationKit.ENTITY.toString());

    private List<String> businessAdmins() {
        return recipients.resolve(NotificationKit.TENANT, entity, VeroCatalogue.BUSINESS_ADMIN).stream().map(Recipient::userId).sorted().toList();
    }

    @Test
    void anEntryWhosePersonNeverHeldOrNoLongerHoldsTheRoleGetsNothing() {
        lists.add(NotificationKit.ENTITY, "BUSINESS_ADMIN", "ba-1", "ba1@example.test");
        lists.add(NotificationKit.ENTITY, "BUSINESS_ADMIN", "ba-2", "ba2@example.test");
        // On the list from before (or typed in), never signed in with the role:
        lists.insert(new Entry(UUID.randomUUID(), NotificationKit.CUSTOMER, NotificationKit.ENTITY, "BUSINESS_ADMIN", "anyone",
                "someone@outside.example", "admin", Instant.EPOCH));
        assertEquals(List.of("ba-1", "ba-2"), businessAdmins());

        lists.holders.replace(NotificationKit.CUSTOMER, NotificationKit.ENTITY, "ba-2", Set.of("BUSINESS_USER"), Instant.EPOCH);
        assertEquals(List.of("ba-1"), businessAdmins(), "the role is gone");
    }

    @Test
    void someoneNotSeenAtASignInFor31DaysGetsNothing() {
        // Answer Q-5: the role taken away and no sign-in since; 30 days by default.
        lists.add(NotificationKit.ENTITY, "BUSINESS_ADMIN", "ba-1", "ba1@example.test");
        Instant now = NotificationKit.Holders.NOW;
        lists.holders.replace(NotificationKit.CUSTOMER, NotificationKit.ENTITY, "ba-1", Set.of("BUSINESS_ADMIN"), now.minus(java.time.Duration.ofDays(29)));
        assertEquals(List.of("ba-1"), businessAdmins(), "seen 29 days ago");
        lists.holders.replace(NotificationKit.CUSTOMER, NotificationKit.ENTITY, "ba-1", Set.of("BUSINESS_ADMIN"), now.minus(java.time.Duration.ofDays(31)));
        assertEquals(List.of(), businessAdmins(), "seen 31 days ago");
    }

    @Test
    void theWindowIsSevenToNinetyDays() {
        assertEquals(30, new com.sdv.platform.application.notifications.RoleSeenWindow("30").days());
        assertEquals(7, new com.sdv.platform.application.notifications.RoleSeenWindow(" 7 ").days());
        for (String bad : new String[]{"6", "91", "", "thirty"}) {
            org.junit.jupiter.api.Assertions.assertThrows(com.sdv.platform.domain.error.StartupFailure.class,
                    () -> new com.sdv.platform.application.notifications.RoleSeenWindow(bad), bad);
        }
    }

    @Test
    void theItAdminsListCountsAnEntityWhereThePersonIsItAdmin() {
        lists.add(null, "IT_ADMIN", "it-1", "it1@example.test");
        assertEquals(List.of("it-1"), recipients.resolve(NotificationKit.TENANT, entity, VeroCatalogue.IT_ADMIN).stream().map(Recipient::userId).toList());
        lists.holders.replace(NotificationKit.CUSTOMER, NotificationKit.ENTITY, "it-1", Set.of(), Instant.EPOCH);
        assertEquals(List.of(), recipients.resolve(NotificationKit.TENANT, entity, VeroCatalogue.IT_ADMIN).stream().toList());
    }
}
