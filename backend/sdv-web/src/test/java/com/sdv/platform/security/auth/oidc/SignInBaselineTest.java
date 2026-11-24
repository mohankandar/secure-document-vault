package com.sdv.platform.security.auth.oidc;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static com.sdv.platform.security.auth.oidc.SignInTestKit.*;
import static com.sdv.platform.web.baseline.Baseline.matches;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/**
 * Task 0060 (plan 0059, step 0): today's sign-in edge, recorded on Spring Boot 3.5 before the move to 4.2: which tokens
 * are accepted by their {@code typ} and algorithm, how the Authorization header is read, which addresses are open and
 * which near misses are not, every header of a 401, and a cross-origin preflight.
 */
@SpringJUnitWebConfig(SignInTestKit.JwtStack.class)
@TestPropertySource(properties = "sdv.auth.mode=enterprise-jwt")
class SignInBaselineTest {
    private static final RSAKey KEY = newKey("hub-key-1");
    private static final HttpServer HUB = keyServer(KEY);

    private MockMvc mvc;
    @Autowired WebApplicationContext context;

    @DynamicPropertySource
    static void hub(DynamicPropertyRegistry registry) {
        registry.add("test.jwks", () -> "http://127.0.0.1:" + HUB.getAddress().getPort() + "/certs");
    }

    @AfterAll
    static void stopHub() {
        HUB.stop(0);
    }

    @BeforeEach
    void setUp() {
        mvc = mvc(context);
        resetPlatform();
        TENANT_STATUS.set(com.sdv.platform.domain.tenant.TenantStatus.ACTIVE);
    }

    private static String shown(Answer a) {
        return a.status() == 200 ? "200" : a.status() + " " + code(a.body());
    }

    private static String code(String body) {
        var m = Pattern.compile("\"code\":\"([^\"]+)\"").matcher(body);
        return m.find() ? m.group(1) : "(no code)";
    }

    private String withToken(String token) throws Exception {
        return shown(answer(mvc, get("/api/v1/session").header("Authorization", "Bearer " + token)));
    }

    private static String typed(String typ) throws Exception {
        JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID());
        if (typ != null) header.type(new JOSEObjectType(typ));
        SignedJWT jwt = new SignedJWT(header.build(), person("subject", List.of("vero-users")).build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    /** Task 0065 (VSEC-11), through the real decoder: Spring would make up a missing issue time. */
    @Test
    void aTokenNeedsBothItsTimesAndNoIdTokenMarks() throws Exception {
        assertEquals("200", withToken(sign(KEY, person("subject", List.of("vero-users")))));
        assertEquals("401 SDV-AUT-003", withToken(sign(KEY, person("subject", List.of("vero-users")).issueTime(null))));
        assertEquals("401 SDV-AUT-003", withToken(sign(KEY, person("subject", List.of("vero-users")).expirationTime(null))));
        assertEquals("401 SDV-AUT-003", withToken(sign(KEY, person("subject", List.of("vero-users")).claim("nonce", "x"))));
        assertEquals("401 SDV-AUT-003", withToken(sign(KEY, person("subject", List.of("vero-users")).expirationTime(new java.util.Date(System.currentTimeMillis() + 3 * 3_600_000)))));
    }

    @Test
    void theTokenTypesThatSignIn() throws Exception {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String typ : new String[] {null, "JWT", "jwt", "at+jwt", "application/at+jwt", "logout+jwt", "dpop+jwt", "nonsense"}) {
            seen.put(String.valueOf(typ), withToken(typed(typ)));
        }
        Map<String, String> expected = new LinkedHashMap<>();
        // Task 0065 (VSEC-11): at+jwt is an access token's type (RFC 9068), accepted since.
        for (String accepted : List.of("null", "JWT", "jwt", "at+jwt", "application/at+jwt")) expected.put(accepted, "200");
        for (String refused : List.of("logout+jwt", "dpop+jwt", "nonsense")) {
            expected.put(refused, "401 SDV-AUT-003");
        }
        assertEquals(expected, seen);
    }

