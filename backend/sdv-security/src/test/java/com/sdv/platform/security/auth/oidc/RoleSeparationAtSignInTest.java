package com.sdv.platform.security.auth.oidc;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.RoleConflictReporter;
import com.sdv.platform.security.auth.SdvPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.sdv.platform.security.auth.oidc.OidcJwtAuthenticationConverterTest.ENTITY_A;
import static com.sdv.platform.security.auth.oidc.OidcJwtAuthenticationConverterTest.ENTITY_B;
import static com.sdv.platform.security.auth.oidc.OidcJwtAuthenticationConverterTest.assertCode;
import static com.sdv.platform.security.auth.oidc.OidcJwtAuthenticationConverterTest.person;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0025, rule 1, on platform-core's group grants (task 0041, owner answer 8): the separation rules hold whatever
 * loaded the grants (setup, or a grant change), because they run at every sign-in on the roles the grants give, per
 * entity. A conflict is withheld and reported; with nothing left, or when it can't be recorded, the sign-in is refused.
 */
class RoleSeparationAtSignInTest {
    private OidcJwtAuthenticationConverterTest grantsLoadedBySetup() {
        var f = new OidcJwtAuthenticationConverterTest();
        f.grant(ENTITY_A, "vero-users", "BUSINESS_USER");
        f.grant(ENTITY_A, "vero-stewards", "DOCUMENT_STEWARD");
        f.grant(ENTITY_A, "vero-restricted", "RESTRICTED_STEWARD");
        f.grant(ENTITY_A, "vero-business-admins", "BUSINESS_ADMIN");
        f.grant(ENTITY_A, "vero-it", "IT_ADMIN");
        return f;
    }

    @Test
    void restrictedStewardAndAnAdminGrantGiveNeitherAndAreReported() {
        // Task 0062: an IT Admin with any document role gets neither, so with "vero-it" nothing is left.
        var it = grantsLoadedBySetup();
        assertEquals("SDV-ROL-004", assertThrows(SignInRefusedException.class, () -> it.converter().convert(
                person(List.of("vero-restricted", "vero-it", "vero-users"), java.util.Map.of()))).answer().code().code());
        assertEquals(1, it.conflicts.size());
        for (String admin : List.of("vero-business-admins")) {
            var f = grantsLoadedBySetup();
            var auth = f.converter().convert(person(List.of("vero-restricted", admin, "vero-users"), java.util.Map.of("name", "Pat Lee")));
            assertEquals(Set.of(SdvRole.BUSINESS_USER), ((SdvPrincipal) auth.getPrincipal()).roles(), admin);
            assertTrue(auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().contains("ADMIN")
                    || a.getAuthority().contains("RESTRICTED")), admin);
            assertEquals(1, f.conflicts.size(), admin);
            var conflict = f.conflicts.get(0);
            assertEquals(RoleConflictReporter.Source.LOGIN_GROUPS, conflict.source());
            assertEquals(ENTITY_A, conflict.tenantId());
            assertEquals("person-1", conflict.subject());
            assertEquals("Pat Lee", conflict.displayName());
            assertFalse(conflict.withheld().contains(SdvRole.BUSINESS_USER));
        }
    }

    @Test
    void oneGroupGrantedBothRolesIsAConflictForEveryMember() {
        var f = new OidcJwtAuthenticationConverterTest();
        f.grant(ENTITY_A, "legal", "RESTRICTED_STEWARD", "BUSINESS_ADMIN", "BUSINESS_USER");
        var p = (SdvPrincipal) f.converter().convert(person(List.of("legal"), java.util.Map.of())).getPrincipal();
        assertEquals(Set.of(SdvRole.BUSINESS_USER), p.roles());
        assertEquals(1, f.conflicts.size());
    }

    @Test
    void separationIsPerEntity() {
        var f = new OidcJwtAuthenticationConverterTest();
        f.grant(ENTITY_A, "hc-reviewers", "RESTRICTED_STEWARD");
        f.grant(ENTITY_B, "acme-admins", "BUSINESS_ADMIN");
        f.requestedEntity = ENTITY_A.toString();
        var a = (SdvPrincipal) f.converter().convert(person(List.of("hc-reviewers", "acme-admins"), java.util.Map.of())).getPrincipal();
        assertEquals(Set.of(SdvRole.RESTRICTED_STEWARD), a.roles());
        f.requestedEntity = ENTITY_B.toString();
        var b = (SdvPrincipal) f.converter().convert(person(List.of("hc-reviewers", "acme-admins"), java.util.Map.of())).getPrincipal();
        assertEquals(Set.of(SdvRole.BUSINESS_ADMIN), b.roles());
        assertTrue(f.conflicts.isEmpty(), "different entities never conflict");
    }

    @Test
    void withNoOtherRoleTheSignInIsRefusedWithItsCode() {
        var f = grantsLoadedBySetup();
        assertCode(SdvErrorCode.ROL_004, 401, () -> f.converter().convert(person(List.of("vero-restricted", "vero-business-admins"), java.util.Map.of())));
        assertEquals(1, f.conflicts.size(), "recorded before refusing");
    }

    @Test
    void aConflictThatCannotBeRecordedRefusesTheSignIn() {
        var f = grantsLoadedBySetup();
        f.failReports = true;
        assertCode(SdvErrorCode.ROL_005, 401, () -> f.converter().convert(person(List.of("vero-restricted", "vero-it", "vero-users"), java.util.Map.of())));
    }

    @Test
    void restrictedStewardAloneOrWithStewardRolesIsGiven() {
        var f = grantsLoadedBySetup();
        assertEquals(Set.of(SdvRole.RESTRICTED_STEWARD, SdvRole.DOCUMENT_STEWARD, SdvRole.BUSINESS_USER),
                ((SdvPrincipal) f.converter().convert(person(List.of("vero-restricted", "vero-stewards", "vero-users"), java.util.Map.of())).getPrincipal()).roles());
        assertTrue(f.conflicts.isEmpty());
        assertThrows(SignInRefusedException.class, () -> f.converter().convert(person(List.of("RESTRICTED_STEWARD"), java.util.Map.of())),
                "a group named like a role gives nothing without a grant");
    }
}
