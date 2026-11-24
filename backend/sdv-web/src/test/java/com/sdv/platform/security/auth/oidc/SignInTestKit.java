package com.sdv.platform.security.auth.oidc;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.documentplatform.identity.IdentityClaimConfig;
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

/**
 * Task 0038: a small Vero web stack for sign-in tests. Vero's real security chain ({@link SecurityConfiguration}), its
 * real token decoder ({@link OidcJwtConfiguration#enterpriseJwtDecoder}) and converter, its error handling, and a
 * local sign-in hub that serves signing keys from a loopback port. MockMvc doesn't forward {@code sendError} to
 * /error the way the servlet container does, so {@link #answer} runs Vero's error page on the same request.
 */
final class SignInTestKit {
    static final String ISSUER = "https://idp.example.test/realms/vero";
    static final String AUDIENCE = "vero-api";
    static final String PROVIDER = "customer-oidc";
    /** Task 0041: the test entity is a platform-core slot; its UUID is derived from the slot ID. */
    static final String SLOT = "ENT-VERO-TEST-0001";
    static final UUID TENANT = com.documentplatform.identity.EntityUuid.fromSlotId(SLOT);
    static final UUID SOURCE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    /** The sending system's client ID, bound by setup's client-binding file to {@link #SLOT} and source "intake-source". */
    static final String CLIENT = "intake-client-0041";
    static final String SOURCE_KEY = "intake-source";

    /**
     * Task 0041: the platform-core lookups, in memory (the idn_ tables are tested on PostgreSQL elsewhere). Each test
     * may switch the customer's login connection off, or make the lookups fail. Not cached, unlike the product.
     */
    static final java.util.concurrent.atomic.AtomicBoolean CONNECTION_ACTIVE = new java.util.concurrent.atomic.AtomicBoolean(true);
    static final AtomicReference<RuntimeException> LOOKUP_FAILURE = new AtomicReference<>();
    /** Whether the entity has the sending system the client binding names. */
    static final java.util.concurrent.atomic.AtomicBoolean SOURCE_SET_UP = new java.util.concurrent.atomic.AtomicBoolean(true);

    static void resetPlatform() {
        CONNECTION_ACTIVE.set(true);
        LOOKUP_FAILURE.set(null);
        SOURCE_SET_UP.set(true);
    }

    /** Vero's converter over the in-memory platform lookups: group "vero-users" is Business user in {@link #TENANT}. */
    static OidcJwtAuthenticationConverter converter() {
        var grants = Map.of(com.documentplatform.identity.admission.EntityId.of(TENANT), Map.of("vero-users", Set.of("BUSINESS_USER")));
        com.documentplatform.identity.admission.EntityGroupGrants grantLookup = key -> {
            if (LOOKUP_FAILURE.get() != null) throw LOOKUP_FAILURE.get();
            return CONNECTION_ACTIVE.get() ? grants : Map.of();
        };
        com.documentplatform.identity.admission.ActiveEntityCandidates candidates = key ->
                CONNECTION_ACTIVE.get() ? grants.keySet() : Set.of();
        var binding = com.documentplatform.identity.store.MachineBinding.create("TEN-VERO-TEST-0001", "SDV", CLIENT, SLOT,
                SOURCE_KEY, "test sending system");
        var senders = new OidcJwtAuthenticationConverter.Senders() {
            @Override public Optional<com.documentplatform.identity.store.MachineBinding> activeBinding(String clientId) {
                return CLIENT.equals(clientId) ? Optional.of(binding) : Optional.empty();
            }
            @Override public Set<com.documentplatform.identity.admission.EntityId> admissible(
                    com.documentplatform.identity.admission.ConnectionKey key, String clientId) {
                return CLIENT.equals(clientId) && CONNECTION_ACTIVE.get() ? grants.keySet() : Set.of();
            }
        };
        var lookups = new OidcJwtAuthenticationConverter.SignInLookups() {
            @Override public boolean connectionActive(com.documentplatform.identity.admission.ConnectionKey key) { return CONNECTION_ACTIVE.get(); }
            @Override public List<String> activatableSlots(com.documentplatform.identity.admission.ConnectionKey key, Object groups) { return List.of(); }
        };
        var settings = new OidcJwtAuthenticationConverter.Settings(PROVIDER, AUDIENCE, "groups",
                OidcJwtAuthenticationConverter.DEFAULT_CLIENT_ID_CLAIMS, "sdv_entity_id", "sdv_source_id", "sdv_source_key");
        var people = new com.documentplatform.identity.admission.EntityMembershipResolver(grantLookup, candidates,
                SdvRoleCatalog.shipped(), new com.documentplatform.identity.admission.AdmissionSettings(false, true, PROVIDER, AUDIENCE));
        return new OidcJwtAuthenticationConverter(settings,
                new OidcJwtIdentityNormalizer(PROVIDER, new IdentityClaimConfig("preferred_username", "email", "name", "groups")),
                people, new PlatformLevelGrants(key -> Map.of(), candidates, SdvRoleCatalog.shipped()), lookups, senders,
                (entity, key) -> SOURCE_SET_UP.get() && TENANT.equals(entity) && SOURCE_KEY.equals(key) ? Optional.of(SOURCE) : Optional.empty(),
                OidcJwtAuthenticationConverter.RequestView.CURRENT, conflict -> { });
    }

    /** Task 0039: the tenant's status as the per-request tenant check reads it; null: no such tenant. */
    static final AtomicReference<com.sdv.platform.domain.tenant.TenantStatus> TENANT_STATUS =
            new AtomicReference<>(com.sdv.platform.domain.tenant.TenantStatus.ACTIVE);

    private SignInTestKit() { }

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
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(subject).claim("azp", "vero-web")
                .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("groups", groups).claim("name", "Pat Lee");
    }

    static JWTClaimsSet.Builder sendingSystem() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject("service-account-intake")
                .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("azp", CLIENT);
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
            return new OidcJwtConfiguration().enterpriseJwtDecoder(ISSUER, jwks, AUDIENCE, 0);
        }

        @Bean OidcJwtAuthenticationConverter converter() {
            return SignInTestKit.converter();
        }

        @Bean Probe probe() { return new Probe(); }

        /** Task 0039: the per-request tenant check; each test sets the tenant's status in TENANT_STATUS (0 s cache). */
        @Bean com.sdv.platform.application.access.ActiveTenantCache tenantCheck() {
            return new com.sdv.platform.application.access.ActiveTenantCache(tenantId -> {
                var status = TENANT_STATUS.get();
                return status == null || !TENANT.equals(tenantId) ? Optional.empty()
                        : Optional.of(new com.sdv.platform.domain.tenant.Tenant(TENANT, "acme", "Acme", status,
                                com.sdv.platform.domain.tenant.TenantGuardrails.defaults(), Instant.EPOCH, Instant.EPOCH));
            }, 0);
        }

        @Bean FoundationExceptionHandler errors() { return new FoundationExceptionHandler(); }
    }
}
