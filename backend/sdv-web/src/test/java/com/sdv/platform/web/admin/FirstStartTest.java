package com.sdv.platform.web.admin;

import com.documentplatform.identity.store.ActivationOutcome;
import com.documentplatform.identity.store.SlotActivation;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.LicenceRefusal;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.error.CodedRefusal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 0098: the first entity from the first-start screen. Tests first, as the task asks. */
class FirstStartTest {
    static final String SLOT = "ENT-0000000000000001";
    static final String FILE = "{\"licence\":\"the file\"}";

    final AtomicBoolean active = new AtomicBoolean(false);
    final Licence stored = licence("LIC-1", 3, true);
    Licence uploaded = licence("LIC-1", 3, true);
    RuntimeException verifyFails;
    final SlotActivation activation = mock(SlotActivation.class);
    final TenantRepository tenants = mock(TenantRepository.class);
    final AuditEventPort audit = mock(AuditEventPort.class);
    final ReentrantLock jvmLock = new ReentrantLock();
    /** first_start_attempts in memory (answer Q-7: a table, not this Vero's memory). */
    final List<FirstStart.Refused> table = Collections.synchronizedList(new ArrayList<>());
    final FirstStart.Attempts kept = new FirstStart.Attempts() {
        @Override public void add(FirstStart.Refused r) { table.add(r); }
        @Override public List<FirstStart.Refused> takeAll() { List<FirstStart.Refused> all = List.copyOf(table); table.clear(); return all; }
    };
    final FirstStart.Lock lock = new FirstStart.Lock() {
        @Override public <T> T exclusively(Supplier<T> work) {
            jvmLock.lock();
            try { return work.get(); } finally { jvmLock.unlock(); }
        }
    };
    final FirstStart firstStart = new FirstStart(active::get, () -> stored, (bytes, customer) -> {
        if (verifyFails != null) throw verifyFails;
        assertEquals("TEN-1", customer, "checked as this customer's");
        return uploaded;
    }, activation, tenants, audit, lock, Clock.fixed(Instant.parse("2026-11-27T12:00:00Z"), ZoneOffset.UTC), kept,
            org.springframework.transaction.support.TransactionOperations.withoutTransaction());

    final FirstStart.Person itAdmin = new FirstStart.Person("it-1", "Ivo IT", List.of(SLOT));
    final FirstStart.Person businessAdminOnly = new FirstStart.Person("ba-1", "Bea", List.of());

    FirstStartTest() {
        when(activation.activate(eq(SLOT), anyString(), anyString())).thenAnswer(c -> {
            active.set(true);
            return new ActivationOutcome(ActivationOutcome.Result.ACTIVATED, SLOT, null);
        });
    }

    private static Licence licence(String id, long version, boolean listsSlot) {
        Licence l = mock(Licence.class);
        when(l.licenceId()).thenReturn(id);
        when(l.version()).thenReturn(version);
        when(l.tenantId()).thenReturn("TEN-1");
        when(l.slot(SLOT)).thenReturn(listsSlot ? Optional.of(mock(EntitySlot.class)) : Optional.empty());
        return l;
    }

    private static String code(Runnable r) {
        return ((CodedRefusal) assertThrows(RuntimeException.class, r::run)).errorCode().code();
    }

    @Test
    void anItAdminOfTheSlotActivatesTheFirstEntityAndItIsAudited() {
        assertEquals(List.of(SLOT), firstStart.open(itAdmin));
        FirstStart.Activated done = firstStart.activate(itAdmin, SLOT, "Acme Mortgage", FILE);
        assertEquals(SLOT, done.slotId());
        verify(tenants).createEntity(eq(done.entityId()), eq(SLOT.toLowerCase()), eq("Acme Mortgage"));
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertEquals(EntityActivationController.ACTION, event.getValue().action());
        assertTrue(event.getValue().safeDetailsJson().contains("\"firstStart\":true"));
        assertFalse(event.getValue().safeDetailsJson().contains("the file"), "never the licence");
    }

