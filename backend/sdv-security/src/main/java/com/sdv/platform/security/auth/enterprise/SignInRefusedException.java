package com.sdv.platform.security.auth.enterprise;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.auth.SignInAnswer;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * Task 0038: a verified token that Vero still refuses (claims not usable, no connection, no role, role conflict). It
 * stays an OAuth2 "invalid_token" refusal for Spring, so the answer is 401 with a WWW-Authenticate header; it also
 * carries Vero's code, the fixed sentence and the exact cause for the log line.
 * <p>
 * The OAuth2 description is the code only: Spring's own descriptions could echo what the token held.
 */
public final class SignInRefusedException extends OAuth2AuthenticationException {
    private final transient SignInAnswer answer;

    public SignInRefusedException(SdvErrorCode code, String message, String logDetail) {
        super(new OAuth2Error("invalid_token", code.code(), null), code.code());
        this.answer = SignInAnswer.of(401, code, message, logDetail);
    }

    public SignInAnswer answer() {
        return answer;
    }
}
