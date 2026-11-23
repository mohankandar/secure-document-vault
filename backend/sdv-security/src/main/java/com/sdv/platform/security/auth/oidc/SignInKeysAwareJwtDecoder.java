package com.sdv.platform.security.auth.oidc;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.net.URI;
import java.util.Objects;

/**
 * Task 0038 (SDV-AUT-004): Spring's decoder answers "the sign-in hub's keys couldn't be fetched" with the same
 * {@link JwtException} it uses for other decoding troubles. This tells it apart: a fetch failure (the key source,
 * an I/O error or Spring's HTTP client in the cause chain) becomes {@link SignInKeysUnavailableException}, which
 * Vero answers 503 instead of 401 or 500. A token that is simply wrong ({@link BadJwtException}) is left alone.
 */
public final class SignInKeysAwareJwtDecoder implements JwtDecoder {
    private final JwtDecoder delegate;
    private final String keysHost;

    public SignInKeysAwareJwtDecoder(JwtDecoder delegate, String jwkSetUri) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.keysHost = host(jwkSetUri);
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        try {
            return delegate.decode(token);
        } catch (BadJwtException wrong) {
            throw wrong;
        } catch (JwtException ex) {
            Throwable fetch = fetchFailure(ex);
            if (fetch == null) throw ex;
            throw new SignInKeysUnavailableException(
                    "The sign-in keys at " + keysHost + " couldn't be fetched (" + fetch.getClass().getSimpleName() + ").", ex);
        }
    }

    /** The key set's host, for the log line (never the path or query). */
    public String keysHost() {
        return keysHost;
    }

    static Throwable fetchFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof com.nimbusds.jose.KeySourceException
                    || t instanceof java.io.IOException
                    || t instanceof org.springframework.web.client.RestClientException) {
                return t;
            }
        }
        return null;
    }

    static String host(String uri) {
        try {
            URI parsed = URI.create(uri.trim());
            return parsed.getPort() < 0 ? parsed.getHost() : parsed.getHost() + ":" + parsed.getPort();
        } catch (RuntimeException ex) {
            return "(the configured key set)";
        }
    }
}
