package com.sdv.platform.security.auth.enterprise;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.OidcConnectionBinding;
import com.documentplatform.identity.OidcConnectionBindingResolver;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.security.config.SecurityConfiguration;
import com.sdv.platform.web.config.CorrelationIdFilter;
import com.sdv.platform.web.foundation.FoundationExceptionHandler;
import com.sdv.platform.web.foundation.SdvErrorController;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import jakarta.servlet.RequestDispatcher;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Task 0038: a small Vero web stack for sign-in tests. Vero's real security chain ({@link SecurityConfiguration}), its
 * real token decoder ({@link EnterpriseJwtConfiguration#enterpriseJwtDecoder}) and converter, its error handling, and a
 * local sign-in hub that serves signing keys from a loopback port. MockMvc doesn't forward {@code sendError} to
 * /error the way the servlet container does, so {@link #answer} runs Vero's error page on the same request.
 */
final class SignInTestKit {
    static final String ISSUER = "https://idp.example.test/realms/vero";
    static final String AUDIENCE = "vero-api";
    static final String PROVIDER = "customer-oidc";
    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID SOURCE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** What the converter's connection lookup does; each test sets it. Not cached, unlike the product's 30 s cache. */
    static final AtomicReference<Function<URI, Optional<OidcConnectionBinding>>> CONNECTIONS =
            new AtomicReference<>(SignInTestKit::bound);

    private SignInTestKit() { }

    static Optional<OidcConnectionBinding> bound(URI issuer) {
        return Optional.of(new OidcConnectionBinding(UUID.randomUUID(), TENANT, PROVIDER, issuer, AUDIENCE, 1L));
    }

    static RSAKey newKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A loopback sign-in hub serving {@code key}'s public half at /certs. */
    static HttpServer keyServer(RSAKey key) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A loopback port nothing listens on: the hub is down. */
    static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static JWTClaimsSet.Builder person(String subject, List<String> groups) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(subject)
                .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("groups", groups).claim("name", "Pat Lee");
    }

    static JWTClaimsSet.Builder sendingSystem() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject("service-account-intake")
                .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("sdv_tenant_id", TENANT.toString()).claim("sdv_source_id", SOURCE.toString());
    }

    static String sign(RSAKey key, JWTClaimsSet.Builder claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    static MockMvc mvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    /** The answer a client sees: the chain's status and headers, then Vero's error page for the body. */
    record Answer(int status, String wwwAuthenticate, String body, Throwable thrown) { }

    static Answer answer(MockMvc mvc, RequestBuilder request) throws Exception {
        MvcResult result;
        try {
            result = mvc.perform(request).andReturn();
        } catch (Exception ex) {
            // What the servlet container does with an exception that left the filters: it goes to /error.
            MockHttpServletRequest error = new MockHttpServletRequest("GET", "/error");
            error.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
            error.setAttribute(RequestDispatcher.ERROR_EXCEPTION, ex);
            MockHttpServletResponse page = new MockHttpServletResponse();
            new SdvErrorController().error(error, page);
            return new Answer(page.getStatus(), null, page.getContentAsString(), ex);
        }
        MockHttpServletResponse response = result.getResponse();
        if (response.getErrorMessage() == null && response.getStatus() < 400) {
            return new Answer(response.getStatus(), null, response.getContentAsString(), null);
        }
        if (response.getErrorMessage() == null) {
            // Answered by Vero's exception handler: the body is already written.
            return new Answer(response.getStatus(), response.getHeader("WWW-Authenticate"), response.getContentAsString(), null);
        }
        MockHttpServletRequest same = (MockHttpServletRequest) result.getRequest();
        same.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, response.getStatus());
        MockHttpServletResponse page = new MockHttpServletResponse();
        new SdvErrorController().error(same, page);
        return new Answer(response.getStatus(), response.getHeader("WWW-Authenticate"), page.getContentAsString(), null);
    }

    static ListAppender<ILoggingEvent> captureLogs(Class<?>... sources) {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        for (Class<?> source : sources) ((Logger) LoggerFactory.getLogger(source)).addAppender(logs);
        return logs;
    }

    static void release(ListAppender<ILoggingEvent> logs, Class<?>... sources) {
        for (Class<?> source : sources) ((Logger) LoggerFactory.getLogger(source)).detachAppender(logs);
    }

    @RestController
    static class Probe {
        private final SecurityContextFacade security = new SecurityContextFacade();

        @GetMapping("/api/v1/session") String person() { return "signed in"; }

        @GetMapping("/api/v1/business/home") String personOnly() { security.requirePrincipal(); return "person"; }

        @PostMapping("/api/v1/ingestion/submissions") String intake() {
            security.requireIngestionSourcePrincipal();
            return "sending system";
        }
    }

    /** Vero's chain in enterprise-jwt mode, keys from {@code test.jwks}. */
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfiguration.class)
    static class JwtStack {
        @Bean JwtDecoder enterpriseJwtDecoder(@Value("${test.jwks}") String jwks) {
            return new EnterpriseJwtConfiguration().enterpriseJwtDecoder(ISSUER, jwks, AUDIENCE, 0);
        }

        @Bean EnterpriseJwtAuthenticationConverter converter() {
            OidcConnectionBindingResolver resolver = (provider, issuer, audience) -> CONNECTIONS.get().apply(issuer);
            return new EnterpriseJwtAuthenticationConverter("sdv_tenant_id", null, "sdv_source_id", "groups",
                    new OidcJwtIdentityNormalizer(PROVIDER, new IdentityClaimConfig("preferred_username", "email", "name", "groups")),
                    new SdvGroupRoleMapper(Map.of(SdvRole.BUSINESS_USER, Set.of("vero-users")), false),
                    resolver, PROVIDER, AUDIENCE, conflict -> { });
        }

        @Bean Probe probe() { return new Probe(); }

        @Bean FoundationExceptionHandler errors() { return new FoundationExceptionHandler(); }
    }
}
