package com.sdv.platform.security.separation;

import com.sdv.platform.domain.error.InternalCheckFailed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0025, 3a: the generic rule, with plain text role names (as LexaVault might use it), not Vero's roles. */
class RoleSeparationPolicyTest {
    private final RoleSeparationPolicy<String> policy = new RoleSeparationPolicy<>(List.of(
            new RoleSeparationRule<>("AUDITOR_NOT_APPROVER", Set.of("auditor"), Set.of("approver", "owner")),
            new RoleSeparationRule<>("CLERK_NOT_OWNER", Set.of("clerk"), Set.of("owner"))));

    @Test
    void aBrokenRuleWithholdsBothSidesAndKeepsTheRest() {
        var r = policy.resolve(Set.of("auditor", "approver", "reader"));
        assertEquals(Set.of("reader"), r.granted());
        assertEquals(Set.of("auditor", "approver"), r.withheld());
        assertEquals(List.of("AUDITOR_NOT_APPROVER"), r.brokenRules());
    }

    @Test
    void severalBrokenRulesAreAllReportedInRuleOrder() {
        var r = policy.resolve(Set.of("auditor", "clerk", "owner"));
        assertEquals(Set.of(), r.granted());
        assertEquals(List.of("AUDITOR_NOT_APPROVER", "CLERK_NOT_OWNER"), r.brokenRules());
    }

    @Test
    void rolesOnOneSideOnlyAreKept() {
        var r = policy.resolve(Set.of("approver", "owner"));
        assertFalse(r.conflicted());
        assertEquals(Set.of("approver", "owner"), r.granted());
    }

    @Test
    void badRulesAndInputAreRefused() {
        assertThrows(InternalCheckFailed.class, () -> new RoleSeparationRule<>("X", Set.of("a"), Set.of("a")));
        assertThrows(InternalCheckFailed.class, () -> new RoleSeparationRule<>("X", Set.of(), Set.of("a")));
        assertThrows(InternalCheckFailed.class, () -> new RoleSeparationRule<>(" ", Set.of("a"), Set.of("b")));
        assertThrows(InternalCheckFailed.class, () -> new RoleSeparationPolicy<>(List.of(
                new RoleSeparationRule<>("X", Set.of("a"), Set.of("b")),
                new RoleSeparationRule<>("X", Set.of("c"), Set.of("d")))));
        assertThrows(NullPointerException.class, () -> policy.resolve(null));
    }

    @Test
    void forbiddenGrantsAreGenericToo() {
        List<ForbiddenGrant<String, String>> grants = List.of(new ForbiddenGrant<>("G", Set.of("owner"), Set.of("audit")));
        assertNotNull(ForbiddenGrant.find(grants, "owner", "audit"));
        assertNull(ForbiddenGrant.find(grants, "owner", "read"));
        assertNull(ForbiddenGrant.find(grants, "auditor", "audit"));
    }
}
