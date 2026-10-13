package com.sdv.platform.security.auth.enterprise;

import com.documentplatform.identity.AuthenticatedIdentity;
import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.IdentityClaims;
import com.documentplatform.identity.OidcConnectionBindingResolver;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.security.auth.SdvPrincipal;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Converts a verified enterprise JWT into SDV principals.
 * Human identity is normalized by the shared enterprise-identity module; SDV remains authoritative for role mapping.
 * Machine-to-machine ingestion-source identity remains an SDV-specific path.
 */
public final class EnterpriseJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final String tenantClaim;
    private final UUID configuredHumanTenantId;
    private final OidcConnectionBindingResolver connectionResolver;
    private final String providerId;
    private final String audience;
    private final String sourceIdClaim;
    private final String userGroupsClaim;
    private final OidcJwtIdentityNormalizer identityNormalizer;
    private final SdvGroupRoleMapper roleMapper;

    /** Backward-compatible constructor retained for frozen enterprise-JWT tests/configuration. */
    public EnterpriseJwtAuthenticationConverter(String tenantClaim, String rolesClaim, String sourceIdClaim) {
        this(
                tenantClaim,
                null,
                sourceIdClaim,
                rolesClaim,
                new OidcJwtIdentityNormalizer(
                        "enterprise-jwt",
                        new IdentityClaimConfig("preferred_username", "email", "name", rolesClaim)),
                new SdvGroupRoleMapper(Map.of(), true));
    }

    /** Backward-compatible group-mapping constructor retained for existing tests and deployments. */
    public EnterpriseJwtAuthenticationConverter(
            String tenantClaim,
            String sourceIdClaim,
            String userGroupsClaim,
            OidcJwtIdentityNormalizer identityNormalizer,
            SdvGroupRoleMapper roleMapper) {
        this(tenantClaim, null, sourceIdClaim, userGroupsClaim, identityNormalizer, roleMapper, null, "enterprise-jwt", "enterprise-jwt");
    }

    public EnterpriseJwtAuthenticationConverter(
            String tenantClaim,
            UUID configuredHumanTenantId,
            String sourceIdClaim,
            String userGroupsClaim,
            OidcJwtIdentityNormalizer identityNormalizer,
            SdvGroupRoleMapper roleMapper) {
        this(tenantClaim, configuredHumanTenantId, sourceIdClaim, userGroupsClaim, identityNormalizer, roleMapper, null, "enterprise-jwt", "enterprise-jwt");
    }

    public EnterpriseJwtAuthenticationConverter(
            String tenantClaim, UUID configuredHumanTenantId, String sourceIdClaim, String userGroupsClaim,
            OidcJwtIdentityNormalizer identityNormalizer, SdvGroupRoleMapper roleMapper,
            OidcConnectionBindingResolver connectionResolver, String providerId, String audience) {
        this.tenantClaim = requireClaimName(tenantClaim, "tenant claim");
        this.configuredHumanTenantId = configuredHumanTenantId;
        this.connectionResolver = connectionResolver;
        this.providerId = requireClaimName(providerId, "provider id");
        this.audience = requireClaimName(audience, "audience");
        this.sourceIdClaim = requireClaimName(sourceIdClaim, "source-id claim");
        this.userGroupsClaim = requireClaimName(userGroupsClaim, "groups claim");
        if (this.tenantClaim.equals(this.sourceIdClaim) || this.tenantClaim.equals(this.userGroupsClaim) || this.sourceIdClaim.equals(this.userGroupsClaim)) {
            throw new IllegalArgumentException("Enterprise JWT tenant and source-id claim names must be distinct.");
        }
        this.identityNormalizer = java.util.Objects.requireNonNull(identityNormalizer, "identityNormalizer");
        this.roleMapper = java.util.Objects.requireNonNull(roleMapper, "roleMapper");
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            String subject = requiredSubject(jwt);
            String sourceText = optionalStringClaim(jwt, sourceIdClaim, 100);

            if (sourceText != null) {
                // Machine-to-machine source identities stay tenant-explicit and do not inherit
                // the human OIDC connection's tenant binding.
                UUID tenantId = UUID.fromString(requiredStringClaim(jwt, tenantClaim, 100));
                if (!IdentityClaims.values(jwt.getClaim(userGroupsClaim)).isEmpty()) {
                    throw invalid("Enterprise JWT must identify exactly one SDV user principal or ingestion-source principal.");
                }
                UUID sourceId = UUID.fromString(sourceText);
                IngestionSourcePrincipal principal = new IngestionSourcePrincipal(tenantId, sourceId, subject);
                return new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("SDV_INGESTION_SOURCE")));
            }

            UUID tenantId = resolveHumanTenant(jwt);
            AuthenticatedIdentity identity = identityNormalizer.normalize(jwt);
            EnumSet<SdvRole> roles = roleMapper.map(identity.groups());
            SdvPrincipal principal = new SdvPrincipal(tenantId, identity.subject(), roles);
            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                    .toList();
            return new UsernamePasswordAuthenticationToken(principal, null, authorities);
        } catch (OAuth2AuthenticationException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw invalid("Enterprise JWT contains invalid SDV identity claims.");
        }
    }


    private UUID resolveHumanTenant(Jwt jwt) {
        if (connectionResolver != null) {
            if (jwt.getIssuer() == null) throw invalid("Enterprise JWT issuer is required for persisted tenant resolution.");
            var binding = connectionResolver.findActive(providerId, URI.create(jwt.getIssuer().toString()), audience);
            if (binding.isPresent()) return binding.get().tenantId();
            throw invalid("No ACTIVE persisted OIDC connection matches the authenticated issuer/provider/audience.");
        }
        if (configuredHumanTenantId != null) return configuredHumanTenantId;
        return UUID.fromString(requiredStringClaim(jwt, tenantClaim, 100));
    }

    private static String requiredSubject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 300) {
            throw invalid("Enterprise JWT subject is required.");
        }
        return subject.trim();
    }

    private static String requiredStringClaim(Jwt jwt, String name, int maxLength) {
        String value = optionalStringClaim(jwt, name, maxLength);
        if (value == null) throw invalid("Enterprise JWT is missing a required SDV identity claim.");
        return value;
    }

    private static String optionalStringClaim(Jwt jwt, String name, int maxLength) {
        Object raw = jwt.getClaim(name);
        if (raw == null) return null;
        if (!(raw instanceof String text)) throw invalid("Enterprise JWT SDV identity claim must be a string.");
        String value = text.trim();
        if (value.isEmpty()) return null;
        if (value.length() > maxLength) throw invalid("Enterprise JWT SDV identity claim is too long.");
        return value;
    }

    private static String requireClaimName(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException("Enterprise JWT " + label + " must be configured.");
        }
        return value.trim();
    }

    private static OAuth2AuthenticationException invalid(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), description);
    }
}
