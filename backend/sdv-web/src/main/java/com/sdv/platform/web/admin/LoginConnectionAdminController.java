package com.sdv.platform.web.admin;

import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.sdv.platform.domain.identity.VeroProduct;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.web.security.ActiveConnectionCheck;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0041 (owner answer 6): the IT Admin reads the login connection in use, read only. Setup loads and changes it
 * ("Sign-in is set during installation by your installer"). It holds no secret (a secret reference at most, not shown).
 * <p>
 * Task 0057 (LV-16 item 2): {@code POST …/test} runs platform-core's test sign-in against the connection in use
 * ({@link ActiveConnectionCheck}) and answers what it found, with platform-core's codes. It changes nothing: Vero offers
 * no draft, activation or rollback.
 */
@RestController
@RequestMapping("/api/v1/admin/login-connection")
public class LoginConnectionAdminController {
    private static final String BEARER = "Bearer ";

    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final PlatformIdentityReads reads;
    private final LoginConnectionStore connections;
    private final ActiveConnectionCheck tester;
    private final boolean tokenSignIn;

    public LoginConnectionAdminController(SecurityContextFacade security, AuthorizationService authorization,
                                          PlatformIdentityReads reads, LoginConnectionStore connections,
                                          ActiveConnectionCheck tester, @Value("${sdv.auth.mode:disabled}") String authMode) {
        this.security = security;
        this.authorization = authorization;
        this.reads = reads;
        this.connections = connections;
        this.tester = tester;
        // Only an OIDC sign-in's Authorization header is a token of the login system; the local test sign-in has none.
        this.tokenSignIn = "enterprise-jwt".equals(authMode);
    }

    public record ConnectionView(UUID connectionId, String providerId, String providerType, String issuerUri,
                                 String browserClientId, String apiAudience, String status, long configurationVersion) {
        static ConnectionView of(LoginConnection c) {
            return new ConnectionView(c.connectionId(), c.providerId(), c.providerType().name(), c.issuer().toString(),
                    c.browserClientId(), c.apiAudience(), c.status().name(), c.configurationVersion());
        }
    }

    /** {@code active} is null when the customer has no ACTIVE connection for Vero (or the entity is not set up). */
    public record Overview(ConnectionView active, boolean changedBySetupOnly) { }

    /**
     * The test's answer. {@code outcome}: PASSED, FAILED, or NO_CONNECTION (nothing to test). {@code code}: platform-core's
     * code of the check that failed ({@code IDN_TEST_…}), else null. {@code tokenChecked}: the admin's own sign-in token
     * was checked too (signature, issuer, audience, expiry, groups); without it only the discovery document, the keys
     * and the issuer were. Never a token, a key or the document's content.
     */
    public record TestAnswer(String outcome, String code, String message, String reason, boolean tokenChecked,
                             UUID connectionId, long configurationVersion, Instant testedAt) { }

    @GetMapping
    public Overview overview() {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.CONFIGURE_TENANT_AUTH);
        return new Overview(active(principal).map(ConnectionView::of).orElse(null), true);
    }

    @PostMapping("/test")
    public TestAnswer test(HttpServletRequest request) {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.CONFIGURE_TENANT_AUTH);
        Instant now = Instant.now();
        Optional<LoginConnection> active = active(principal);
        if (active.isEmpty()) {
            return new TestAnswer("NO_CONNECTION", null, "No company sign-in is in use, so there is nothing to test.",
                    null, false, null, 0, now);
        }
        LoginConnection c = active.get();
        ActiveConnectionCheck.Result r = tester.test(c, ownToken(request));
        return new TestAnswer(r.passed() ? "PASSED" : "FAILED", r.code(), r.message(), r.reason(), r.tokenChecked(),
                c.connectionId(), c.configurationVersion(), now);
    }

    private Optional<LoginConnection> active(SdvPrincipal principal) {
        return reads.slotOf(principal.tenantId()).flatMap(slot -> connections.active(slot.tenantId(), VeroProduct.CODE));
    }

    /** The bearer token Spring already accepted for this request, under OIDC sign-in only; else null. */
    private String ownToken(HttpServletRequest request) {
        if (!tokenSignIn) return null;
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) return null;
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
