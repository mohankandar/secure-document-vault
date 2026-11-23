package com.sdv.platform.security.auth.oidc;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0037, item 5: a sign-in setting that isn't valid stops the start with SDV-SYS-005 and names the setting. */
class SignInSettingsCodeTest {
    private static StartupFailure decoder(String issuer, String jwks, String audience) {
        return assertThrows(StartupFailure.class,
                () -> new OidcJwtConfiguration().enterpriseJwtDecoder(issuer, jwks, audience, 0));
    }

    @Test
    void eachIssuerProblemIsSys005AndNamesTheSetting() {
        StartupFailure missing = decoder("", "https://idp.example.org/certs", "vero");
        assertEquals(SdvErrorCode.SYS_005, missing.errorCode());
        assertEquals("sdv.auth.jwt.issuer is required when sdv.auth.mode=enterprise-jwt.", missing.detail());
        assertEquals(SdvErrorCode.SYS_005, decoder("http://idp.example.org/realm", "https://idp.example.org/certs", "vero").errorCode(),
                "HTTP outside loopback");
        assertEquals(SdvErrorCode.SYS_005, decoder("https://idp.example.org/realm", "not a uri", "vero").errorCode());
        assertEquals(SdvErrorCode.SYS_005, decoder("https://idp.example.org/realm", "https://idp.example.org/certs", " ").errorCode());
        assertTrue(missing.getMessage().startsWith("SDV-SYS-005 A sign-in setting is not valid."));
    }

    @Test
    void aTokenLifetimeLimitMustBePositive() {
        assertEquals(SdvErrorCode.SYS_005, assertThrows(StartupFailure.class,
                () -> new MaximumTokenLifetimeValidator(java.time.Duration.ZERO)).errorCode());
    }
}
