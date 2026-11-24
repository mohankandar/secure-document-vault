package com.sdv.platform.web.security;

import com.documentplatform.errors.PlatformCode;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.signin.LocalTestIssuers;
import com.documentplatform.identity.signin.OidcTestSignIn;
import com.documentplatform.identity.signin.TestReport;
import com.documentplatform.identity.signin.TestSignIn;
import com.documentplatform.identity.store.LoginConnection;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.util.Optional;

/**
 * Task 0057 (LV-16 item 2): the IT Admin's read-only "Test sign-in" of the login connection in use. platform-core's
 * identity module does the checks ({@link OidcTestSignIn}, released in 0.3.0): it reads the issuer's discovery document
 * and signing keys over platform-core's outbound rules ({@code platform.outbound.*}, the {@link IssuerFetch} bean of
 * platform-identity-spring), checks that the document names the same issuer and, with a token, the token's signature,
 * issuer, API audience, expiry and groups claim.
 * <p>
 * The token is the IT Admin's own sign-in token of this request (Spring already accepted it for Vero's audience); it is
 * handed to platform-core and forgotten: never stored, logged or returned. Without one (the local test sign-in) only
 * the discovery document, the keys and the issuer are checked, and the report says so.
 * <p>
 * It changes nothing: no draft, no validation mark, no activation (platform-core's {@code LoginConnections.validate}
 * would write; this does not use it). http is allowed for a login server on this machine only under a
 * development-signed licence ({@link DevelopmentLicence}), as platform-core's own validation does.
 */
@Component
public class ActiveConnectionCheck {
    private static final Logger log = LoggerFactory.getLogger(ActiveConnectionCheck.class);

    /** What one test found. {@code code} is platform-core's code (null when passed); never a token or a secret. */
    public record Result(boolean passed, String code, String message, String reason, boolean tokenChecked) { }

    private final OidcTestSignIn signIn;
    private final DevelopmentLicence developmentLicence;
    private final String groupsClaim;

    public ActiveConnectionCheck(ObjectProvider<IssuerFetch> fetch, DevelopmentLicence developmentLicence,
                                @Value("${sdv.auth.jwt.groups-claim:groups}") String groupsClaim) {
        IssuerFetch issuerFetch = fetch.getIfAvailable();
        // Fail closed: without platform-core's outbound fetch the test could not run as released.
        if (issuerFetch == null) {
            throw new StartupFailure(SdvErrorCode.SYS_005, "The test sign-in needs platform-core's issuer fetch "
                    + "(platform.outbound.* settings), which is not available.");
        }
        this.signIn = new OidcTestSignIn(issuerFetch, Clock.systemUTC());
        this.developmentLicence = developmentLicence;
        this.groupsClaim = groupsClaim == null || groupsClaim.isBlank() ? TestSignIn.DEFAULT_GROUPS_CLAIM : groupsClaim.trim();
    }

    /** Tests {@code connection}; {@code token} is the caller's own bearer token, or null. */
    public Result test(LoginConnection connection, String token) {
        if (connection.providerType() != IdentityProviderType.OIDC) {
            return new Result(false, null, "The test sign-in checks OIDC login systems only.", null, false);
        }
        URI issuer = connection.issuer();
        Optional<LocalTestIssuers.Problem> refused = LocalTestIssuers.check(issuer, developmentLicence.signedByDevelopmentKey());
        if (refused.isPresent()) {
            return logged(connection, fail(refused.get()));
        }
        TestSignIn input = token == null || token.isBlank() ? TestSignIn.issuerOnly() : new TestSignIn(token, groupsClaim);
        TestReport report = signIn.run(issuer, connection.apiAudience(), input, LocalTestIssuers.isLocalHttp(issuer));
        Result result = report.passed()
                ? new Result(true, null, report.message(), null, report.tokenChecked())
                : new Result(false, report.failure().code(), report.message(), report.reason(), report.tokenChecked());
        return logged(connection, result);
    }

    private static Result fail(PlatformCode code) {
        return new Result(false, code.code(), code.userMessage(), null, false);
    }

    /** One INFO line: the connection and the outcome's code. Never the token, the claims or the document's content. */
    private static Result logged(LoginConnection connection, Result result) {
        log.info("Test sign-in of login connection {} (issuer host={}): {}{}", connection.connectionId(),
                connection.issuer().getHost(), result.passed() ? "passed" : "failed " + result.code(),
                result.tokenChecked() ? ", with the admin's own token" : ", without a token");
        return result;
    }
}
