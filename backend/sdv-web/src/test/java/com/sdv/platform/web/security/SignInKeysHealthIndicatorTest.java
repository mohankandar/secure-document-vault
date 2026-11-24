package com.sdv.platform.web.security;

import com.documentplatform.monitoring.CheckResult;
import com.documentplatform.monitoring.CheckRunner;
import com.documentplatform.monitoring.CheckState;
import com.sdv.platform.web.schema.ReadinessOnly;
import java.time.Instant;
import java.util.List;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0038 (SDV-AUT-004): readiness reports whether the sign-in hub's keys can be fetched, against a real loopback
 * key set: up, then unreachable (NOT_READY, task 0039, with the code and the host, one WARN), then back (UP, one INFO).
 */
class SignInKeysHealthIndicatorTest {
    private ListAppender<ILoggingEvent> logs;
    private HttpServer hub;
    private final AtomicInteger answer = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws Exception {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(SignInKeysCheck.class)).addAppender(logs);
        byte[] keys = new JWKSet(new RSAKeyGenerator(2048).keyID("k1").generate().toPublicJWK()).toString()
                .getBytes(StandardCharsets.UTF_8);
        hub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hub.createContext("/certs", exchange -> {
            int status = answer.get();
            byte[] body = status == 200 ? keys : "{\"error\":\"down\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        hub.createContext("/not-keys", exchange -> {
            byte[] body = "<html>login</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        hub.start();
    }

    @AfterEach
    void tearDown() {
        hub.stop(0);
        ((Logger) LoggerFactory.getLogger(SignInKeysCheck.class)).detachAppender(logs);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + hub.getAddress().getPort() + path;
    }

    private static SignInKeysCheck jwt(String jwks) {
        return new SignInKeysCheck("enterprise-jwt", jwks);
    }

    /** Readiness after one run of the check, as the list runs it. */
    private static Health ready(SignInKeysCheck check) throws Exception {
        CheckResult result = CheckResult.of(check, check.check(), Instant.now(), Duration.ZERO);
        return new SignInKeysHealthIndicator(check.used(), () -> List.of(result)).health();
    }

    @Test
    void upWhileTheKeysCanBeFetchedDownWhileTheyCantAndUpAgain() throws Exception {
        SignInKeysCheck check = jwt(url("/certs"));
        assertEquals(Status.UP, ready(check).getStatus());

        answer.set(503);
        Health down = ready(check);
        assertEquals(ReadinessOnly.NOT_READY, down.getStatus());
        assertEquals("SDV-AUT-004", down.getDetails().get("code"));
        String reason = (String) down.getDetails().get("reason");
        assertTrue(reason.contains("127.0.0.1:" + hub.getAddress().getPort()) && reason.contains("HTTP 503"), reason);
        assertEquals(ReadinessOnly.NOT_READY, ready(check).getStatus());

        answer.set(200);
        assertEquals(Status.UP, ready(check).getStatus());
        assertEquals(1, logs.list.stream().filter(e -> e.getLevel() == Level.WARN).count(), "one WARN for the outage");
        assertTrue(logs.list.stream().filter(e -> e.getLevel() == Level.WARN).findFirst().orElseThrow()
                .getFormattedMessage().startsWith("code=SDV-AUT-004 "));
        assertEquals(1, logs.list.stream().filter(e -> e.getLevel() == Level.INFO).count(), "one INFO when it is back");
    }

    @Test
    void anUnreachableHubOrAnAnswerThatIsNotAKeySetIsDown() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        Health unreachable = ready(jwt("http://127.0.0.1:" + closed + "/certs"));
        assertEquals(ReadinessOnly.NOT_READY, unreachable.getStatus());
        assertTrue(unreachable.getDetails().get("reason").toString().contains("ConnectException"));
        assertEquals("the answer is not a key set", jwt(url("/not-keys")).check().details().get("reason"));
        assertEquals("no key set address is set", jwt("").check().details().get("reason"));
    }

    /** Task 0074: the list's runner stops a check that hangs; readiness reads the last result and never waits. */
    @Test
    void aHubThatDoesNotAnswerIsDownAndNotCheckedYetIsNotReady() throws Exception {
        SignInKeysCheck slow = new SignInKeysCheck(true, "https://idp.example.test/certs", () -> {
            Thread.sleep(60_000);
            return null;
        });
        try (CheckRunner runner = new CheckRunner()) {
            CheckResult result = runner.run(slow);
            assertEquals(CheckState.DOWN, result.state());
            assertEquals(ReadinessOnly.NOT_READY, new SignInKeysHealthIndicator(true, () -> List.of(result)).health().getStatus());
        }
        assertEquals(ReadinessOnly.NOT_READY, new SignInKeysHealthIndicator(true, List::of).health().getStatus());
    }

    @Test
    void withoutEnterpriseJwtNoKeysAreUsedAndItIsUp() throws Exception {
        for (String mode : new String[]{"local-header", "disabled"}) {
            assertEquals(Status.UP, ready(new SignInKeysCheck(mode, "")).getStatus(), mode);
        }
    }

    @Test
    void theHostIsShownWithoutThePath() {
        assertEquals("idp.example.test", SignInKeysCheck.host("https://idp.example.test/realms/x/protocol/openid-connect/certs?k=1"));
        assertEquals("(not set)", SignInKeysCheck.host(""));
    }
}
