package com.sdv.platform.security.auth.enterprise;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import com.sdv.platform.domain.error.InternalCheckFailed;
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

import static com.sdv.platform.security.auth.enterprise.SignInTestKit.*;
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
        CONNECTIONS.set(SignInTestKit::bound);
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

    @Test
    void aGoodPersonTokenSignsIn() throws Exception {
        Answer answer = withToken("/api/v1/session", false, sign(KEY, person(SUBJECT, List.of("vero-users"))));
        assertEquals(200, answer.status(), answer.body());
    }

    @Test
    void noTokenIs401Aut001WithABearerHeaderAndAnInfoLine() throws Exception {
        Answer answer = call(get("/api/v1/session"));
        JsonNode body = body(answer, 401, "SDV-AUT-001", "Please sign in again.");
        assertEquals("Bearer", answer.wwwAuthenticate());
        List<ILoggingEvent> info = lines(Level.INFO, "SDV-AUT-001");
        assertEquals(1, info.size());
        assertTrue(info.getFirst().getFormattedMessage().contains("reference=" + body.get("reference").asText()));
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
        String badSource = sign(KEY, sendingSystem().claim("sdv_source_id", "not-a-uuid-" + SUBJECT));
        body(withToken("/api/v1/ingestion/submissions", true, badSource), 401, "SDV-AUT-005",
                "Your sign-in is missing details Vero needs. Your IT team can fix this.");
        List<ILoggingEvent> warn = lines(Level.WARN, "SDV-AUT-005");
        assertEquals(2, warn.size());
        assertTrue(warn.get(0).getFormattedMessage().contains("claim=sub is missing"));
        assertTrue(warn.get(1).getFormattedMessage().contains("claim=sdv_source_id is not a UUID"));
    }

    @Test
    void noActiveConnectionIs401Aut006WithTheIssuerHostAtWarn() throws Exception {
        CONNECTIONS.set(issuer -> Optional.empty());
        Answer answer = withToken("/api/v1/session", false, sign(KEY, person(SUBJECT, List.of("vero-users"))));
        body(answer, 401, "SDV-AUT-006", "Vero isn't set up to accept this sign-in. Your IT team can fix this.");
        List<ILoggingEvent> warn = lines(Level.WARN, "SDV-AUT-006");
        assertEquals(1, warn.size());
        assertTrue(warn.getFirst().getFormattedMessage().contains("issuer host=idp.example.test"));
    }

    @Test
    void noVeroRoleIs401Aut007AtInfoAndNamesNoGroup() throws Exception {
        Answer answer = withToken("/api/v1/session", false, sign(KEY, person(SUBJECT, List.of(GROUP))));
        body(answer, 401, "SDV-AUT-007",
                "You're signed in, but you don't have access to Vero. Ask your IT team to add you to a Vero group.");
        assertEquals(1, lines(Level.INFO, "SDV-AUT-007").size());
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
     * IDP-007, "likely" in the catalog, confirmed: before task 0038 the repository threw IllegalStateException, which
     * left Spring's chain and was answered 500 SDV-SYS-013 at every sign-in. Now it is 500 SDV-IDP-007 with its sentence.
     */
    @Test
    void twoActiveConnectionsWereA500AtEverySignInAndAreNowIdp007() throws Exception {
        String token = sign(KEY, person(SUBJECT, List.of("vero-users")));
        CONNECTIONS.set(issuer -> { throw new IllegalStateException("Ambiguous ACTIVE OIDC connection binding"); });
        Answer before = withToken("/api/v1/session", false, token);
        assertInstanceOf(IllegalStateException.class, rootCause(before.thrown()), "it leaves Spring's chain");
        body(before, 500, "SDV-SYS-013", "Vero couldn't finish this just now. Try again. If it keeps happening, give your IT team the reference.");

        CONNECTIONS.set(issuer -> { throw InternalCheckFailed.ambiguousSignInConnection("Two or more ACTIVE sign-in connections match issuer=" + issuer); });
        Answer now = withToken("/api/v1/session", false, token);
        body(now, 500, "SDV-IDP-007", "Vero can't sign you in because its sign-in setup is inconsistent. Your IT team has been told.");
        List<ILoggingEvent> error = lines(Level.ERROR, "SDV-IDP-007");
        assertEquals(1, error.size());
        assertNotNull(error.getFirst().getThrowableProxy(), "the log line holds the check's sentence for IT");
        assertTrue(error.getFirst().getThrowableProxy().getMessage().contains("issuer=" + ISSUER));
    }

    private static Throwable rootCause(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }
}