    @Test
    void someoneWhoIsNotItAdminOfTheSlotIsRefused() {
        assertEquals("SDV-ENT-002", code(() -> firstStart.activate(businessAdminOnly, SLOT, "Acme", FILE)));
        assertEquals("SDV-ENT-002", code(() -> firstStart.activate(itAdmin, "ENT-0000000000000002", "Acme", FILE)), "another slot");
        assertEquals("SDV-ENT-002", code(() -> firstStart.activate(itAdmin, "not a slot", "Acme", FILE)));
        verify(activation, never()).activate(anyString(), anyString(), anyString());
    }

    @Test
    void aLicenceFileThatIsNotThisInstallsIsRefusedWithTheExistingCode() {
        verifyFails = new LicenceException(LicenceRefusal.LICENCE_TAMPERED);
        assertEquals("SDV-ENT-001", code(() -> firstStart.activate(itAdmin, SLOT, "Acme", FILE)));
        verifyFails = null;
        uploaded = licence("LIC-OTHER", 3, true);
        assertEquals("SDV-ENT-001", code(() -> firstStart.activate(itAdmin, SLOT, "Acme", FILE)), "another licence");
        uploaded = licence("LIC-1", 2, true);
        assertEquals("SDV-ENT-001", code(() -> firstStart.activate(itAdmin, SLOT, "Acme", FILE)), "an older version");
        uploaded = licence("LIC-1", 3, false);
        assertEquals("SDV-ENT-001", code(() -> firstStart.activate(itAdmin, SLOT, "Acme", FILE)), "the slot isn't in it");
        assertEquals("SDV-ENT-001", code(() -> firstStart.activate(itAdmin, SLOT, "Acme", " ")), "no file");
        assertEquals("SDV-ENT-001", code(() -> firstStart.activate(itAdmin, SLOT, "Acme", "x".repeat(FirstStart.MAX_LICENCE_CHARS + 1))));
        verify(activation, never()).activate(anyString(), anyString(), anyString());
        verify(audit, never()).append(any());
        assertEquals(6, table.size(), "kept in the table, not in memory");
        assertTrue(table.stream().noneMatch(r -> r.toString().contains("the file")), "never the licence");

        // The refused attempts reach the audit of the entity once it exists.
        uploaded = licence("LIC-1", 4, true);
        firstStart.activate(itAdmin, SLOT, "Acme", FILE);
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(7)).append(events.capture());
        assertEquals(6, events.getAllValues().stream().filter(e -> e.action().equals(FirstStart.REFUSED_ACTION)).count());
        assertTrue(events.getAllValues().stream().anyMatch(e -> e.safeDetailsJson().contains("LICENCE_TAMPERED")));
        assertTrue(table.isEmpty(), "emptied with the copy");
    }

    @Test
    void onceAnEntityIsActiveTheScreenIsGoneForEveryone() {
        active.set(true);
        assertThrows(FirstStart.Gone.class, () -> firstStart.open(itAdmin));
        assertThrows(FirstStart.Gone.class, () -> firstStart.activate(itAdmin, SLOT, "Acme", FILE));
        assertThrows(FirstStart.Gone.class, () -> firstStart.activate(businessAdminOnly, SLOT, "Acme", FILE));
        verify(activation, never()).activate(anyString(), anyString(), anyString());
    }

    @Test
    void ofTwoAtOnceOnlyOneWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Object> results = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            runs.add(pool.submit(() -> {
                start.await();
                try {
                    results.add(firstStart.activate(itAdmin, SLOT, "Acme", FILE));
                } catch (FirstStart.Gone gone) {
                    results.add(gone);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : runs) f.get();
        pool.shutdown();
        assertEquals(1, results.stream().filter(r -> r instanceof FirstStart.Activated).count());
        assertEquals(1, results.stream().filter(r -> r instanceof FirstStart.Gone).count());
        verify(activation, times(1)).activate(anyString(), anyString(), anyString());
    }
}
