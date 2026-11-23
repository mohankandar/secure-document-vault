package com.sdv.platform.security.auth.oidc;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.auth.SignInAnswer;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * Task 0038: a verified token that Vero still refuses (claims not usable, no connection, no role, role conflict). It
 * stays an OAuth2 "invalid_token" refusal for Spring, so the answer is 401 with a WWW-Authenticate header; it also
 * carries Vero's code, the fixed sentence and the exact cause for the log line.
 * <p>
 * Task 0041: SDV-AUT-010 ("choose an entity") is answered 409, not 401: the sign-in is good, only the entity choice is
 * missing or wrong, so the browser must not treat it as signed out.
 * <p>
 * The OAuth2 description is the code only: Spring's own descriptions could echo what the token held.
 */
public final class SignInRefusedException extends OAuth2AuthenticationException {
    private final transient SignInAnswer answer;

    public SignInRefusedException(SdvErrorCode code, String message, String logDetail) {
        super(new OAuth2Error("invalid_token", code.code(), null), code.code());
        this.answer = SignInAnswer.of(code == SdvErrorCode.AUT_010 ? 409 : 401, code, message, logDetail);
    }

    public SignInAnswer answer() {
        return answer;
    }
}
