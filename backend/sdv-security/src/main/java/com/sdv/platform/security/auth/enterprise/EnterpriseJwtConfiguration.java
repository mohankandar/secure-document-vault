package com.sdv.platform.security.auth.enterprise;

import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.OidcConnectionBindingResolver;
import com.documentplatform.identity.spring.CachingOidcConnectionBindingResolver;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.documentplatform.identity.spring.RequiredAudienceValidator;
import com.sdv.platform.security.SdvRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Duration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.URI;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

@Configuration
@ConditionalOnProperty(prefix = "sdv.auth", name = "mode", havingValue = "enterprise-jwt")
public class EnterpriseJwtConfiguration {
    @Bean
    JwtDecoder enterpriseJwtDecoder(
            @Value("${sdv.auth.jwt.issuer:}") String issuer,
            @Value("${sdv.auth.jwt.jwk-set-uri:}") String jwkSetUri,
            @Value("${sdv.auth.jwt.audience:}") String audience,
            @Value("${sdv.auth.jwt.max-token-lifetime-seconds:0}") long maxTokenLifetimeSeconds) {
        String normalizedIssuer = requireSecureUri(issuer, "sdv.auth.jwt.issuer");
        String normalizedJwkSetUri = requireSecureUri(jwkSetUri, "sdv.auth.jwt.jwk-set-uri");
        String normalizedAudience = requireText(audience, "sdv.auth.jwt.audience");

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(normalizedJwkSetUri).build();
        OAuth2TokenValidator<Jwt> issuerAndTime = JwtValidators.createDefaultWithIssuer(normalizedIssuer);
        OAuth2TokenValidator<Jwt> audienceValidator = new RequiredAudienceValidator(normalizedAudience);
        if (maxTokenLifetimeSeconds > 0) {
            OAuth2TokenValidator<Jwt> lifetimeValidator = new MaximumTokenLifetimeValidator(Duration.ofSeconds(maxTokenLifetimeSeconds));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerAndTime, audienceValidator, lifetimeValidator));
        } else {
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerAndTime, audienceValidator));
        }
        return decoder;
    }

    @Bean
    EnterpriseJwtAuthenticationConverter enterpriseJwtAuthenticationConverter(
            @Value("${sdv.auth.jwt.tenant-claim:sdv_tenant_id}") String tenantClaim,
            @Value("${sdv.auth.jwt.tenant-id:}") String configuredTenantId,
            @Value("${sdv.auth.jwt.groups-claim:groups}") String groupsClaim,
            @Value("${sdv.auth.jwt.source-id-claim:sdv_source_id}") String sourceIdClaim,
            @Value("${sdv.auth.jwt.provider-id:customer-oidc}") String providerId,
            @Value("${sdv.auth.jwt.audience:}") String audience,
            @Value("${sdv.auth.jwt.username-claim:preferred_username}") String usernameClaim,
            @Value("${sdv.auth.jwt.email-claim:email}") String emailClaim,
            @Value("${sdv.auth.jwt.display-name-claim:name}") String displayNameClaim,
            @Value("${sdv.auth.jwt.role-mappings.business-user:}") String businessUserGroups,
            @Value("${sdv.auth.jwt.role-mappings.document-steward:}") String documentStewardGroups,
            @Value("${sdv.auth.jwt.role-mappings.business-admin:}") String businessAdminGroups,
            @Value("${sdv.auth.jwt.role-mappings.tenant-it-admin:}") String tenantItAdminGroups,
            @Value("${sdv.auth.jwt.role-mappings.platform-operations-admin:}") String platformOperationsGroups,
            @Value("${sdv.auth.jwt.persistent-connection-enabled:true}") boolean persistentConnectionEnabled,
            ObjectProvider<OidcConnectionBindingResolver> connectionResolverProvider,
            @Value("${sdv.auth.jwt.legacy-direct-role-claim-enabled:true}") boolean legacyDirectRoleClaimEnabled,
            @Value("${sdv.auth.jwt.roles-claim:sdv_roles}") String legacyRolesClaim) {

        // Compatibility: frozen deployments can continue to present sdv_roles until tenant group mappings are configured.
        String effectiveGroupsClaim = hasConfiguredMappings(
                businessUserGroups, documentStewardGroups, businessAdminGroups, tenantItAdminGroups, platformOperationsGroups)
                ? groupsClaim
                : (legacyDirectRoleClaimEnabled ? legacyRolesClaim : groupsClaim);

        var identityNormalizer = new OidcJwtIdentityNormalizer(
                providerId,
                new IdentityClaimConfig(usernameClaim, emailClaim, displayNameClaim, effectiveGroupsClaim));

        EnumMap<SdvRole, Set<String>> mappings = new EnumMap<>(SdvRole.class);
        mappings.put(SdvRole.BUSINESS_USER, SdvGroupRoleMapper.split(businessUserGroups));
        mappings.put(SdvRole.DOCUMENT_STEWARD, SdvGroupRoleMapper.split(documentStewardGroups));
        mappings.put(SdvRole.BUSINESS_ADMIN, SdvGroupRoleMapper.split(businessAdminGroups));
        mappings.put(SdvRole.TENANT_IT_ADMIN, SdvGroupRoleMapper.split(tenantItAdminGroups));
        mappings.put(SdvRole.PLATFORM_OPERATIONS_ADMIN, SdvGroupRoleMapper.split(platformOperationsGroups));

        OidcConnectionBindingResolver persistentResolver = null;
        if (persistentConnectionEnabled) {
            persistentResolver = connectionResolverProvider.getIfAvailable();
            if (persistentResolver == null) throw new IllegalStateException("Persistent OIDC connection resolution is enabled but no resolver is available.");
            persistentResolver = new CachingOidcConnectionBindingResolver(persistentResolver, Duration.ofSeconds(30), 256);
        }
        return new EnterpriseJwtAuthenticationConverter(
                tenantClaim, optionalUuid(configuredTenantId, "sdv.auth.jwt.tenant-id"), sourceIdClaim, effectiveGroupsClaim,
                identityNormalizer, new SdvGroupRoleMapper(mappings, legacyDirectRoleClaimEnabled),
                persistentResolver, providerId, normalizedAudience(audience));
    }


    private static String normalizedAudience(String value) { return requireText(value, "sdv.auth.jwt.audience"); }

    private static java.util.UUID optionalUuid(String value, String property) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.util.UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(property + " must be a valid UUID when configured.");
        }
    }

    private static boolean hasConfiguredMappings(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return true;
        return false;
    }

    private static String requireSecureUri(String value, String property) {
        String text = requireText(value, property);
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(property + " must be an absolute HTTPS URI (HTTP is allowed only for loopback acceptance).");
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalStateException(property + " must be an absolute HTTPS URI (HTTP is allowed only for loopback acceptance).");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()));
        if (!https && !loopbackHttp) {
            throw new IllegalStateException(property + " must use HTTPS outside loopback acceptance.");
        }
        return text;
    }

    private static String requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required when sdv.auth.mode=enterprise-jwt.");
        }
        return value.trim();
    }
}
