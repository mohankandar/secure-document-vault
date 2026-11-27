package com.sdv.platform.application.notifications;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.NotificationRecipientRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.error.CodedRefusal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.TransactionDefinition;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Task 0081, item 4: the admins' lists; every change audited, never with the address. */
class RecipientListServiceTest {
    static final String CUSTOMER = "TEN-LISTS";
    static final UUID ENTITY = UUID.fromString("10000000-0000-0000-0000-0000000b0081");
    static final RoleSeenWindow WINDOW = new RoleSeenWindow(30, Clock.fixed(Holders.NOW, ZoneOffset.UTC));

    private final Store store = new Store();
    private final Holders holders = new Holders()
            .hold(ENTITY, "ba-2", "BUSINESS_ADMIN").hold(ENTITY, "x", "BUSINESS_ADMIN").hold(ENTITY, "ita", "IT_ADMIN");
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final RecipientListService lists = new RecipientListService(store, holders, WINDOW, audit, new NoTransactions(),
            Clock.fixed(Instant.parse("2026-11-27T12:00:00Z"), ZoneOffset.UTC));
    private final RecipientListService.Actor admin = new RecipientListService.Actor(ENTITY, "ba-1", "Bea Admin", "corr");

    @Test
    void addAndRemoveAreAuditedWithoutTheAddress() {
        var added = lists.add(admin, CUSTOMER, ENTITY, "BUSINESS_ADMIN", " ba-2 ", " Second.Admin@Example.test ");
        assertEquals("ba-2", added.userId());
        assertEquals("second.admin@example.test", added.email());
        assertEquals(List.of(added), lists.list(CUSTOMER, ENTITY));
        lists.remove(admin, CUSTOMER, ENTITY, added.recipientId().toString());
        assertEquals(List.of(), lists.list(CUSTOMER, ENTITY));
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(2)).append(events.capture());
        assertEquals(List.of(RecipientListService.ADDED_ACTION, RecipientListService.REMOVED_ACTION), events.getAllValues().stream().map(AuditEvent::action).toList());
        for (AuditEvent e : events.getAllValues()) {
            assertFalse(e.safeDetailsJson().contains("@"), "never the address: " + e.safeDetailsJson());
            assertFalse(e.safeDetailsJson().toLowerCase().contains("example"), e.safeDetailsJson());
        }
    }

    @Test
    void whatIsNotValidIsRefusedAndChangesNothing() {
        assertEquals("SDV-NTF-002", code(() -> lists.add(admin, CUSTOMER, ENTITY, "IT_ADMIN", "x", "x@example.test")), "the IT Admins are the customer's list");
        assertEquals("SDV-NTF-002", code(() -> lists.add(admin, CUSTOMER, null, "BUSINESS_ADMIN", "x", "x@example.test")), "the customer's list is IT's");
        assertEquals("SDV-NTF-002", code(() -> lists.add(admin, CUSTOMER, ENTITY, "PLATFORM_OPERATIONS_ADMIN", "x", "x@example.test")));
        assertEquals("SDV-NTF-002", code(() -> lists.add(admin, CUSTOMER, ENTITY, "BUSINESS_ADMIN", "a b", "x@example.test")), "user ID");
        for (String bad : List.of("", "no-at", "two@@example.test", "a@b", "a b@example.test", "x@example.test, y@example.test")) {
            assertEquals("SDV-NTF-002", code(() -> lists.add(admin, CUSTOMER, ENTITY, "BUSINESS_ADMIN", "x", bad)), bad);
        }
        lists.add(admin, CUSTOMER, ENTITY, "BUSINESS_ADMIN", "x", "x@example.test");
        assertEquals("SDV-NTF-003", code(() -> lists.add(admin, CUSTOMER, ENTITY, "BUSINESS_ADMIN", "x", "other@example.test")), "on the list already");
        assertEquals("SDV-NTF-004", code(() -> lists.remove(admin, CUSTOMER, ENTITY, UUID.randomUUID().toString())));
        assertEquals("SDV-NTF-004", code(() -> lists.remove(admin, CUSTOMER, ENTITY, "not-an-id")));
        assertEquals(1, store.entries.size());
    }

    @Test
    void anotherEntitysEntryCantBeRemovedFromThisOne() {
        var it = lists.add(admin, CUSTOMER, null, "IT_ADMIN", "ita", "it@example.test");
        assertEquals("SDV-NTF-004", code(() -> lists.remove(admin, CUSTOMER, ENTITY, it.recipientId().toString())));
        assertEquals(1, lists.list(CUSTOMER, null).size());
    }

    @Test
    void nothingIsAuditedForARefusal() {
        RecipientListService fresh = new RecipientListService(new Store(), holders, WINDOW, audit, new NoTransactions());
        code(() -> fresh.add(admin, CUSTOMER, ENTITY, "BUSINESS_ADMIN", "x", "bad"));
        verifyNoInteractions(audit);
    }

    @Test
    void onlySomeoneWhoHoldsTheRoleGoesOnItsList() {
        // Task 0100 (VSEC-203): not signed in with the role (or another role only): refused, nothing written.
        assertEquals("SDV-NTF-002", code(() -> lists.add(admin, CUSTOMER, ENTITY, "BUSINESS_ADMIN", "anyone", "someone@outside.example")));
        assertEquals("SDV-NTF-002", code(() -> lists.add(admin, CUSTOMER, ENTITY, "DOCUMENT_STEWARD", "ba-2", "ba2@example.test")), "another role");
        assertEquals("SDV-NTF-002", code(() -> lists.add(admin, CUSTOMER, null, "IT_ADMIN", "ba-2", "ba2@example.test")), "not an IT Admin anywhere");
        assertEquals(0, store.entries.size());
        verifyNoInteractions(audit);
        assertEquals("ita", lists.add(admin, CUSTOMER, null, "IT_ADMIN", "ita", "it@example.test").userId(), "an IT Admin of any entity");
    }

    @Test
    void anEntryWhosePersonNoLongerHoldsTheRoleIsShownNotConfirmed() {
        var added = lists.add(admin, CUSTOMER, ENTITY, "BUSINESS_ADMIN", "ba-2", "ba2@example.test");
        assertTrue(added.confirmed());
        assertTrue(lists.list(CUSTOMER, ENTITY).getFirst().confirmed());
        holders.replace(CUSTOMER, ENTITY, "ba-2", java.util.Set.of(), Instant.EPOCH);
        assertFalse(lists.list(CUSTOMER, ENTITY).getFirst().confirmed());
    }

    private static String code(Runnable r) {
        return ((CodedRefusal) assertThrows(RuntimeException.class, r::run)).errorCode().code();
    }

    static final class Store implements NotificationRecipientRepository {
        final List<Entry> entries = new ArrayList<>();
        @Override public List<Entry> list(String c, UUID e) { return entries.stream().filter(x -> x.customerId().equals(c) && Objects.equals(x.entityId(), e)).toList(); }
        @Override public List<Entry> holders(String c, UUID e, String role) { return list(c, e).stream().filter(x -> x.role().equals(role)).toList(); }
        @Override public List<Entry> person(String c, String user) { return entries.stream().filter(x -> x.userId().equals(user)).toList(); }
        @Override public boolean insert(Entry n) {
            if (list(n.customerId(), n.entityId()).stream().anyMatch(x -> x.role().equals(n.role()) && x.userId().equals(n.userId()))) return false;
            return entries.add(n);
        }
        @Override public Optional<Entry> delete(String c, UUID e, UUID id) {
            Optional<Entry> found = list(c, e).stream().filter(x -> x.recipientId().equals(id)).findFirst();
            found.ifPresent(entries::remove);
            return found;
        }
    }

    /** Task 0100: role_holders in memory, for one customer. */
    static final class Holders implements com.sdv.platform.application.port.RoleHolderRepository {
        final java.util.Map<String, java.util.Set<String>> seen = new java.util.HashMap<>();
        /** When each person was last seen (role_holders.seen_at). */
        final java.util.Map<String, Instant> seenAt = new java.util.HashMap<>();
        /** What a sign-in by {@code hold} counts as: the tests' now. */
        static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");

        Holders hold(UUID entity, String user, String role) {
            seen.computeIfAbsent(entity + "|" + user, k -> new java.util.TreeSet<>()).add(role);
            return this;
        }

        @Override public java.util.Set<String> roles(UUID entityId, String userId) {
            return new java.util.TreeSet<>(seen.getOrDefault(entityId + "|" + userId, java.util.Set.of()));
        }

        @Override public void replace(String customerId, UUID entityId, String userId, java.util.Set<String> roles, Instant at) {
            seen.put(entityId + "|" + userId, new java.util.TreeSet<>(roles));
            this.seenAt.put(entityId + "|" + userId, at);
        }

        @Override public java.util.Set<String> holders(String customerId, UUID entityId, String role, Instant seenSince) {
            java.util.Set<String> out = new java.util.TreeSet<>();
            seen.forEach((k, roles) -> {
                String[] parts = k.split("[|]", 2);
                if (roles.contains(role) && !seenAt.getOrDefault(k, NOW).isBefore(seenSince) && (entityId == null || parts[0].equals(entityId.toString()))) out.add(parts[1]);
            });
            return out;
        }
    }

    static final class NoTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
