package com.sdv.platform.security.auth.oidc;

import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Task 0038 (SDV-AUT-004): the sign-in hub's signing keys (its JWKS) couldn't be fetched, so no token can be checked.
 * It is not the caller's fault: answered 503, not 401. The message names the key set's host and the cause's class,
 * never the token.
 */
public final class SignInKeysUnavailableException extends JwtException {
    public SignInKeysUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
