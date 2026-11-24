package com.sdv.platform.web.security;

import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.Verdict;
import com.nimbusds.jose.jwk.JWKSet;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.web.monitoring.VeroCheckCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Task 0038 (SDV-AUT-004): whether the login system's signing keys ({@code SDV_AUTH_JWT_JWK_SET_URI}) can be fetched.
 * Without them no sign-in can be checked, so readiness counts it ({@link SignInKeysHealthIndicator}). Task 0074: one of the
 * checks of the list, run every 10 seconds. Only with {@code SDV_AUTH_MODE=enterprise-jwt}.
 * The first failure and the recovery each write one log line; the details name the host, never the path or the keys.
 */
public class SignInKeysCheck implements DependencyCheck {
    static final Logger LOG = LoggerFactory.getLogger(SignInKeysCheck.class);
    static final Duration WAIT = Duration.ofSeconds(2);
    public static final String NAME = "sign-in-keys";

    private final boolean used;
    private final String host;
    private final Callable<String> fetch;
    private volatile boolean lastUp = true;

    public SignInKeysCheck(String mode, String jwkSetUri) {
        this(isJwt(mode), jwkSetUri, httpFetch(jwkSetUri));
    }

    /** {@code fetch} returns null when the keys were fetched and are usable, or the reason they aren't; it may throw. */
    SignInKeysCheck(boolean used, String jwkSetUri, Callable<String> fetch) {
        this.used = used;
        this.host = host(jwkSetUri);
        this.fetch = fetch;
    }

    boolean used() { return used; }
    @Override public String name() { return NAME; }
    @Override public String group() { return "sign-in"; }
    @Override public Duration every() { return Duration.ofSeconds(10); }
    /** The fetch's own limits (connect and answer) come first. */
    @Override public Duration timeout() { return WAIT.multipliedBy(3); }

    @Override
    public Verdict check() throws InterruptedException {
        if (!used) return Verdict.working();
        String reason;
        try {
            reason = fetch.call();
        } catch (InterruptedException ex) {
            throw ex;
        } catch (Exception ex) {
            reason = ex.getClass().getSimpleName();
        }
        if (reason == null) {
            if (!lastUp) LOG.info("Sign-in keys at {} can be fetched again; sign-ins can be checked.", host);
            lastUp = true;
            return Verdict.working();
        }
        if (lastUp) {
            LOG.warn("code={} The sign-in keys at {} can't be fetched ({}); no sign-in can be checked and readiness is "
                    + "not ready until they can.", SdvErrorCode.AUT_004.code(), host, reason);
        }
        lastUp = false;
        return Verdict.down(VeroCheckCode.SIGN_IN_KEYS, Map.of("host", host, "reason", reason));
    }

    private static boolean isJwt(String mode) {
        return mode != null && "enterprise-jwt".equals(mode.trim().toLowerCase(Locale.ROOT));
    }

    /** One GET of the key set: usable when it answers 200 with a key set that holds at least one key. */
    static Callable<String> httpFetch(String jwkSetUri) {
        return () -> {
            if (jwkSetUri == null || jwkSetUri.isBlank()) return "no key set address is set";
            HttpClient client = HttpClient.newBuilder().connectTimeout(WAIT).followRedirects(HttpClient.Redirect.NEVER).build();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(jwkSetUri.trim()))
                    .timeout(WAIT).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            return usable(response.statusCode(), response.body());
        };
    }

    static String usable(int status, String body) {
        if (status != 200) return "HTTP " + status;
        try {
            return JWKSet.parse(body).getKeys().isEmpty() ? "the key set holds no keys" : null;
        } catch (java.text.ParseException | RuntimeException ex) {
            return "the answer is not a key set";
        }
    }

    static String host(String uri) {
        try {
            URI parsed = URI.create(uri.trim());
            if (parsed.getHost() == null) return "(not set)";
            return parsed.getPort() < 0 ? parsed.getHost() : parsed.getHost() + ":" + parsed.getPort();
        } catch (RuntimeException ex) {
            return "(not set)";
        }
    }
}
