package com.sdv.platform.web.security;

import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.testkit.MockIssuer;
import com.sdv.platform.domain.error.StartupFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0057 (LV-16 item 2): the read-only test sign-in of the connection in use, through platform-core's identity
 * module, against platform-core's {@link MockIssuer} (a login server on this machine, http).
 */
class ActiveConnectionCheckTest {
    static final String AUDIENCE = "sdv-api";
    static final DevelopmentLicence DEVELOPMENT = () -> true;
    static final DevelopmentLicence PRODUCTION = DevelopmentLicence.NONE;

    MockIssuer issuer = MockIssuer.start();

    @AfterEach
    void stop() {
        issuer.close();
    }

    static ActiveConnectionCheck check(IssuerFetch fetch, DevelopmentLicence licence) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (fetch != null) beans.registerSingleton("fetch", fetch);
        return new ActiveConnectionCheck(beans.getBeanProvider(IssuerFetch.class), licence, "groups");
    }

    LoginConnection connection(URI issuerUri) {
        return new LoginConnection(UUID.randomUUID(), "TEN-TEST-0001", "SDV", "customer-oidc", IdentityProviderType.OIDC,
                issuerUri, "sdv-web", AUDIENCE, null, IdentityConnectionStatus.ACTIVE, 2);
    }

    String token(String audience, List<String> groups) {
        var claims = issuer.claims(audience, Duration.ofMinutes(5));
        claims.put("aud", audience);
        claims.put("groups", groups);
        return issuer.sign(claims);
    }

    @Test
    void theConnectionInUsePassesWithoutAToken() {
        var r = check(MockIssuer.fetch(), DEVELOPMENT).test(connection(issuer.issuer()), null);
        assertTrue(r.passed(), r.toString());
        assertNull(r.code());
        assertFalse(r.tokenChecked(), "without a token only discovery, keys and issuer are checked");
    }

    @Test
    void theAdminsOwnTokenChecksTheAudienceToo() {
        var ok = check(MockIssuer.fetch(), DEVELOPMENT).test(connection(issuer.issuer()), token(AUDIENCE, List.of("it")));
        assertTrue(ok.passed(), ok.toString());
        assertTrue(ok.tokenChecked());

        var wrongAudience = check(MockIssuer.fetch(), DEVELOPMENT).test(connection(issuer.issuer()), token("another-api", List.of("it")));
        assertFalse(wrongAudience.passed());
        assertEquals("IDN_TEST_AUDIENCE", wrongAudience.code());
    }

    @Test
    void eachFailureNamesPlatformCoresCode() {
        issuer.down();
        var unreachable = check(MockIssuer.fetch(), DEVELOPMENT).test(connection(issuer.issuer()), null);
        assertEquals("IDN_TEST_ISSUER_UNREACHABLE", unreachable.code(), unreachable.toString());
        issuer.up();

        issuer.discoveryIssuer("https://another.example.test/");
        assertEquals("IDN_TEST_DISCOVERY_MISMATCH", check(MockIssuer.fetch(), DEVELOPMENT).test(connection(issuer.issuer()), null).code());
        issuer.discoveryIssuer(issuer.issuer().toString());

        issuer.noKeys();
        assertEquals("IDN_TEST_KEYS_MISSING", check(MockIssuer.fetch(), DEVELOPMENT).test(connection(issuer.issuer()), null).code());
    }

    @Test
    void anHttpLoginServerIsRefusedUnlessTheLicenceIsDevelopmentSigned() {
        var r = check(MockIssuer.fetch(), PRODUCTION).test(connection(issuer.issuer()), null);
        assertFalse(r.passed());
        assertEquals("HTTP_NOT_ALLOWED", r.code(), r.toString());
        assertEquals(0, issuer.calls(), "refused before anything is fetched");
    }

    @Test
    void withoutPlatformCoresIssuerFetchVeroDoesNotStart() {
        assertThrows(StartupFailure.class, () -> check(null, DEVELOPMENT));
    }
}
