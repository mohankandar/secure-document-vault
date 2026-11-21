package com.sdv.platform.security.auth.enterprise;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.documentplatform.identity.AuthenticatedIdentity;
import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.IdentityClaims;
import com.documentplatform.identity.OidcConnectionBindingResolver;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.security.auth.RoleConflictReporter;
import com.sdv.platform.security.auth.RoleSeparationGuard;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SignInAnswer;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
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
    private final RoleSeparationGuard separationGuard;

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
        this(tenantClaim, configuredHumanTenantId, sourceIdClaim, userGroupsClaim, identityNormalizer, roleMapper,
                connectionResolver, providerId, audience, null);
    }

    /**
     * Task 0025: {@code conflictReporter} records sign-ins whose groups name roles that can never be held together.
     * Without one (the older constructors), such a sign-in is refused.
     */
    public EnterpriseJwtAuthenticationConverter(
            String tenantClaim, UUID configuredHumanTenantId, String sourceIdClaim, String userGroupsClaim,
            OidcJwtIdentityNormalizer identityNormalizer, SdvGroupRoleMapper roleMapper,
            OidcConnectionBindingResolver connectionResolver, String providerId, String audience,
            RoleConflictReporter conflictReporter) {
        this.separationGuard = new RoleSeparationGuard(conflictReporter);
        this.tenantClaim = requireClaimName(tenantClaim, "tenant claim");
        this.configuredHumanTenantId = configuredHumanTenantId;
        this.connectionResolver = connectionResolver;
        this.providerId = requireClaimName(providerId, "provider id");
        this.audience = requireClaimName(audience, "audience");
        this.sourceIdClaim = requireClaimName(sourceIdClaim, "source-id claim");
        this.userGroupsClaim = requireClaimName(userGroupsClaim, "groups claim");
        if (this.tenantClaim.equals(this.sourceIdClaim) || this.tenantClaim.equals(this.userGroupsClaim) || this.sourceIdClaim.equals(this.userGroupsClaim)) {
            throw new StartupFailure(SdvErrorCode.SYS_005, "Enterprise JWT tenant and source-id claim names must be distinct.");
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
                UUID tenantId = uuidClaim(requiredStringClaim(jwt, tenantClaim, 100), tenantClaim);
                if (!IdentityClaims.values(jwt.getClaim(userGroupsClaim)).isEmpty()) {
                    throw claims("both claim=" + sourceIdClaim + " and claim=" + userGroupsClaim
                            + " are present; a token is a person or a sending system, not both");
                }
                UUID sourceId = uuidClaim(sourceText, sourceIdClaim);
                IngestionSourcePrincipal principal = new IngestionSourcePrincipal(tenantId, sourceId, subject);
                return new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("SDV_INGESTION_SOURCE")));
            }

            UUID tenantId = resolveHumanTenant(jwt);
            AuthenticatedIdentity identity = identityNormalizer.normalize(jwt);
            EnumSet<SdvRole> named = roleMapper.map(identity.groups());
            // Task 0025, rule 1: roles that can never be held together are withheld and reported.
            EnumSet<SdvRole> roles;
            try {
                roles = separationGuard.apply(tenantId, identity.subject(), identity.displayName(),
                        RoleConflictReporter.Source.LOGIN_GROUPS, named);
            } catch (RoleSeparationGuard.RefusedException ex) {
                // Task 0038: ROL-004, ROL-005 or AUT-007, with its fixed sentence. The log names no subject or group.
                var answer = ex.answer();
                throw new SignInRefusedException(answer.code(), answer.message(), "tenant=" + tenantId + " " + answer.logDetail());
            }
            // Name and email are display-only; they come from the claims configured for the shared normalizer
            // (defaults: "name" and "email") and never influence roles or tenant.
            SdvPrincipal principal = new SdvPrincipal(tenantId, identity.subject(), roles, identity.displayName(), identity.email());
            List<SimpleGrantedAuthority> authorities = roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                    .toList();
            return new UsernamePasswordAuthenticationToken(principal, null, authorities);
        } catch (OAuth2AuthenticationException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            // The shared normalizer refused a claim; its message can hold a claim's value, so only the class is logged.
            throw claims("the identity claims could not be read (" + ex.getClass().getSimpleName() + ")");
        }
    }


    private UUID resolveHumanTenant(Jwt jwt) {
        if (connectionResolver != null) {
            if (jwt.getIssuer() == null) throw claims("claim=iss is missing");
            URI issuer = URI.create(jwt.getIssuer().toString());
            var binding = connectionResolver.findActive(providerId, issuer, audience);
            if (binding.isPresent()) return binding.get().tenantId();
            // The issuer was already checked against Vero's own issuer setting, so its host is Vero's setting too.
            throw new SignInRefusedException(SdvErrorCode.AUT_006, SignInAnswer.NO_CONNECTION,
                    "no ACTIVE sign-in connection for issuer host=" + issuer.getHost() + " provider=" + providerId
                            + " audience=" + audience);
        }
        if (configuredHumanTenantId != null) return configuredHumanTenantId;
        return uuidClaim(requiredStringClaim(jwt, tenantClaim, 100), tenantClaim);
    }

    private static String requiredSubject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) throw claims("claim=sub is missing");
        if (subject.length() > 300) throw claims("claim=sub is longer than 300 characters");
        return subject.trim();
    }

    private static String requiredStringClaim(Jwt jwt, String name, int maxLength) {
        String value = optionalStringClaim(jwt, name, maxLength);
        if (value == null) throw claims("claim=" + name + " is missing");
        return value;
    }

    private static String optionalStringClaim(Jwt jwt, String name, int maxLength) {
        Object raw = jwt.getClaim(name);
        if (raw == null) return null;
        if (!(raw instanceof String text)) throw claims("claim=" + name + " is not a string");
        String value = text.trim();
        if (value.isEmpty()) return null;
        if (value.length() > maxLength) throw claims("claim=" + name + " is longer than " + maxLength + " characters");
        return value;
    }

    private static UUID uuidClaim(String value, String name) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw claims("claim=" + name + " is not a UUID");
        }
    }

    /** Task 0038 (SDV-AUT-005): the log detail names the claim, never its value. */
    private static SignInRefusedException claims(String detail) {
        return new SignInRefusedException(SdvErrorCode.AUT_005, SignInAnswer.CLAIMS_NOT_USABLE, detail);
    }

    private static String requireClaimName(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new StartupFailure(SdvErrorCode.SYS_005, "Enterprise JWT " + label + " must be configured.");
        }
        return value.trim();
    }

}