    @Test
    void theAlgorithmsAndKeysThatSignIn() throws Exception {
        var claims = person("subject", List.of("vero-users")).build();
        SignedJWT hs = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KEY.getKeyID()).build(), claims);
        hs.sign(new MACSigner(new byte[32]));
        SignedJWT rs512 = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS512).keyID(KEY.getKeyID()).build(), claims);
        rs512.sign(new RSASSASigner(KEY));
        SignedJWT noKid = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
        noKid.sign(new RSASSASigner(KEY));
        assertEquals("401 SDV-AUT-003", withToken(new PlainJWT(claims).serialize()));
        assertEquals("401 SDV-AUT-003", withToken(hs.serialize()));
        assertEquals("401 SDV-AUT-003", withToken(rs512.serialize()));
        assertEquals("200", withToken(noKid.serialize()));
    }

    @Test
    void howTheAuthorizationHeaderIsRead() throws Exception {
        String token = sign(KEY, person("subject", List.of("vero-users")));
        StringBuilder text = new StringBuilder();
        for (String header : List.of("Bearer T", "bearer T", "BEARER T", "Bearer  T", "Bearer T ", "Bearer", "Bearer ", "Basic T", "T", "Bearer T,T")) {
            Answer a = answer(mvc, get("/api/v1/session").header("Authorization", header.replace("T", token)));
            text.append('[').append(header).append("] -> ").append(shown(a)).append(a.status() == 200 ? "" : " " + a.wwwAuthenticate()).append('\n');
        }
        Answer query = answer(mvc, get("/api/v1/session").param("access_token", token));
        text.append("in the address -> ").append(shown(query)).append(' ').append(query.wwwAuthenticate()).append('\n');
        matches("authorization-header", text);
    }

    /** "open": the chain lets a caller without a sign-in through. What a controller then answers isn't recorded here. */
    private String outcome(HttpMethod method, String path) throws Exception {
        MockHttpServletResponse r;
        try {
            r = mvc.perform(MockMvcRequestBuilders.request(method, URI.create("http://localhost" + path))).andReturn().getResponse();
        } catch (Exception ex) {
            return "thrown " + ex.getClass().getSimpleName();
        }
        return switch (r.getStatus()) {
            case 401 -> "401";
            case 400 -> "400";
            default -> "open";
        };
    }

    @Test
    void theOpenAddressesAndTheirNearMisses() throws Exception {
        StringBuilder text = new StringBuilder();
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info",
                "/api/v1/system/info", "/error",
                "/", "/actuator", "/actuator/", "/actuator/env", "/actuator/health/", "/actuator/health/readiness/",
                "/actuator/health/other", "/actuator/health/readiness/databaseSchema", "/actuator/health.json", "/Actuator/health",
                "/actuator/HEALTH", "/actuator/%68ealth", "/actuator/info/", "/actuator/info/build", "/api/v1/system",
                "/api/v1/system/info/", "/api/v1/system/info/x", "/api/v1/system/informal", "/error/", "/errors", "/api/v1/session",
                "/api/v1/session/", "//actuator/health", "/actuator/health;x=y", "/actuator/../api/v1/session",
                "/actuator/health/%2e%2e/env", "/actuator%2Fhealth", "/actuator/health%00")) {
            text.append(path).append(" -> ").append(outcome(HttpMethod.GET, path)).append('\n');
        }
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.TRACE, HttpMethod.OPTIONS)) {
            for (String path : List.of("/actuator/health", "/api/v1/session")) {
                text.append(method).append(' ').append(path).append(" -> ").append(outcome(method, path)).append('\n');
            }
        }
        matches("open-addresses", text);
    }

    private static String headers(MockHttpServletResponse r) {
        Map<String, Object> all = new TreeMap<>();
        for (String name : r.getHeaderNames()) {
            all.put(name, name.toLowerCase().contains("correlation") ? "(an ID)" : r.getHeaders(name));
        }
        return r.getStatus() + " " + all + "\n";
    }

    @Test
    void everyHeaderOfA401() throws Exception {
        matches("headers-of-a-401", headers(mvc.perform(get("/api/v1/session")).andReturn().getResponse())
                + headers(mvc.perform(get("/api/v1/session").header("Authorization", "Bearer not.a.jwt")).andReturn().getResponse()));
    }

    @Test
    void anotherOriginGetsNoPermission() throws Exception {
        MockHttpServletResponse preflight = mvc.perform(options("/api/v1/session").header("Origin", "https://elsewhere.example.test")
                .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "authorization"))
                .andReturn().getResponse();
        String token = sign(KEY, person("subject", List.of("vero-users")));
        MockHttpServletResponse simple = mvc.perform(get("/api/v1/session").header("Origin", "https://elsewhere.example.test")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();
        for (MockHttpServletResponse r : List.of(preflight, simple)) {
            for (String name : r.getHeaderNames()) assertFalse(name.toLowerCase().startsWith("access-control-allow"), name);
        }
        matches("another-origin", "preflight " + preflight.getStatus() + "\nget " + simple.getStatus() + "\n");
    }
}
