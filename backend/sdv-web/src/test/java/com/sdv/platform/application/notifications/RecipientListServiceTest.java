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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Task 0081, item 4: the admins' lists; every change audited, never with the address. */
class RecipientListServiceTest {
    static final String CUSTOMER = "TEN-LISTS";
    static final UUID ENTITY = UUID.fromString("10000000-0000-0000-0000-0000000b0081");

    private final Store store = new Store();
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final RecipientListService lists = new RecipientListService(store, audit, new NoTransactions(),
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
        RecipientListService fresh = new RecipientListService(new Store(), audit, new NoTransactions());
        code(() -> fresh.add(admin, CUSTOMER, ENTITY, "BUSINESS_ADMIN", "x", "bad"));
        verifyNoInteractions(audit);
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

    static final class NoTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
