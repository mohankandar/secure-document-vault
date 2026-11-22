package com.sdv.platform.web.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.sdv.platform.domain.error.SdvErrorCode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Task 0038 (SDV-AUT-004): readiness reports whether the sign-in hub's signing keys (its JWKS,
 * {@code SDV_AUTH_JWT_JWK_SET_URI}) can be fetched. Without them no sign-in can be checked, so Vero is not ready:
 * {@code /actuator/health/readiness} is 503 while they can't be fetched, and ready again on its own when they can.
 * <p>
 * Only with {@code SDV_AUTH_MODE=enterprise-jwt}; in the other modes Vero uses no keys and this is UP. Like the
 * database ping, a fetch never makes a probe wait more than 2 s, and probes share one fetch. The first failure and
 * the recovery each write one log line (WARN with the code and the host, then INFO); the details name the host, never
 * the path or the keys.
 */
@Component("signInKeys")
public class SignInKeysHealthIndicator implements HealthIndicator {
    static final Logger LOG = LoggerFactory.getLogger(SignInKeysHealthIndicator.class);
    static final Duration WAIT = Duration.ofSeconds(2);

    private final boolean used;
    private final String host;
    private final Callable<String> fetch;
    private final Duration wait;
    private final ExecutorService fetcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sdv-sign-in-keys-check");
        t.setDaemon(true);
        return t;
    });
    private Future<String> inFlight;
    private volatile boolean lastUp = true;

    @Autowired
    public SignInKeysHealthIndicator(@Value("${sdv.auth.mode:disabled}") String mode,
                                     @Value("${sdv.auth.jwt.jwk-set-uri:}") String jwkSetUri) {
        this(isJwt(mode), jwkSetUri, httpFetch(jwkSetUri), WAIT);
    }

    /**
     * {@code fetch} returns null when the keys were fetched and are usable, or the reason they aren't; it may throw.
     */
    SignInKeysHealthIndicator(boolean used, String jwkSetUri, Callable<String> fetch, Duration wait) {
        this.used = used;
        this.host = host(jwkSetUri);
        this.fetch = fetch;
        this.wait = wait;
    }

    @Override
    public Health health() {
        if (!used) return Health.up().withDetail("signIn", "not enterprise-jwt; no sign-in keys are used").build();
        String reason;
        try {
            reason = check().get(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            reason = "no answer within " + wait.toMillis() + " ms";
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            reason = cause.getClass().getSimpleName();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            reason = "interrupted";
        }
        if (reason == null) {
            if (!lastUp) LOG.info("Sign-in keys at {} can be fetched again; sign-ins can be checked.", host);
            lastUp = true;
            return Health.up().withDetail("host", host).build();
        }
        if (lastUp) {
            LOG.warn("code={} The sign-in keys at {} can't be fetched ({}); no sign-in can be checked and readiness is "
                    + "not ready until they can.", SdvErrorCode.AUT_004.code(), host, reason);
        }
        lastUp = false;
        return Health.status(com.sdv.platform.web.schema.ReadinessOnly.NOT_READY).withDetail("code", SdvErrorCode.AUT_004.code()).withDetail("host", host)
                .withDetail("reason", reason).build();
    }

    private synchronized Future<String> check() {
        if (inFlight == null || inFlight.isDone()) inFlight = fetcher.submit(fetch);
        return inFlight;
    }

    @PreDestroy
    void stop() {
        fetcher.shutdownNow();
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
