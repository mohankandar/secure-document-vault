package com.sdv.platform.security.auth.oidc;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0065. */
class AccessTokenShapeValidatorTest {
    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");
    private final AccessTokenShapeValidator validator = new AccessTokenShapeValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    private boolean accepted(Instant issued, Instant ends, Object type, String... claims) {
        Map<String, Object> headers = new HashMap<>(Map.of("alg", "RS256"));
        if (type != null) headers.put("typ", type);
        Map<String, Object> all = new HashMap<>(Map.of("sub", "maya"));
        for (String c : claims) all.put(c, "x");
        return !validator.validate(new Jwt("t", issued, ends, headers, all)).hasErrors();
    }

    @Test
    void anAccessTokenPasses() {
        for (Object type : new Object[] {null, "JWT", "jwt", "at+jwt", "application/at+jwt"}) {
            assertTrue(accepted(NOW.minusSeconds(5), NOW.plusSeconds(300), type), String.valueOf(type));
        }
        assertTrue(accepted(NOW.plusSeconds(60), NOW.plusSeconds(300), "JWT"), "a minute's clock difference");
    }

    @Test
    void timesAreRequiredAndNotInTheFuture() {
        assertFalse(accepted(null, NOW.plusSeconds(300), "JWT"));
        assertFalse(accepted(NOW, null, "JWT"));
        assertFalse(accepted(NOW.plusSeconds(61), NOW.plusSeconds(300), "JWT"));
    }

    @Test
    void anIdTokenOrAnotherTypeIsRefused() {
        for (String claim : new String[] {"nonce", "at_hash", "c_hash"}) assertFalse(accepted(NOW, NOW.plusSeconds(300), "JWT", claim), claim);
        for (Object type : new Object[] {"ID", "logout+jwt", "dpop+jwt", "", 5}) assertFalse(accepted(NOW, NOW.plusSeconds(300), type), String.valueOf(type));
    }
}
