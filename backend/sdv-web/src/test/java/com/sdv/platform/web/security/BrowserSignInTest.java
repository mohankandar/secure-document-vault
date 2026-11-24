package com.sdv.platform.web.security;

import com.documentplatform.identity.signin.IssuerFetch;
import com.sdv.platform.domain.error.CodedFault;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.persistence.identity.PlatformIdentityReads.BrowserConnection;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 0065: the open sign-in address and the login system's addresses behind it. */
class BrowserSignInTest {
    private static final String ISSUER = "https://login.example.test/realm";
    private static final BrowserConnection CONNECTION = new BrowserConnection(ISSUER, "vero-web", "customer-oidc");
    private Instant now = Instant.parse("2026-11-24T12:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };
    private final List<URI> asked = new ArrayList<>();
    private String answer = document("", "");
    private int status = 200;
    private final IssuerFetch fetch = (uri, max, localHttp) -> {
        asked.add(uri);
        if (answer == null) throw new IOException("down");
        return new IssuerFetch.Response(status, answer.getBytes(StandardCharsets.UTF_8));
    };
    private final BrowserSignIn signIn = new BrowserSignIn(fetch, () -> false, clock);

    private static String document(String issuer, String extra) {
        return "{\"issuer\":\"" + (issuer.isEmpty() ? ISSUER : issuer) + "\",\"authorization_endpoint\":\"https://login.example.test/auth\","
                + "\"token_endpoint\":\"https://login.example.test/token\"" + extra + "}";
    }

    @Test
    void theAddressesComeFromTheDiscoveryDocument() {
        answer = document("", ",\"end_session_endpoint\":\"https://login.example.test/out\",\"code_challenge_methods_supported\":[\"plain\",\"S256\"]");
        assertEquals(new BrowserSignIn.Endpoints("https://login.example.test/auth", "https://login.example.test/token", "https://login.example.test/out", null),
                signIn.endpoints(CONNECTION));
        assertEquals(List.of(URI.create(ISSUER + "/.well-known/openid-configuration")), asked);
    }

    @Test
    void whatDoesNotFitIsRefused() {
        for (String bad : List.of(document("https://other.example.test", ""), "{}", "not json",
                document("", ",\"code_challenge_methods_supported\":[\"plain\"]"),
                document("", "").replace("https://login.example.test/token", "http://login.example.test/token"),
                document("", "").replace("https://login.example.test/token", "javascript:alert(1)"),
                document("", "").replace("https://login.example.test/auth", "https://user@login.example.test/auth"))) {
            answer = bad;
            now = now.plus(Duration.ofMinutes(1));
            assertNull(signIn.endpoints(CONNECTION), bad);
        }
        answer = document("", "");
        status = 302;
        now = now.plus(Duration.ofMinutes(1));
        assertNull(signIn.endpoints(CONNECTION));
        assertNull(new BrowserSignIn(fetch, () -> false, clock).endpoints(new BrowserConnection("http://login.example.test", "c", "p")), "http");
    }

    @Test
    void theyAreKeptAndAFailureDoesNotCallOverAndOver() {
        signIn.endpoints(CONNECTION);
        now = now.plus(Duration.ofMinutes(4));
        signIn.endpoints(CONNECTION);
        assertEquals(1, asked.size());
        answer = null;
        now = now.plus(Duration.ofMinutes(2));
        for (int i = 0; i < 20; i++) assertNotNull(signIn.endpoints(CONNECTION), "the last good ones");
        assertEquals(2, asked.size());
        now = now.plus(Duration.ofMinutes(60));
        assertNull(signIn.endpoints(CONNECTION), "older than an hour");
    }

    private BrowserSignInController controller(String mode, Optional<BrowserConnection> connection) {
        PlatformIdentityReads reads = mock(PlatformIdentityReads.class);
        when(reads.browserConnection(any())).thenReturn(connection);
        return new BrowserSignInController(reads, signIn, mode, "customer-oidc", ISSUER, "vero-api", "openid");
    }

    private static SdvErrorCode code(BrowserSignInController c) {
        CodedFault fault = assertThrows(CodedFault.class, c::signIn);
        assertEquals(503, fault.httpStatus());
        return fault.errorCode();
    }

    @Test
    void theOpenAddressSaysOnlyWhatABrowserNeeds() {
        var view = controller("enterprise-jwt", Optional.of(CONNECTION)).signIn();
        assertEquals("oidc", view.getBody().mode());
        assertEquals("vero-web", view.getBody().clientId());
        assertEquals("https://login.example.test/token", view.getBody().tokenEndpoint());
        assertEquals("no-store", view.getHeaders().getCacheControl());
        assertEquals("local", controller("local-header", Optional.empty()).signIn().getBody().mode());
        assertEquals(SdvErrorCode.AUT_014, code(controller("enterprise-jwt", Optional.empty())));
        assertEquals(SdvErrorCode.AUT_014, code(controller("disabled", Optional.empty())));
        answer = null;
        assertEquals(SdvErrorCode.AUT_015, code(new BrowserSignInController(readsWith(), new BrowserSignIn(fetch, () -> false, clock),
                "enterprise-jwt", "customer-oidc", ISSUER, "vero-api", "openid")));
    }

    private static PlatformIdentityReads readsWith() {
        PlatformIdentityReads reads = mock(PlatformIdentityReads.class);
        when(reads.browserConnection(any())).thenReturn(Optional.of(CONNECTION));
        return reads;
    }
}
