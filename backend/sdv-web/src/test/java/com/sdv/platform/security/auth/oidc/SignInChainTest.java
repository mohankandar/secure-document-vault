package com.sdv.platform.security.auth.oidc;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import com.sdv.platform.web.foundation.ErrorReplies;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static com.sdv.platform.security.auth.oidc.SignInTestKit.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 0038: every sign-in answer through Vero's real security chain in enterprise-jwt mode, with real signed tokens
 * and a loopback sign-in hub. Each code has its status, its fixed sentence and one log line at its level; no body,
 * header or log line holds anything from the token.
 */
@SpringJUnitWebConfig(SignInTestKit.JwtStack.class)
@TestPropertySource(properties = "sdv.auth.mode=enterprise-jwt")
class SignInChainTest {
    private static final RSAKey KEY = newKey("hub-key-1");
    private static final HttpServer HUB = keyServer(KEY);
    /** Values that are in the tokens and must never be in an answer or a log line. */
    private static final String SUBJECT = "subject-7f3a-do-not-echo";
    private static final String GROUP = "group-4c1d-do-not-echo";

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> everythingSeen = new ArrayList<>();
    private final List<String> tokensSent = new ArrayList<>();
    private ListAppender<ILoggingEvent> logs;
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
        logs = captureLogs(ErrorReplies.class);
    }

    @AfterEach
    void noTokenContentAnywhere() {
        for (ILoggingEvent e : logs.list) everythingSeen.add(e.getFormattedMessage());
        release(logs, ErrorReplies.class);
        for (String seen : everythingSeen) {
            assertFalse(seen.contains(SUBJECT), "the subject leaked: " + seen);
            assertFalse(seen.contains(GROUP), "a group leaked: " + seen);
            assertFalse(seen.contains("Pat Lee"), "the name leaked: " + seen);
            for (String token : tokensSent) assertFalse(seen.contains(token.substring(token.lastIndexOf('.') + 1)), "the token leaked");
            assertFalse(seen.toLowerCase().contains("expired at"), "a token time leaked: " + seen);
        }
    }

    private Answer call(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        Answer answer = answer(mvc, request);
        everythingSeen.add(answer.body());
        if (answer.wwwAuthenticate() != null) everythingSeen.add(answer.wwwAuthenticate());
        return answer;
    }

    private Answer withToken(String path, boolean postIt, String token) throws Exception {
        tokensSent.add(token);
        var request = postIt ? post(path) : get(path);
        return call(request.header("Authorization", "Bearer " + token));
    }

    private JsonNode body(Answer answer, int status, String code, String message) throws Exception {
        assertEquals(status, answer.status(), answer.body());
        JsonNode body = json.readTree(answer.body());
        assertEquals(List.of("code", "message", "reference", "timestamp"), names(body));
        assertEquals(code, body.get("code").asText());
        assertEquals(message, body.get("message").asText());
        return body;
    }

    private List<ILoggingEvent> lines(Level level, String code) {
        return logs.list.stream().filter(e -> e.getLevel() == level && e.getFormattedMessage().contains("code=" + code)).toList();
    }

    private static List<String> names(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** Task 0062: a bound client's token that isn't the client's own is refused; the client's own is accepted. */
    @Test
    void aPersonsTokenThroughABoundClientIsNotASendingSystem() throws Exception {
        assertEquals(200, withToken("/api/v1/ingestion/submissions", true, sign(KEY, sendingSystem())).status());
        assertEquals(200, withToken("/api/v1/ingestion/submissions", true, sign(KEY, sendingSystem().claim("acr", "1").claim("idtyp", "app"))).status());
        for (String claim : List.of("sid", "auth_time", "nonce", "at_hash", "c_hash", "s_hash", "amr", "_claim_names", "hasgroups")) {
            body(withToken("/api/v1/ingestion/submissions", true, sign(KEY, sendingSystem().claim(claim, "x"))), 401, "SDV-AUT-005",
                    "Your sign-in is missing details Vero needs. Your IT team can fix this.");
        }
        body(withToken("/api/v1/ingestion/submissions", true, sign(KEY, sendingSystem().claim("idtyp", "user"))), 401, "SDV-AUT-005",
                "Your sign-in is missing details Vero needs. Your IT team can fix this.");
        for (ILoggingEvent e : lines(Level.WARN, "SDV-AUT-005")) assertTrue(e.getFormattedMessage().contains("isn't the client's own: claim="));
    }

    @Test
    void theInstallersMarkOfASendingSystemMustMatchExactly() {
        var keycloak = new OidcJwtAuthenticationConverter.MachineTokens("preferred_username", "service-account-{client}");
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "RS256").subject("s");
        assertNull(keycloak.problem(jwt.claim("preferred_username", "service-account-c1").build(), "c1"));
        assertNotNull(keycloak.problem(jwt.claim("preferred_username", "pat").build(), "c1"));
        assertNotNull(keycloak.problem(jwt.claim("preferred_username", "service-account-c2").build(), "c1"));
        assertNotNull(keycloak.problem(jwt.claims(c -> c.remove("preferred_username")).build(), "c1"));
        for (String[] bad : new String[][] {{"x", ""}, {"", "x"}}) {
            assertThrows(com.sdv.platform.domain.error.StartupFailure.class, () -> new OidcJwtAuthenticationConverter.MachineTokens(bad[0], bad[1]));
        }
    }

    @Test
    void aGoodPersonTokenSignsIn() throws Exception {
        Answer answer = withToken("/api/v1/session", false, sign(KEY, person(SUBJECT, List.of("vero-users"))));
        assertEquals(200, answer.status(), answer.body());
    }

    /**
     * Task 0039 (owner answer to report 0038, 1): the line is DEBUG, not INFO (it was INFO in task 0038, so this test
     * changed): one INFO line per request without a sign-in was noise. Shown with DEBUG on, and nothing at INFO.
     */
    @Test
    void noTokenIs401Aut001WithABearerHeaderAndADebugLine() throws Exception {
        ch.qos.logback.classic.Logger errors = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ErrorReplies.class);
        Level before = errors.getLevel();
        errors.setLevel(Level.DEBUG);
        try {
            Answer answer = call(get("/api/v1/session"));
            JsonNode body = body(answer, 401, "SDV-AUT-001", "Please sign in again.");
            assertEquals("Bearer", answer.wwwAuthenticate());
            assertEquals(List.of(), lines(Level.INFO, "SDV-AUT-001"));
            List<ILoggingEvent> debug = lines(Level.DEBUG, "SDV-AUT-001");
            assertEquals(1, debug.size());
            assertTrue(debug.getFirst().getFormattedMessage().contains("reference=" + body.get("reference").asText()));
        } finally {
            errors.setLevel(before);
        }
    }

    @Test
    void aTokenSpringRefusesIs401Aut003WithTheReasonClassOnly() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        String expired = sign(KEY, person(SUBJECT, List.of("vero-users"))
                .issueTime(Date.from(past.minusSeconds(300))).expirationTime(Date.from(past)));
        String otherAudience = sign(KEY, person(SUBJECT, List.of("vero-users")).audience("someone-else"));
        String otherIssuer = sign(KEY, person(SUBJECT, List.of("vero-users")).issuer("https://elsewhere.example.test/realms/x"));
        String forged = sign(newKey("hub-key-1"), person(SUBJECT, List.of("vero-users")));
        String[][] cases = {{expired, "expired"}, {otherAudience, "audience"}, {otherIssuer, "issuer"}, {forged, "signature"}};
        for (String[] c : cases) {
            Answer answer = withToken("/api/v1/session", false, c[0]);
            body(answer, 401, "SDV-AUT-003", "Please sign in again.");
            assertEquals("Bearer error=\"invalid_token\", error_description=\"SDV-AUT-003\"", answer.wwwAuthenticate(),
                    "Spring's description (which can hold the token's times) is not sent");
        }
        Answer garbage = call(get("/api/v1/session").header("Authorization", "Bearer not.a.jwt"));
        body(garbage, 401, "SDV-AUT-003", "Please sign in again.");
        List<ILoggingEvent> info = lines(Level.INFO, "SDV-AUT-003");
        assertEquals(5, info.size());
        for (int i = 0; i < cases.length; i++) {
            assertTrue(info.get(i).getFormattedMessage().endsWith("reason=token not accepted: " + cases[i][1]),
                    info.get(i).getFormattedMessage());
        }
    }

    @Test
    void claimsVeroCannotUseAre401Aut005WithTheClaimNameAtWarn() throws Exception {
        Answer noSubject = withToken("/api/v1/session", false, sign(KEY, person(SUBJECT, List.of("vero-users")).subject(null)));
        body(noSubject, 401, "SDV-AUT-005", "Your sign-in is missing details Vero needs. Your IT team can fix this.");
        String badSource = sign(KEY, sendingSystem().claim("client_id", "another-client-" + SUBJECT));
        body(withToken("/api/v1/ingestion/submissions", true, badSource), 401, "SDV-AUT-005",
                "Your sign-in is missing details Vero needs. Your IT team can fix this.");
        List<ILoggingEvent> warn = lines(Level.WARN, "SDV-AUT-005");
        assertEquals(2, warn.size());
        assertTrue(warn.get(0).getFormattedMessage().contains("claim=sub is missing"));
        assertTrue(warn.get(1).getFormattedMessage().contains("client-ID claims"));
        assertFalse(warn.get(1).getFormattedMessage().contains("another-client-"), "the log names the claims, not the values");
    }

    @Test
    void noActiveConnectionIs401Aut006WithTheIssuerHostAtWarn() throws Exception {
        CONNECTION_ACTIVE.set(false);
        Answer answer = withToken("/api/v1/session", false, sign(KEY, person(SUBJECT, List.of("vero-users"))));
        body(answer, 401, "SDV-AUT-006", "Vero isn't set up to accept this sign-in. Your IT team can fix this.");
        List<ILoggingEvent> warn = lines(Level.WARN, "SDV-AUT-006");
        assertEquals(1, warn.size());
        assertTrue(warn.getFirst().getFormattedMessage().contains("issuer host=idp.example.test"));
    }

    /**
     * Task 0039 (owner answer to report 0038, 2): the INFO line names the subject, so IT knows who was refused; never
     * the token, the groups or the name. Changed from task 0038, where the line named no subject: this test used the
     * do-not-echo subject before; it now uses its own subject, which only the log line may hold.
     */
    @Test
    void noVeroRoleIs401Aut007AtInfoWithTheSubjectAndNamesNoGroup() throws Exception {
        String who = "pat.lee-0039";
        Answer answer = withToken("/api/v1/session", false, sign(KEY, person(who, List.of(GROUP))));
        JsonNode body = body(answer, 401, "SDV-AUT-007",
                "You're signed in, but you don't have access to Vero. Ask your IT team to add you to a Vero group.");
        assertFalse(body.toString().contains(who), "the answer never names the subject");
        assertFalse(answer.wwwAuthenticate().contains(who));
        List<ILoggingEvent> info = lines(Level.INFO, "SDV-AUT-007");
        assertEquals(1, info.size());
        assertTrue(info.getFirst().getFormattedMessage().contains("subject=" + who), info.getFirst().getFormattedMessage());
    }

    /**
     * Task 0039 (owner answer to report 0038, 3): every signed-in request checks that the tenant is active, so a
     * suspended tenant is refused everywhere, also on addresses that never read the tenant (here the session and the
     * intake probe). The same answer for an unknown tenant. (The cache time itself: ActiveTenantCacheTest.)
     */
    @Test
    void aSuspendedTenantIsRefusedOnEveryAddress() throws Exception {
        String personToken = sign(KEY, person(SUBJECT, List.of("vero-users")));
        String systemToken = sign(KEY, sendingSystem());
        assertEquals(200, withToken("/api/v1/session", false, personToken).status());

        TENANT_STATUS.set(com.sdv.platform.domain.tenant.TenantStatus.SUSPENDED);
        String sentence = "Your organisation's Vero account isn't available. Contact your IT team.";
        JsonNode person = body(withToken("/api/v1/session", false, personToken), 403, "SDV-AUT-009", sentence);
        body(withToken("/api/v1/business/home", false, personToken), 403, "SDV-AUT-009", sentence);
        JsonNode system = body(withToken("/api/v1/ingestion/submissions", true, systemToken), 403, "SDV-AUT-009", sentence);
        assertEquals(person.get("message"), system.get("message"));
        List<ILoggingEvent> warn = lines(Level.WARN, "SDV-AUT-009");
        assertEquals(3, warn.size());
        assertTrue(warn.getFirst().getFormattedMessage().contains("tenant=" + TENANT + " suspended (SUSPENDED) (request check)"));

        TENANT_STATUS.set(null);
        body(withToken("/api/v1/session", false, personToken), 403, "SDV-AUT-009", sentence);
        assertTrue(lines(Level.WARN, "SDV-AUT-009").getLast().getFormattedMessage().contains("missing"));

        TENANT_STATUS.set(com.sdv.platform.domain.tenant.TenantStatus.ACTIVE);
        assertEquals(200, withToken("/api/v1/session", false, personToken).status());
    }

    @Test
    void theWrongKindOfSignInIs403Aut002() throws Exception {
        String personToken = sign(KEY, person(SUBJECT, List.of("vero-users")));
        Answer onIntake = withToken("/api/v1/ingestion/submissions", true, personToken);
        body(onIntake, 403, "SDV-AUT-002", "This address is for sending systems only.");
        Answer onPersons = withToken("/api/v1/business/home", false, sign(KEY, sendingSystem()));
        body(onPersons, 403, "SDV-AUT-002", "This address is for people, not sending systems.");
        List<ILoggingEvent> info = lines(Level.INFO, "SDV-AUT-002");
        assertEquals(2, info.size());
        assertTrue(info.get(0).getFormattedMessage().contains("principal=person on an address for sending systems"));
    }


    /**
     * Task 0041: the grants can't be read (the database is away). The sign-in fails closed with Vero's 500
     * (SDV-SYS-013), never a person signed in without their grants. SDV-IDP-007 (two ACTIVE connections) is retired:
     * platform-core allows one ACTIVE connection per customer and product.
     */
    @Test
    void grantsThatCannotBeReadFailClosedWith500() throws Exception {
        String token = sign(KEY, person(SUBJECT, List.of("vero-users")));
        LOOKUP_FAILURE.set(new IllegalStateException("The group grants could not be read (POLICY_UNAVAILABLE)."));
        Answer answer = withToken("/api/v1/session", false, token);
        assertInstanceOf(IllegalStateException.class, rootCause(answer.thrown()), "it leaves Spring's chain");
        body(answer, 500, "SDV-SYS-013", "Vero couldn't finish this just now. Try again. If it keeps happening, give your IT team the reference.");
    }

    private static Throwable rootCause(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }
}
