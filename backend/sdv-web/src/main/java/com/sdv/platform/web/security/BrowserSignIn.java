package com.sdv.platform.web.security;

import com.sdv.platform.application.json.VeroJson;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.signin.LocalTestIssuers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.persistence.identity.PlatformIdentityReads.BrowserConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Task 0065: the login system's addresses for the browser, from its discovery document, fetched under platform-core's
 * outbound rules. Kept 5 minutes; when a later fetch fails, the last good ones serve up to an hour. A failure is
 * remembered for 10 seconds, so an open address can't be used to call the login system over and over.
 */
@Component
public class BrowserSignIn {
    static final Duration FRESH = Duration.ofMinutes(5);
    static final Duration LAST_GOOD = Duration.ofHours(1);
    static final Duration FAILED = Duration.ofSeconds(10);
    private static final int MAX_BYTES = 256 * 1024;
    private static final Logger log = LoggerFactory.getLogger(BrowserSignIn.class);

    /** endSession and revocation are null when the login system has none. */
    public record Endpoints(String authorization, String token, String endSession, String revocation) { }

    private record Kept(Endpoints endpoints, Instant at) { }

    private final IssuerFetch fetch;
    private final DevelopmentLicence developmentLicence;
    private final Clock clock;
    private final ObjectMapper json = VeroJson.mapper();
    private final Map<String, Kept> kept = new HashMap<>();
    private final Map<String, Instant> failed = new HashMap<>();

    @Autowired
    public BrowserSignIn(ObjectProvider<IssuerFetch> fetch, DevelopmentLicence developmentLicence) {
        this(fetch.getIfAvailable(), developmentLicence, Clock.systemUTC());
    }

    BrowserSignIn(IssuerFetch fetch, DevelopmentLicence developmentLicence, Clock clock) {
        this.fetch = fetch;
        this.developmentLicence = developmentLicence;
        this.clock = clock;
    }

    /** The addresses; null when they can't be read now. */
    public synchronized Endpoints endpoints(BrowserConnection connection) {
        Instant now = clock.instant();
        String issuer = connection.issuer();
        Kept last = kept.get(issuer);
        if (last != null && last.at().plus(FRESH).isAfter(now)) return last.endpoints();
        Instant failure = failed.get(issuer);
        if (failure == null || !failure.plus(FAILED).isAfter(now)) {
            try {
                Endpoints read = read(issuer);
                kept.put(issuer, new Kept(read, now));
                failed.remove(issuer);
                return read;
            } catch (IOException | RuntimeException ex) {
                failed.put(issuer, now);
                log.warn("The login system's addresses can't be read (issuer host={}): {}", host(issuer), ex.getMessage());
            }
        }
        return last != null && last.at().plus(LAST_GOOD).isAfter(now) ? last.endpoints() : null;
    }

    private Endpoints read(String issuer) throws IOException {
        if (fetch == null) throw new IOException("no outbound fetch");
        URI uri = URI.create(issuer);
        boolean localHttp = LocalTestIssuers.isLocalHttp(uri);
        if (LocalTestIssuers.check(uri, developmentLicence.signedByDevelopmentKey()).isPresent()) throw new IOException("an http login system is not allowed here");
        IssuerFetch.Response answer = fetch.get(URI.create(issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration"), MAX_BYTES, localHttp);
        if (answer.status() != 200) throw new IOException("discovery answered " + answer.status());
        JsonNode document = json.readTree(answer.body());
        if (!issuer.equals(document.path("issuer").asString(null))) throw new IOException("discovery names another issuer");
        JsonNode methods = document.path("code_challenge_methods_supported");
        if (methods.isArray() && methods.valueStream().noneMatch(m -> "S256".equals(m.asString(null)))) throw new IOException("discovery lists no PKCE S256");
        return new Endpoints(address(document, "authorization_endpoint", true, localHttp), address(document, "token_endpoint", true, localHttp),
                address(document, "end_session_endpoint", false, localHttp), address(document, "revocation_endpoint", false, localHttp));
    }

    private static String address(JsonNode document, String name, boolean required, boolean localHttp) throws IOException {
        String text = document.path(name).asString(null);
        if (text == null || text.isBlank()) {
            if (required) throw new IOException("discovery has no " + name);
            return null;
        }
        URI uri;
        try {
            uri = URI.create(text);
        } catch (IllegalArgumentException ex) {
            throw new IOException("discovery's " + name + " is not an address");
        }
        boolean allowed = uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null
                && ("https".equalsIgnoreCase(uri.getScheme()) || localHttp && LocalTestIssuers.isLocalHttp(uri));
        if (!allowed) throw new IOException("discovery's " + name + " is not an https address");
        return text;
    }

    private static String host(String issuer) {
        try {
            return URI.create(issuer).getHost();
        } catch (IllegalArgumentException ex) {
            return "-";
        }
    }
}
