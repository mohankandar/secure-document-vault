package com.sdv.platform.application.notifications;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.domain.audit.AuditEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Task 0100 (VSEC-203): a sign-in that shows a role gone takes the person off that role's lists, audited. */
class RoleHolderServiceTest {
    static final String CUSTOMER = RecipientListServiceTest.CUSTOMER;
    static final UUID ENTITY = RecipientListServiceTest.ENTITY;
    static final UUID OTHER = UUID.fromString("10000000-0000-0000-0000-0000000c0100");

    final RecipientListServiceTest.Store store = new RecipientListServiceTest.Store();
    final RecipientListServiceTest.Holders holders = new RecipientListServiceTest.Holders();
    final AuditEventPort audit = mock(AuditEventPort.class);
    final RecipientListServiceTest.NoTransactions tx = new RecipientListServiceTest.NoTransactions();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-27T12:00:00Z"), ZoneOffset.UTC);
    final RoleHolderService service = new RoleHolderService(holders, store, audit, tx, clock);
    final RecipientListService lists = new RecipientListService(store, holders, RecipientListServiceTest.WINDOW, mock(AuditEventPort.class), tx, clock);
    final RecipientListService.Actor admin = new RecipientListService.Actor(ENTITY, "ba-1", "Bea Admin", "corr");

    @Test
    void aSignInWritesTheListRolesOnly() {
        assertTrue(service.seen(CUSTOMER, ENTITY, "u1", Set.of("BUSINESS_ADMIN", "PLATFORM_OPERATIONS_ADMIN")).isEmpty());
        assertEquals(Set.of("BUSINESS_ADMIN"), holders.roles(ENTITY, "u1"));
        verifyNoInteractions(audit);
    }

    @Test
    void aRoleGoneTakesThePersonOffThatRolesListsAndIsAudited() {
        service.seen(CUSTOMER, ENTITY, "u1", Set.of("BUSINESS_ADMIN", "DOCUMENT_STEWARD"));
        lists.add(admin, CUSTOMER, ENTITY, "BUSINESS_ADMIN", "u1", "u1@example.test");
        lists.add(admin, CUSTOMER, ENTITY, "DOCUMENT_STEWARD", "u1", "u1@example.test");

        var removed = service.seen(CUSTOMER, ENTITY, "u1", Set.of("DOCUMENT_STEWARD"));
        assertEquals(List.of("BUSINESS_ADMIN"), removed.stream().map(e -> e.role()).toList());
        assertEquals(List.of("DOCUMENT_STEWARD"), store.entries.stream().map(e -> e.role()).toList(), "the other role's entry stays");

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertEquals(RecipientListService.REMOVED_ACTION, event.getValue().action());
        assertTrue(event.getValue().safeDetailsJson().contains(RoleHolderService.ROLE_GONE));
        assertFalse(event.getValue().safeDetailsJson().contains("@"), "never the address");
    }

    @Test
    void anItAdminLeavesTheCustomersListOnlyWhenNoEntityShowsTheRole() {
        service.seen(CUSTOMER, ENTITY, "it1", Set.of("IT_ADMIN"));
        service.seen(CUSTOMER, OTHER, "it1", Set.of("IT_ADMIN"));
        lists.add(admin, CUSTOMER, null, "IT_ADMIN", "it1", "it1@example.test");

        assertTrue(service.seen(CUSTOMER, ENTITY, "it1", Set.of()).isEmpty(), "still IT Admin in the other entity");
        assertEquals(1, store.entries.size());
        assertEquals(1, service.seen(CUSTOMER, OTHER, "it1", Set.of("BUSINESS_USER")).size());
        assertTrue(store.entries.isEmpty());
        verify(audit, times(1)).append(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aUserIdThatCantBeOnAListIsLeftAlone() {
        assertTrue(service.seen(CUSTOMER, ENTITY, "has space", Set.of("BUSINESS_ADMIN")).isEmpty());
        assertTrue(service.seen("", ENTITY, "u1", Set.of("BUSINESS_ADMIN")).isEmpty());
        assertTrue(holders.seen.isEmpty());
    }
}
