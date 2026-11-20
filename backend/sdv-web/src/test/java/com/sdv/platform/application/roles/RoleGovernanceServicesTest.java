package com.sdv.platform.application.roles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RoleGovernancePort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0025: the role-conflict record (rule 1) and the audited change of the Restricted Steward groups (rule 3). */
class RoleGovernanceServicesTest {
    private static final UUID T1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID T2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RoleGovernancePort store = mock(RoleGovernancePort.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RoleMappingWatchService watch = new RoleMappingWatchService(store, audit, clock);
    private final RoleConflictService conflicts = new RoleConflictService(store, audit, clock);

    private List<AuditEvent> audited() {
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, atLeast(0)).append(events.capture());
        return events.getAllValues();
    }

    @Test
    void theFirstStartWithGroupsIsAChangeForEveryTenant() throws Exception {
        when(store.findRecordedGroups("RESTRICTED_STEWARD")).thenReturn(Optional.empty());
        when(store.tenantIds()).thenReturn(List.of(T1, T2));

        assertTrue(watch.check("RESTRICTED_STEWARD", List.of(" vero-restricted ", "hc-readers", "vero-restricted")));

        List<AuditEvent> events = audited();
        assertEquals(2, events.size());
        assertEquals(List.of(T1, T2), events.stream().map(AuditEvent::tenantId).toList());
        AuditEvent event = events.get(0);
        assertEquals("ROLE_GROUP_MAPPING_CHANGED", event.action());
        assertEquals("SYSTEM", event.actorType());
        assertEquals("ROLE", event.targetType());
        assertEquals("RESTRICTED_STEWARD", event.targetId());
        assertEquals(AuditOutcome.SUCCESS, event.outcome());
        JsonNode details = JSON.readTree(event.safeDetailsJson());
        assertTrue(details.get("groupsBefore").isNull());
        assertEquals("[\"hc-readers\",\"vero-restricted\"]", details.get("groupsAfter").toString());
        verify(store, times(2)).addNotice(argThat(n -> n.roleName().equals("RESTRICTED_STEWARD") && n.groupsBefore() == null
                && n.groupsAfter().equals(List.of("hc-readers", "vero-restricted")) && n.createdAt().equals(NOW)));
        verify(store).saveRecordedGroups("RESTRICTED_STEWARD", List.of("hc-readers", "vero-restricted"), NOW);
    }

    @Test
    void theSameGroupsInAnotherOrderAreNoChange() {
        when(store.findRecordedGroups("RESTRICTED_STEWARD")).thenReturn(Optional.of(List.of("vero-restricted", "hc-readers")));
        assertFalse(watch.check("RESTRICTED_STEWARD", List.of("hc-readers", "vero-restricted")));
        verifyNoInteractions(audit);
        verify(store, never()).addNotice(any());
        verify(store, never()).saveRecordedGroups(anyString(), anyList(), any());
    }

    @Test
    void aChangedGroupIsAuditedWithBeforeAndAfter() throws Exception {
        when(store.findRecordedGroups("RESTRICTED_STEWARD")).thenReturn(Optional.of(List.of("vero-restricted")));
        when(store.tenantIds()).thenReturn(List.of(T1));
        assertTrue(watch.check("RESTRICTED_STEWARD", List.of("vero-restricted", "everyone")));
        JsonNode details = JSON.readTree(audited().get(0).safeDetailsJson());
        assertEquals("[\"vero-restricted\"]", details.get("groupsBefore").toString());
        assertEquals("[\"everyone\",\"vero-restricted\"]", details.get("groupsAfter").toString());
        verify(store).addNotice(argThat(n -> n.tenantId().equals(T1) && n.groupsBefore().equals(List.of("vero-restricted"))));
    }

    @Test
    void removingEveryGroupIsAChangeToo() {
        when(store.findRecordedGroups("RESTRICTED_STEWARD")).thenReturn(Optional.of(List.of("vero-restricted")));
        when(store.tenantIds()).thenReturn(List.of(T1));
        assertTrue(watch.check("RESTRICTED_STEWARD", List.of()));
        verify(store).addNotice(argThat(n -> n.groupsAfter().isEmpty()));
        verify(store).saveRecordedGroups("RESTRICTED_STEWARD", List.of(), NOW);
    }

    @Test
    void theFirstStartWithoutGroupsOnlyRecordsThat() {
        when(store.findRecordedGroups("RESTRICTED_STEWARD")).thenReturn(Optional.empty());
        assertFalse(watch.check("RESTRICTED_STEWARD", List.of()));
        verifyNoInteractions(audit);
        verify(store, never()).addNotice(any());
        verify(store).saveRecordedGroups("RESTRICTED_STEWARD", List.of(), NOW);
    }

    @Test
    void aFailedAuditFailsTheCheck() {
        when(store.findRecordedGroups("RESTRICTED_STEWARD")).thenReturn(Optional.empty());
        when(store.tenantIds()).thenReturn(List.of(T1));
        doThrow(new IllegalStateException("db down")).when(audit).append(any());
        assertThrows(IllegalStateException.class, () -> watch.check("RESTRICTED_STEWARD", List.of("g")));
        verify(store, never()).saveRecordedGroups(anyString(), anyList(), any());
    }

    @Test
    void aNewRoleConflictIsAuditedOnceAndAlwaysRecorded() throws Exception {
        when(store.recordRoleConflict(any())).thenReturn(true, false);
        for (int i = 0; i < 2; i++) {
            conflicts.record(T1, "person-1", "Pat Lee", "LOGIN_GROUPS",
                    List.of("RESTRICTED_STEWARD", "BUSINESS_USER", "BUSINESS_ADMIN"),
                    List.of("RESTRICTED_STEWARD", "BUSINESS_ADMIN"), List.of("RESTRICTED_STEWARD_NOT_ADMIN"));
        }
        verify(store, times(2)).recordRoleConflict(argThat(p -> p.tenantId().equals(T1) && p.subject().equals("person-1")
                && p.heldRoles().equals(List.of("BUSINESS_ADMIN", "BUSINESS_USER", "RESTRICTED_STEWARD"))
                && p.withheldRoles().equals(List.of("BUSINESS_ADMIN", "RESTRICTED_STEWARD"))));
        List<AuditEvent> events = audited();
        assertEquals(1, events.size());
        AuditEvent event = events.get(0);
        assertEquals("ROLE_CONFLICT_DETECTED", event.action());
        assertEquals(AuditOutcome.DENIED, event.outcome());
        assertEquals("person-1", event.actorId());
        assertEquals("Pat Lee", event.actorDisplayName());
        JsonNode details = JSON.readTree(event.safeDetailsJson());
        assertEquals("LOGIN_GROUPS", details.get("resolvedFrom").asText());
        assertEquals("[\"BUSINESS_ADMIN\",\"RESTRICTED_STEWARD\"]", details.get("withheldRoles").toString());
        assertEquals("[\"RESTRICTED_STEWARD_NOT_ADMIN\"]", details.get("rules").toString());
    }
}
