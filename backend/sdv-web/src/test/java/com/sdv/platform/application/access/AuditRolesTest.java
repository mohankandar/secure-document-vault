package com.sdv.platform.application.access;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0054: the roles an open, print or download records: fixed role names only, sorted, none twice. */
class AuditRolesTest {
    @Test
    void onlyRoleNamesSortedAndOnce() {
        Clearance c = new Clearance(true, true, List.of("RESTRICTED_STEWARD", "BUSINESS_USER", "RESTRICTED_STEWARD", "not a role", "X\"Y"), true);
        assertEquals(List.of("BUSINESS_USER", "RESTRICTED_STEWARD"), AuditRoles.of(c));
        assertEquals(",\"roles\":[\"BUSINESS_USER\",\"RESTRICTED_STEWARD\"]", AuditRoles.field(c));
    }

    @Test
    void noRoleWritesNothing() {
        assertEquals("", AuditRoles.field(new Clearance(false, false)));
        assertEquals(List.of(), AuditRoles.of(null));
    }
}
