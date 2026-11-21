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
            EnumSet.of(SdvRole.BUSINESS_ADMIN, SdvRole.TENANT_IT_ADMIN, SdvRole.PLATFORM_OPERATIONS_ADMIN);

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
            assertEquals(List.of(SdvAccessRules.RESTRICTED_STEWARD_NOT_ADMIN), resolution.brokenRules());
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
            assertEquals(EnumSet.of(SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD), resolution.granted(), held.toString());
            EnumSet<SdvRole> withheld = EnumSet.copyOf(heldAdmins);
            withheld.add(SdvRole.RESTRICTED_STEWARD);
            assertEquals(withheld, resolution.withheld(), held.toString());
        }
    }

    @Test
    void allowedCombinationsAreUnchanged() {
        for (Set<SdvRole> held : List.of(
                EnumSet.of(SdvRole.RESTRICTED_STEWARD),
                EnumSet.of(SdvRole.RESTRICTED_STEWARD, SdvRole.DOCUMENT_STEWARD, SdvRole.BUSINESS_USER),
                EnumSet.of(SdvRole.DOCUMENT_STEWARD, SdvRole.BUSINESS_ADMIN),
                EnumSet.of(SdvRole.BUSINESS_ADMIN, SdvRole.TENANT_IT_ADMIN, SdvRole.PLATFORM_OPERATIONS_ADMIN),
                EnumSet.of(SdvRole.BUSINESS_USER, SdvRole.TENANT_IT_ADMIN))) {
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
