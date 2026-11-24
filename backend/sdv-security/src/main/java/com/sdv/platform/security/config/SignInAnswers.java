package com.sdv.platform.security.config;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.auth.SignInAnswer;
import com.sdv.platform.security.auth.oidc.SignInKeysUnavailableException;
import com.sdv.platform.security.auth.oidc.SignInRefusedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import java.io.IOException;
import java.util.Locale;

/**
 * Task 0038: Vero's answers to a sign-in the security filters refused. Each puts a {@link SignInAnswer} on the request
 * and calls {@code sendError(status)}; Vero's /error page logs it and writes the one error body.
 * <ul>
 *   <li>Sign-in switched off: 401 SDV-SYS-006, saying so.</li>
 *   <li>No sign-in: 401 SDV-AUT-001.</li>
 *   <li>A token Spring refused (expired, signature, issuer, audience, lifetime, malformed): 401 SDV-AUT-003.</li>
 *   <li>A verified token Vero refused: its own code (AUT-005, AUT-006, AUT-007, ROL-004, ROL-005).</li>
 *   <li>The sign-in hub's keys can't be fetched: 503 SDV-AUT-004 (Spring would answer 500).</li>
 *   <li>Refused by the filters' access rule: 403 SDV-ROL-006.</li>
 * </ul>
 * The {@code WWW-Authenticate} header keeps Spring's error ("invalid_token"), but its description is Vero's code only:
 * Spring's descriptions can repeat what the token held (for example its expiry time).
 */
public final class SignInAnswers {
    private SignInAnswers() { }

    public static AuthenticationEntryPoint entryPoint(String mode) {
        boolean bearer = "enterprise-jwt".equals(mode);
        boolean off = "disabled".equals(mode);
        return (request, response, exception) -> {
            SignInAnswer answer = classify(exception, off);
            if (bearer) response.setHeader("WWW-Authenticate", wwwAuthenticate(exception, answer));
            send(request, response, answer);
        };
    }

    /**
     * The bearer-token filter's failure handler. A failure that is not the caller's (Spring wraps it in
     * {@link AuthenticationServiceException}) is no 401: the hub's keys unavailable is 503 SDV-AUT-004; anything else
     * goes on to the error page as a failure (500, with the cause in the log).
     */
    public static AuthenticationFailureHandler bearerFailureHandler(AuthenticationEntryPoint entryPoint) {
        return (request, response, exception) -> {
            if (exception instanceof AuthenticationServiceException service) {
                SignInKeysUnavailableException keys = keysUnavailable(service);
                if (keys == null) throw service;
                send(request, response, SignInAnswer.of(HttpServletResponse.SC_SERVICE_UNAVAILABLE, SdvErrorCode.AUT_004,
                        SignInAnswer.KEYS_UNAVAILABLE, keys.getMessage()));
                return;
            }
            entryPoint.commence(request, response, exception);
        };
    }

    public static AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) -> {
            String message = exception instanceof CodedRefusal ? exception.getMessage() : SignInAnswer.ROLE_LACKS_THIS;
            send(request, response, SignInAnswer.of(HttpServletResponse.SC_FORBIDDEN, SdvErrorCode.ROL_006, message,
                    "refused by the address's access rule"));
        };
    }

    static SignInAnswer classify(AuthenticationException exception, boolean signInOff) {
        if (signInOff) {
            return SignInAnswer.of(401, SdvErrorCode.SYS_006, SignInAnswer.SIGN_IN_OFF,
                    "sign-in is switched off (SDV_AUTH_MODE=disabled)");
        }
        if (exception instanceof SignInRefusedException refused) return refused.answer();
        if (exception instanceof OAuth2AuthenticationException oauth) {
            int status = oauth.getError() instanceof BearerTokenError bearer && bearer.getHttpStatus() != null
                    ? bearer.getHttpStatus().value() : 401;
            return SignInAnswer.of(status, SdvErrorCode.AUT_003, SignInAnswer.SIGN_IN_AGAIN, "token not accepted: " + reason(oauth));
        }
        return SignInAnswer.of(401, SdvErrorCode.AUT_001, SignInAnswer.SIGN_IN_AGAIN, "no sign-in on the request");
    }

    /**
     * Why Spring refused the token, as a class (never the description itself: it can hold the token's times). The
     * order matters: "Required API audience is missing" holds "iss".
     */
    static String reason(OAuth2AuthenticationException exception) {
        if (exception.getError() != null && "invalid_request".equals(exception.getError().getErrorCode())) {
            return "malformed Authorization header";
        }
        StringBuilder all = new StringBuilder();
        if (exception.getError() != null && exception.getError().getDescription() != null) {
            all.append(exception.getError().getDescription()).append(' ');
        }
        for (Throwable t = exception; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null) all.append(t.getMessage()).append(' ');
        }
        String text = all.toString().toLowerCase(Locale.ROOT);
        if (text.contains("lifetime")) return "lifetime longer than Vero allows";
        // Task 0065 (AccessTokenShapeValidator).
        if (text.contains("id token")) return "an ID token, not an access token";
        if (text.contains("token type")) return "token type";
        if (text.contains("no end time") || text.contains("no issue time") || text.contains("in the future")) return "token times";
        if (text.contains("audience")) return "audience";
        if (text.contains("expired")) return "expired";
        if (text.contains("used before")) return "not yet valid";
        if (text.contains("iss claim") || text.contains("issuer")) return "issuer";
        if (text.contains("signature") || text.contains("no matching key") || text.contains("signed jwt rejected")) {
            return "signature";
        }
        if (text.contains("malformed") || text.contains("serialization") || text.contains("invalid jwt")) return "malformed";
        return "other";
    }

    private static String wwwAuthenticate(AuthenticationException exception, SignInAnswer answer) {
        if (!(exception instanceof OAuth2AuthenticationException oauth) || oauth.getError() == null) return "Bearer";
        String error = oauth.getError().getErrorCode();
        if (error == null || !error.matches("[a-z_]{1,40}")) error = "invalid_token";
        return "Bearer error=\"" + error + "\", error_description=\"" + answer.code().code() + "\"";
    }

    private static SignInKeysUnavailableException keysUnavailable(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SignInKeysUnavailableException keys) return keys;
        }
        return null;
    }

    private static void send(HttpServletRequest request, HttpServletResponse response, SignInAnswer answer) throws IOException {
        answer.attachTo(request);
        // The text is never sent: the error page writes the body from the answer.
        response.sendError(answer.status(), answer.code().code());
    }
}
