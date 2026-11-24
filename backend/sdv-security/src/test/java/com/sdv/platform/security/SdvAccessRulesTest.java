package com.sdv.platform.security;

import com.sdv.platform.security.separation.RoleSeparationPolicy;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0025, section 3: Vero's separation rules as data. */
class SdvAccessRulesTest {
    private static final Set<SdvRole> ADMINS =
            EnumSet.of(SdvRole.BUSINESS_ADMIN, SdvRole.IT_ADMIN, SdvRole.PLATFORM_OPERATIONS_ADMIN);

    private static RoleSeparationPolicy.Resolution<SdvRole> resolve(SdvRole... roles) {
        return SdvAccessRules.SEPARATION.resolve(EnumSet.copyOf(List.of(roles)));
    }

    @Test
    void restrictedStewardWithEachAdminRoleGetsNeither() {
        for (SdvRole admin : ADMINS) {
            var resolution = resolve(SdvRole.RESTRICTED_STEWARD, admin);
            assertTrue(resolution.conflicted(), admin.name());
            assertEquals(Set.of(), resolution.granted(), admin.name());
            assertEquals(EnumSet.of(SdvRole.RESTRICTED_STEWARD, admin), resolution.withheld(), admin.name());
            // Task 0062: an IT Admin with any document role breaks that rule too.
            assertEquals(admin == SdvRole.IT_ADMIN ? List.of(SdvAccessRules.RESTRICTED_STEWARD_NOT_ADMIN, SdvAccessRules.IT_ADMIN_NOT_DOCUMENT_ROLE)
                    : List.of(SdvAccessRules.RESTRICTED_STEWARD_NOT_ADMIN), resolution.brokenRules());
        }
    }

    @Test
    void restrictedStewardWithEveryCombinationOfAdminRolesGetsNoneOfThemAndKeepsTheRest() {
        List<SdvRole> admins = List.copyOf(ADMINS);
        for (int mask = 1; mask < (1 << admins.size()); mask++) {
            EnumSet<SdvRole> held = EnumSet.of(SdvRole.RESTRICTED_STEWARD, SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD);
            EnumSet<SdvRole> heldAdmins = EnumSet.noneOf(SdvRole.class);
            for (int i = 0; i < admins.size(); i++) if ((mask & (1 << i)) != 0) heldAdmins.add(admins.get(i));
            held.addAll(heldAdmins);
            var resolution = SdvAccessRules.SEPARATION.resolve(held);
            // Task 0062: with a Business Admin the Document Steward goes too; with an IT Admin every document role does.
            EnumSet<SdvRole> withheld = EnumSet.copyOf(heldAdmins);
            withheld.add(SdvRole.RESTRICTED_STEWARD);
            if (heldAdmins.contains(SdvRole.BUSINESS_ADMIN)) withheld.add(SdvRole.DOCUMENT_STEWARD);
            if (heldAdmins.contains(SdvRole.IT_ADMIN)) withheld.addAll(EnumSet.of(SdvRole.DOCUMENT_STEWARD, SdvRole.BUSINESS_USER));
            EnumSet<SdvRole> kept = EnumSet.copyOf(held);
            kept.removeAll(withheld);
            assertEquals(kept, resolution.granted(), held.toString());
            assertEquals(withheld, resolution.withheld(), held.toString());
        }
    }

    /** Task 0062: admins never open documents, so these are never held together; the rest of the roles stay. */
    @Test
    void anAdminNeverHoldsARoleThatOpensDocuments() {
        var steward = resolve(SdvRole.BUSINESS_ADMIN, SdvRole.DOCUMENT_STEWARD, SdvRole.BUSINESS_USER);
        assertEquals(List.of(SdvAccessRules.BUSINESS_ADMIN_NOT_STEWARD), steward.brokenRules());
        assertEquals(Set.of(SdvRole.BUSINESS_USER), steward.granted());
        for (SdvRole document : List.of(SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD)) {
            var it = resolve(SdvRole.IT_ADMIN, document, SdvRole.PLATFORM_OPERATIONS_ADMIN);
            assertEquals(List.of(SdvAccessRules.IT_ADMIN_NOT_DOCUMENT_ROLE), it.brokenRules(), document.name());
            assertEquals(Set.of(SdvRole.PLATFORM_OPERATIONS_ADMIN), it.granted(), document.name());
        }
    }

    @Test
    void allowedCombinationsAreUnchanged() {
        for (Set<SdvRole> held : List.of(
                EnumSet.of(SdvRole.RESTRICTED_STEWARD),
                EnumSet.of(SdvRole.RESTRICTED_STEWARD, SdvRole.DOCUMENT_STEWARD, SdvRole.BUSINESS_USER),
                EnumSet.of(SdvRole.BUSINESS_USER, SdvRole.BUSINESS_ADMIN),
                EnumSet.of(SdvRole.DOCUMENT_STEWARD, SdvRole.PLATFORM_OPERATIONS_ADMIN),
                EnumSet.of(SdvRole.BUSINESS_ADMIN, SdvRole.IT_ADMIN, SdvRole.PLATFORM_OPERATIONS_ADMIN),
                EnumSet.of(SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD))) {
            var resolution = SdvAccessRules.SEPARATION.resolve(held);
            assertFalse(resolution.conflicted(), held.toString());
            assertEquals(held, resolution.granted(), held.toString());
        }
    }

    @Test
    void eachForbiddenGrantIsListed() {
        for (SdvRole admin : ADMINS) {
            for (Capability c : List.of(Capability.VIEW_CONFIDENTIAL, Capability.VIEW_HIGHLY_CONFIDENTIAL, Capability.REQUEST_SENSITIVE_ACCESS)) {
                assertNotNull(SdvAccessRules.forbiddenGrant(admin, c), admin + " / " + c);
            }
            assertNull(SdvAccessRules.forbiddenGrant(admin, Capability.APPROVE_SENSITIVE_ACCESS), admin.name());
        }
        for (SdvRole steward : List.of(SdvRole.DOCUMENT_STEWARD, SdvRole.RESTRICTED_STEWARD)) {
            assertNotNull(SdvAccessRules.forbiddenGrant(steward, Capability.APPROVE_SENSITIVE_ACCESS), steward.name());
            assertNull(SdvAccessRules.forbiddenGrant(steward, Capability.VIEW_CONFIDENTIAL), steward.name());
        }
    }

    @Test
    void theFixedRoleMatrixBreaksNoForbiddenGrant() {
        for (SdvRole role : SdvRole.values()) {
            for (Capability capability : RoleCapabilities.capabilities(role)) {
                assertNull(SdvAccessRules.forbiddenGrant(role, capability), role + " / " + capability);
            }
        }
    }

    @Test
    void theRestrictedStewardHasEverythingADocumentStewardHas() {
        assertTrue(RoleCapabilities.capabilities(SdvRole.RESTRICTED_STEWARD)
                .containsAll(RoleCapabilities.capabilities(SdvRole.DOCUMENT_STEWARD)));
    }
}
