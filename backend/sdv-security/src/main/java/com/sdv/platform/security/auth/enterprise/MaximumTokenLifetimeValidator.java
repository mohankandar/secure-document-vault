package com.sdv.platform.security.auth.enterprise;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.time.Instant;

/** Optional defense-in-depth validator that bounds the lifetime minted into a human/M2M JWT. */
final class MaximumTokenLifetimeValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2Error INVALID = new OAuth2Error("invalid_token", "JWT lifetime exceeds the configured SDV maximum.", null);
    private final Duration maximum;

    MaximumTokenLifetimeValidator(Duration maximum) {
        if (maximum == null || maximum.isZero() || maximum.isNegative()) throw new IllegalArgumentException("maximum must be positive");
        this.maximum = maximum;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Instant issuedAt = token.getIssuedAt();
        Instant expiresAt = token.getExpiresAt();
        if (issuedAt == null || expiresAt == null || expiresAt.isBefore(issuedAt)) {
            return OAuth2TokenValidatorResult.failure(INVALID);
        }
        Duration actual = Duration.between(issuedAt, expiresAt);
        return actual.compareTo(maximum) <= 0
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(INVALID);
    }
}
