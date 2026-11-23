package com.sdv.platform.security.auth.oidc;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import com.sdv.platform.web.foundation.ErrorReplies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static com.sdv.platform.security.auth.oidc.SignInTestKit.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Task 0038, SDV-AUT-004 ("likely" in the catalog): the sign-in hub's keys can't be fetched. The test confirms what
 * Spring does on its own, then that Vero's chain answers 503 SDV-AUT-004 instead.
 */
@SpringJUnitWebConfig(SignInTestKit.JwtStack.class)
@TestPropertySource(properties = "sdv.auth.mode=enterprise-jwt")
class SignInKeysDownTest {
    private static final int DOWN = closedPort();
    private static final RSAKey KEY = newKey("hub-key-1");

    private final ObjectMapper json = new ObjectMapper();
    private ListAppender<ILoggingEvent> logs;
    private MockMvc mvc;

    @Autowired WebApplicationContext context;
    @Autowired JwtDecoder decoder;

    @DynamicPropertySource
    static void hub(DynamicPropertyRegistry registry) {
        registry.add("test.jwks", () -> "http://127.0.0.1:" + DOWN + "/certs");
    }

    @BeforeEach
    void setUp() {
        mvc = mvc(context);
        resetPlatform();
        logs = captureLogs(ErrorReplies.class);
    }

    @AfterEach
    void tearDown() {
        release(logs, ErrorReplies.class);
        SecurityContextHolder.clearContext();
    }

    /**
     * What Spring does by itself (its own bearer filter and failure handler, Vero's decoder): the fetch failure is an
     * AuthenticationServiceException that Spring rethrows out of the filter. In a running Vero that reached /error as
     * an unexpected failure: 500 SDV-SYS-013, which says nothing about the sign-in hub.
     */
    @Test
    void springOnItsOwnRethrowsTheFetchFailure() {
        var filter = new BearerTokenAuthenticationFilter(new ProviderManager(new JwtAuthenticationProvider(decoder)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
        request.addHeader("Authorization", "Bearer " + sign(KEY, person("someone", List.of("vero-users"))));
        AuthenticationServiceException thrown = assertThrows(AuthenticationServiceException.class,
                () -> filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain()));
        assertInstanceOf(SignInKeysUnavailableException.class, thrown.getCause(), "Vero's decoder tells the fetch failure apart");
    }

    @Test
    void veroAnswers503Aut004AndLogsTheKeySetHostAtError() throws Exception {
        String token = sign(KEY, person("someone", List.of("vero-users")));
        Answer answer = answer(mvc, get("/api/v1/session").header("Authorization", "Bearer " + token));
        assertEquals(503, answer.status(), answer.body());
        JsonNode body = json.readTree(answer.body());
        assertEquals("SDV-AUT-004", body.get("code").asText());
        assertEquals("Vero can't check sign-ins right now because the sign-in service isn't answering. Try again in a few "
                + "minutes.", body.get("message").asText());
        List<ILoggingEvent> error = logs.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("code=SDV-AUT-004")).toList();
        assertEquals(1, error.size());
        String line = error.getFirst().getFormattedMessage();
        assertTrue(line.contains("reference=" + body.get("reference").asText()));
        assertTrue(line.contains("127.0.0.1:" + DOWN), line);
        assertFalse(line.contains(token.substring(token.lastIndexOf('.') + 1)), "no token in the log");
    }

    @Test
    void aRequestWithoutATokenStillGets401Aut001() throws Exception {
        Answer answer = answer(mvc, get("/api/v1/session"));
        assertEquals(401, answer.status());
        assertEquals("SDV-AUT-001", json.readTree(answer.body()).get("code").asText());
    }
}
