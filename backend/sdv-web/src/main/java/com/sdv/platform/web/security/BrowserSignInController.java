package com.sdv.platform.web.security;

import com.documentplatform.identity.admission.ConnectionKey;
import com.sdv.platform.domain.error.CodedFault;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 0065: how a browser signs in. Open, because it is asked before any sign-in: only what the login system itself
 * publishes, and the public client's ID. Never a secret.
 */
@RestController
@RequestMapping("/api/v1/session/sign-in")
public class BrowserSignInController {
    private final PlatformIdentityReads reads;
    private final BrowserSignIn signIn;
    private final String mode;
    private final ConnectionKey key;
    private final String scope;

    public BrowserSignInController(PlatformIdentityReads reads, BrowserSignIn signIn,
                                   @Value("${sdv.auth.mode:disabled}") String mode,
                                   @Value("${sdv.auth.jwt.provider-id:customer-oidc}") String providerId,
                                   @Value("${sdv.auth.jwt.issuer:}") String issuer,
                                   @Value("${sdv.auth.jwt.audience:}") String audience,
                                   @Value("${sdv.auth.browser-scope:openid profile email}") String scope) {
        this.reads = reads;
        this.signIn = signIn;
        this.mode = mode == null ? "" : mode.trim();
        this.key = "enterprise-jwt".equalsIgnoreCase(this.mode) && !issuer.isBlank() && !audience.isBlank() ? new ConnectionKey(providerId.trim(), java.net.URI.create(issuer.trim()), audience.trim()) : null;
        this.scope = scope == null || scope.isBlank() ? "openid" : scope.trim();
    }

    public record SignInView(String mode, String issuer, String provider, String clientId, String scope, String authorizationEndpoint,
                             String tokenEndpoint, String endSessionEndpoint, String revocationEndpoint) { }

    @GetMapping
    public ResponseEntity<SignInView> signIn() {
        if ("local-header".equalsIgnoreCase(mode)) return noStore(new SignInView("local", null, null, null, null, null, null, null, null));
        if (key == null) throw unavailable(SdvErrorCode.AUT_014, "Sign-in is not set up.", "sdv.auth.mode=" + (mode.isEmpty() ? "disabled" : "disabled or unknown"));
        PlatformIdentityReads.BrowserConnection c = reads.browserConnection(key)
                .orElseThrow(() -> unavailable(SdvErrorCode.AUT_014, "Sign-in is not set up.", "no login connection with a browser client is in use"));
        BrowserSignIn.Endpoints e = signIn.endpoints(c);
        if (e == null) throw unavailable(SdvErrorCode.AUT_015, "The login system can't be reached. Try again later.", "discovery can't be read");
        return noStore(new SignInView("oidc", c.issuer(), c.providerId(), c.browserClientId(), scope, e.authorization(), e.token(), e.endSession(), e.revocation()));
    }

    private static ResponseEntity<SignInView> noStore(SignInView view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }

    private static RuntimeException unavailable(SdvErrorCode code, String message, String detail) {
        return new SignInUnavailable(code, message, detail);
    }

    static final class SignInUnavailable extends CodedFault {
        SignInUnavailable(SdvErrorCode code, String message, String detail) {
            super(code, UNAVAILABLE, message, detail, null);
        }
    }
}
