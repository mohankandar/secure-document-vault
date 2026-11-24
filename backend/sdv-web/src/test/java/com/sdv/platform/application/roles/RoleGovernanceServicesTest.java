package com.sdv.platform.application.roles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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

/** Task 0025: the role-conflict record (rule 1). Task 0041: rule 3 (the watched group setting) went with that setting. */
class RoleGovernanceServicesTest {
    private static final UUID T1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID T2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RoleGovernancePort store = mock(RoleGovernancePort.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RoleConflictService conflicts = new RoleConflictService(store, audit, clock);

    private List<AuditEvent> audited() {
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, atLeast(0)).append(events.capture());
        return events.getAllValues();
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
        assertEquals("LOGIN_GROUPS", details.get("resolvedFrom").asString());
        assertEquals("[\"BUSINESS_ADMIN\",\"RESTRICTED_STEWARD\"]", details.get("withheldRoles").toString());
        assertEquals("[\"RESTRICTED_STEWARD_NOT_ADMIN\"]", details.get("rules").toString());
    }

    /** Task 0026, carry-over 3: one event when the conflict starts, one when it ends, nothing in between. */
    @Test
    void aConflictIsAuditedWhenItStartsAndWhenItEndsAndNotWhileItLasts() throws Exception {
        when(store.recordRoleConflict(any())).thenReturn(true, false, false);
        RoleGovernancePort.RoleConflictProblem open = new RoleGovernancePort.RoleConflictProblem(UUID.randomUUID(), T1,
                "person-1", "Pat Lee", "LOGIN_GROUPS", List.of("BUSINESS_ADMIN", "RESTRICTED_STEWARD"),
                List.of("BUSINESS_ADMIN", "RESTRICTED_STEWARD"), List.of("RESTRICTED_STEWARD_NOT_ADMIN"),
                NOW.minusSeconds(3600), NOW.minusSeconds(60), 3);
        when(store.endRoleConflicts(T1, "person-1", "LOGIN_GROUPS")).thenReturn(List.of(open), List.of());
        for (int i = 0; i < 3; i++) {
            conflicts.record(T1, "person-1", "Pat Lee", "LOGIN_GROUPS", List.of("RESTRICTED_STEWARD", "BUSINESS_ADMIN"),
                    List.of("RESTRICTED_STEWARD", "BUSINESS_ADMIN"), List.of("RESTRICTED_STEWARD_NOT_ADMIN"));
        }
        conflicts.ended(T1, "person-1", "Pat Lee", "LOGIN_GROUPS"); // the groups no longer conflict
        conflicts.ended(T1, "person-1", "Pat Lee", "LOGIN_GROUPS"); // later sign-ins: nothing open, nothing written

        List<AuditEvent> events = audited();
        assertEquals(List.of("ROLE_CONFLICT_DETECTED", "ROLE_CONFLICT_ENDED"), events.stream().map(AuditEvent::action).toList());
        AuditEvent ended = events.get(1);
        assertEquals(AuditOutcome.SUCCESS, ended.outcome());
        assertEquals("person-1", ended.actorId());
        assertEquals("person-1", ended.targetId());
        assertEquals("Pat Lee", ended.actorDisplayName());
        assertEquals(NOW, ended.occurredAt());
        JsonNode details = JSON.readTree(ended.safeDetailsJson());
        assertEquals("LOGIN_GROUPS", details.get("resolvedFrom").asString());
        assertEquals("[\"BUSINESS_ADMIN\",\"RESTRICTED_STEWARD\"]", details.get("withheldRoles").toString());
        assertEquals(NOW.minusSeconds(3600).toString(), details.get("firstSeenAt").asString());
        assertEquals(3, details.get("occurrences").asInt());
    }

    @Test
    void aSignInThatNeverConflictedWritesNothing() {
        when(store.endRoleConflicts(any(), any(), any())).thenReturn(List.of());
        conflicts.ended(T1, "person-2", null, "LOCAL_SIGN_IN");
        verifyNoInteractions(audit);
    }
}
