package com.sdv.platform.security.auth.oidc;

import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.admission.ActiveEntityCandidates;
import com.documentplatform.identity.admission.AdmissionSettings;
import com.documentplatform.identity.admission.EntityGroupGrants;
import com.documentplatform.identity.admission.EntityMembershipResolver;
import com.documentplatform.identity.admission.PlatformGroupGrants;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.documentplatform.identity.spring.RequiredAudienceValidator;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.security.auth.RoleConflictReporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;

/**
 * OIDC sign-in ({@code sdv.auth.mode=enterprise-jwt}) on platform-core identity (task 0041). Spring checks the token
 * (signature, issuer, audience, time); platform-core's idn_ tables, loaded by setup, say which entity and roles a
 * person has and which entity and source a sending system delivers to. No group list or entity ID is configured here.
 */
@Configuration
@ConditionalOnProperty(prefix = "sdv.auth", name = "mode", havingValue = "enterprise-jwt")
public class OidcJwtConfiguration {

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
        // Task 0038: "the sign-in hub's keys can't be fetched" is told apart from a wrong token (SDV-AUT-004).
        return new SignInKeysAwareJwtDecoder(decoder, normalizedJwkSetUri);
    }

    @Bean
    OidcJwtAuthenticationConverter enterpriseJwtAuthenticationConverter(
            @Value("${sdv.auth.jwt.provider-id:customer-oidc}") String providerId,
            @Value("${sdv.auth.jwt.audience:}") String audience,
            @Value("${sdv.auth.jwt.groups-claim:groups}") String groupsClaim,
            @Value("${sdv.auth.jwt.client-id-claims:azp,client_id,cid}") String clientIdClaims,
            @Value("${sdv.auth.jwt.entity-claim:sdv_entity_id}") String entityClaim,
            @Value("${sdv.auth.jwt.source-id-claim:sdv_source_id}") String sourceIdClaim,
            @Value("${sdv.auth.jwt.source-key-claim:sdv_source_key}") String sourceKeyClaim,
            @Value("${sdv.auth.jwt.username-claim:preferred_username}") String usernameClaim,
            @Value("${sdv.auth.jwt.email-claim:email}") String emailClaim,
            @Value("${sdv.auth.jwt.display-name-claim:name}") String displayNameClaim,
            ObjectProvider<EntityGroupGrants> grants,
            ObjectProvider<ActiveEntityCandidates> candidates,
            ObjectProvider<PlatformGroupGrants> platformGrants,
            ObjectProvider<OidcJwtAuthenticationConverter.SignInLookups> lookups,
            ObjectProvider<OidcJwtAuthenticationConverter.Senders> senders,
            ObjectProvider<OidcJwtAuthenticationConverter.Sources> sources,
            ObjectProvider<RoleConflictReporter> conflictReporter) {
        var settings = new OidcJwtAuthenticationConverter.Settings(providerId, requireText(audience, "sdv.auth.jwt.audience"),
                groupsClaim, Arrays.stream(clientIdClaims.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(),
                entityClaim, sourceIdClaim, sourceKeyClaim);
        var normalizer = new OidcJwtIdentityNormalizer(settings.providerId(),
                new IdentityClaimConfig(usernameClaim, emailClaim, displayNameClaim, settings.groupsClaim()));
        SdvRoleCatalog catalog = SdvRoleCatalog.shipped();
        ActiveEntityCandidates entities = require(candidates, "entity lookup");
        var people = new EntityMembershipResolver(require(grants, "group grants"), entities,
                catalog, new AdmissionSettings(false, true, settings.providerId(), settings.audience()));
        // Task 0057: platform-level grants (the role mapping's * rows) give platform roles in every ACTIVE entity.
        var platform = new PlatformLevelGrants(require(platformGrants, "platform grants"), entities, catalog);
        return new OidcJwtAuthenticationConverter(settings, normalizer, people, platform, require(lookups, "sign-in lookups"),
                require(senders, "client bindings"), require(sources, "sending systems"),
                OidcJwtAuthenticationConverter.RequestView.CURRENT, require(conflictReporter, "role-conflict reporter"));
    }

    /** Fail closed: without a platform lookup (or the task 0025 conflict reporter) Vero does not start. */
    private static <T> T require(ObjectProvider<T> provider, String what) {
        T value = provider.getIfAvailable();
        if (value == null) throw new StartupFailure(SdvErrorCode.SYS_005, "OIDC sign-in needs the " + what + ", which is not available.");
        return value;
    }

    private static String requireSecureUri(String value, String property) {
        String text = requireText(value, property);
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException ex) {
            throw new StartupFailure(SdvErrorCode.SYS_005, property + " must be an absolute HTTPS URI (HTTP is allowed only for loopback acceptance).");
        }
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new StartupFailure(SdvErrorCode.SYS_005, property + " must be an absolute HTTPS URI (HTTP is allowed only for loopback acceptance).");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()));
        if (!https && !loopbackHttp) {
            throw new StartupFailure(SdvErrorCode.SYS_005, property + " must use HTTPS outside loopback acceptance.");
        }
        return text;
    }

    private static String requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new StartupFailure(SdvErrorCode.SYS_005, property + " is required when sdv.auth.mode=enterprise-jwt.");
        }
        return value.trim();
    }
}
