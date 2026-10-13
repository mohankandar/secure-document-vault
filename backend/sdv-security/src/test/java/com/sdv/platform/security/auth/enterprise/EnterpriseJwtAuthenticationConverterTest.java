package com.sdv.platform.security.auth.enterprise;

import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;

import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.security.auth.SdvPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EnterpriseJwtAuthenticationConverterTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final EnterpriseJwtAuthenticationConverter converter =
            new EnterpriseJwtAuthenticationConverter("sdv_tenant_id", "sdv_roles", "sdv_source_id");

    @Test
    void mapsUserRolesIntoFrozenSdvPrincipal() {
        var auth = converter.convert(jwt(Map.of(
                "sdv_tenant_id", TENANT.toString(),
                "sdv_roles", List.of("BUSINESS_USER", "DOCUMENT_STEWARD"))));

        assertInstanceOf(SdvPrincipal.class, auth.getPrincipal());
        SdvPrincipal principal = (SdvPrincipal) auth.getPrincipal();
        assertEquals(TENANT, principal.tenantId());
        assertEquals("enterprise-user", principal.subject());
        assertEquals(Set.of(SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD), principal.roles());
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_USER")));
    }

    @Test
    void mapsCustomerGroupsIntoSdvRoles() {
        var mapper = new SdvGroupRoleMapper(Map.of(
                SdvRole.BUSINESS_USER, Set.of("claims-users"),
                SdvRole.DOCUMENT_STEWARD, Set.of("records-stewards")), false);
        var groupConverter = new EnterpriseJwtAuthenticationConverter(
                "sdv_tenant_id",
                "sdv_source_id",
                "groups",
                new OidcJwtIdentityNormalizer(
                        "customer-oidc",
                        new IdentityClaimConfig("preferred_username", "email", "name", "groups")),
                mapper);

        var auth = groupConverter.convert(jwt(Map.of(
                "sdv_tenant_id", TENANT.toString(),
                "groups", List.of("claims-users"))));

        SdvPrincipal principal = (SdvPrincipal) auth.getPrincipal();
        assertEquals(Set.of(SdvRole.BUSINESS_USER), principal.roles());
    }

    @Test
    void failsClosedWhenCustomerGroupsHaveNoSdvMapping() {
        var mapper = new SdvGroupRoleMapper(Map.of(
                SdvRole.BUSINESS_USER, Set.of("claims-users")), false);
        var groupConverter = new EnterpriseJwtAuthenticationConverter(
                "sdv_tenant_id",
                "sdv_source_id",
                "groups",
                new OidcJwtIdentityNormalizer(
                        "customer-oidc",
                        new IdentityClaimConfig("preferred_username", "email", "name", "groups")),
                mapper);

        assertThrows(OAuth2AuthenticationException.class, () -> groupConverter.convert(jwt(Map.of(
                "sdv_tenant_id", TENANT.toString(),
                "groups", List.of("unrelated-group")))));
    }

    @Test
    void resolvesHumanTenantFromConfiguredOidcConnectionWhenTokenHasNoTenantClaim() {
        var mapper = new SdvGroupRoleMapper(Map.of(
                SdvRole.DOCUMENT_STEWARD, Set.of("DocumentStewards")), false);
        var groupConverter = new EnterpriseJwtAuthenticationConverter(
                "sdv_tenant_id",
                TENANT,
                "sdv_source_id",
                "groups",
                new OidcJwtIdentityNormalizer(
                        "customer-oidc",
                        new IdentityClaimConfig("preferred_username", "email", "name", "groups")),
                mapper);

        var auth = groupConverter.convert(jwt(Map.of("groups", List.of("DocumentStewards"))));
        SdvPrincipal principal = (SdvPrincipal) auth.getPrincipal();
        assertEquals(TENANT, principal.tenantId());
        assertEquals(Set.of(SdvRole.DOCUMENT_STEWARD), principal.roles());
    }

    @Test
    void mapsM2mSourceTokenIntoFrozenSourcePrincipal() {
        var auth = converter.convert(jwt(Map.of(
                "sdv_tenant_id", TENANT.toString(),
                "sdv_source_id", SOURCE.toString())));

        assertInstanceOf(IngestionSourcePrincipal.class, auth.getPrincipal());
        IngestionSourcePrincipal principal = (IngestionSourcePrincipal) auth.getPrincipal();
        assertEquals(TENANT, principal.tenantId());
        assertEquals(SOURCE, principal.sourceId());
        assertEquals("enterprise-user", principal.subject());
    }

    @Test
    void failsClosedWhenTokenTriesToBeBothUserAndSource() {
        assertThrows(OAuth2AuthenticationException.class, () -> converter.convert(jwt(Map.of(
                "sdv_tenant_id", TENANT.toString(),
                "sdv_source_id", SOURCE.toString(),
                "sdv_roles", List.of("BUSINESS_USER")))));
    }

    @Test
    void failsClosedOnUnknownRole() {
        assertThrows(OAuth2AuthenticationException.class, () -> converter.convert(jwt(Map.of(
                "sdv_tenant_id", TENANT.toString(),
                "sdv_roles", List.of("SUPER_ADMIN")))));
    }

    @Test
    void failsClosedWhenTenantClaimMissing() {
        assertThrows(OAuth2AuthenticationException.class, () -> converter.convert(jwt(Map.of(
                "sdv_roles", List.of("BUSINESS_USER")))));
    }

    private static Jwt jwt(Map<String, Object> sdvClaims) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("enterprise-user")
                .issuer("https://issuer.example")
                .audience(List.of("sdv-api"))
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300));
        sdvClaims.forEach(builder::claim);
        return builder.build();
    }
}
