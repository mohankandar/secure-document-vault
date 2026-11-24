package com.sdv.platform.security.auth.oidc;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Task 0065: what an access token for Vero must look like, whoever it is for: an end and an issue time, not issued in the
 * future, of an access token's type (none, JWT or at+jwt), and without the marks of an ID token.
 */
final class AccessTokenShapeValidator implements OAuth2TokenValidator<Jwt> {
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    private static final Set<String> TYPES = Set.of("jwt", "at+jwt", "application/at+jwt");
    private static final List<String> ID_TOKEN_MARKS = List.of("nonce", "at_hash", "c_hash");
    private final Clock clock;

    AccessTokenShapeValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Instant issuedAt = token.getIssuedAt();
        if (token.getExpiresAt() == null) return refused("The token has no end time.");
        if (issuedAt == null) return refused("The token has no issue time.");
        if (issuedAt.isAfter(clock.instant().plus(CLOCK_SKEW))) return refused("The token was issued in the future.");
        Object type = token.getHeaders().get("typ");
        if (type != null && !(type instanceof String t && TYPES.contains(t.toLowerCase(Locale.ROOT)))) return refused("The token type is not an access token's.");
        for (String claim : ID_TOKEN_MARKS) {
            if (token.hasClaim(claim)) return refused("The token is an ID token (" + claim + ").");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult refused(String why) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", why, null));
    }
}
