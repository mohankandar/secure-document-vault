package com.sdv.platform.security.auth.enterprise;

import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.RoleConflictReporter;
import com.sdv.platform.security.auth.SdvPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0025, rule 1, on the login-group path (SdvGroupRoleMapper through the enterprise JWT converter). */
class RoleSeparationAtSignInTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final List<RoleConflictReporter.RoleConflict> reported = new ArrayList<>();

    private EnterpriseJwtAuthenticationConverter converter(RoleConflictReporter reporter, boolean directRoles) {
        var mapper = new SdvGroupRoleMapper(Map.of(
                SdvRole.BUSINESS_USER, Set.of("vero-users"),
                SdvRole.DOCUMENT_STEWARD, Set.of("vero-stewards"),
                SdvRole.RESTRICTED_STEWARD, Set.of("vero-restricted"),
                SdvRole.BUSINESS_ADMIN, Set.of("vero-business-admins"),
                SdvRole.TENANT_IT_ADMIN, Set.of("vero-it"),
                SdvRole.PLATFORM_OPERATIONS_ADMIN, Set.of("vero-ops")), directRoles);
        return new EnterpriseJwtAuthenticationConverter("sdv_tenant_id", TENANT, "sdv_source_id", "groups",
                new OidcJwtIdentityNormalizer("customer-oidc",
                        new IdentityClaimConfig("preferred_username", "email", "name", "groups")),
                mapper, null, "customer-oidc", "sdv-api", reporter);
    }

    private static Jwt jwt(List<String> groups) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("person-1").issuer("https://issuer.example")
                .audience(List.of("sdv-api")).issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300))
                .claim("groups", groups).claim("name", "Pat Lee").build();
    }

    private static Set<SdvRole> roles(org.springframework.security.authentication.AbstractAuthenticationToken auth) {
        return ((SdvPrincipal) auth.getPrincipal()).roles();
    }

    @Test
    void restrictedStewardAndAnAdminGroupGiveNeitherAndAreReported() {
        for (String admin : List.of("vero-business-admins", "vero-it", "vero-ops")) {
            reported.clear();
            var auth = converter(reported::add, false).convert(jwt(List.of("vero-restricted", admin, "vero-users")));
            assertEquals(Set.of(SdvRole.BUSINESS_USER), roles(auth), admin);
            assertTrue(auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().contains("ADMIN")
                    || a.getAuthority().contains("RESTRICTED") || a.getAuthority().contains("PLATFORM")), admin);
            assertEquals(1, reported.size(), admin);
            assertEquals(RoleConflictReporter.Source.LOGIN_GROUPS, reported.get(0).source());
            assertEquals(TENANT, reported.get(0).tenantId());
            assertEquals("person-1", reported.get(0).subject());
            assertEquals("Pat Lee", reported.get(0).displayName());
            assertFalse(reported.get(0).withheld().contains(SdvRole.BUSINESS_USER));
        }
    }

    @Test
    void withNoOtherRoleTheSignInIsRefusedWithItsCode() {
        var ex = assertThrows(OAuth2AuthenticationException.class,
                () -> converter(reported::add, false).convert(jwt(List.of("vero-restricted", "vero-business-admins"))));
        assertTrue(ex.getMessage().startsWith("SDV-ROL-004"), ex.getMessage());
        assertEquals(1, reported.size(), "recorded before refusing");
    }

    @Test
    void aConflictThatCannotBeRecordedRefusesTheSignIn() {
        RoleConflictReporter failing = c -> { throw new IllegalStateException("db down"); };
        var ex = assertThrows(OAuth2AuthenticationException.class,
                () -> converter(failing, false).convert(jwt(List.of("vero-restricted", "vero-it", "vero-users"))));
        assertTrue(ex.getMessage().startsWith("SDV-ROL-005"), ex.getMessage());
        assertThrows(OAuth2AuthenticationException.class,
                () -> converter(null, false).convert(jwt(List.of("vero-restricted", "vero-it", "vero-users"))));
    }

    @Test
    void restrictedStewardAloneOrWithStewardRolesIsGiven() {
        assertEquals(Set.of(SdvRole.RESTRICTED_STEWARD),
                roles(converter(reported::add, false).convert(jwt(List.of("vero-restricted")))));
        assertEquals(Set.of(SdvRole.RESTRICTED_STEWARD, SdvRole.DOCUMENT_STEWARD, SdvRole.BUSINESS_USER),
                roles(converter(reported::add, false).convert(jwt(List.of("vero-restricted", "vero-stewards", "vero-users")))));
        assertTrue(reported.isEmpty());
    }

    @Test
    void aDirectRoleClaimNeverGivesRestrictedSteward() {
        var direct = converter(reported::add, true);
        assertThrows(OAuth2AuthenticationException.class, () -> direct.convert(jwt(List.of("RESTRICTED_STEWARD"))));
        assertEquals(Set.of(SdvRole.DOCUMENT_STEWARD),
                roles(direct.convert(jwt(List.of("RESTRICTED_STEWARD", "DOCUMENT_STEWARD")))));
    }
}
