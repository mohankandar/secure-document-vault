package com.sdv.platform.security.auth.enterprise;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaximumTokenLifetimeValidatorTest {
    private final MaximumTokenLifetimeValidator validator = new MaximumTokenLifetimeValidator(Duration.ofMinutes(60));

    @Test
    void acceptsTokenWithinConfiguredMaximum() {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        assertFalse(validator.validate(jwt(now, now.plusSeconds(3599))).hasErrors());
    }

    @Test
    void rejectsTokenBeyondConfiguredMaximum() {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        assertTrue(validator.validate(jwt(now, now.plusSeconds(3601))).hasErrors());
    }

    @Test
    void rejectsMissingIssuedAt() {
        Instant exp = Instant.parse("2026-10-05T13:00:00Z");
        Jwt token = new Jwt("token", null, exp, Map.of("alg", "RS256"), Map.of("sub", "user"));
        assertTrue(validator.validate(token).hasErrors());
    }

    private static Jwt jwt(Instant issuedAt, Instant expiresAt) {
        return new Jwt("token", issuedAt, expiresAt, Map.of("alg", "RS256"), Map.of("sub", "user", "iat", issuedAt.getEpochSecond()));
    }
}
