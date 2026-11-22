package com.sdv.platform.web.security;

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
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

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
        ((Logger) LoggerFactory.getLogger(SignInKeysHealthIndicator.class)).addAppender(logs);
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
        ((Logger) LoggerFactory.getLogger(SignInKeysHealthIndicator.class)).detachAppender(logs);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + hub.getAddress().getPort() + path;
    }

    private static SignInKeysHealthIndicator jwt(String jwks) {
        return new SignInKeysHealthIndicator("enterprise-jwt", jwks);
    }

    @Test
    void upWhileTheKeysCanBeFetchedDownWhileTheyCantAndUpAgain() {
        SignInKeysHealthIndicator indicator = jwt(url("/certs"));
        assertEquals(Status.UP, indicator.health().getStatus());

        answer.set(503);
        Health down = indicator.health();
        assertEquals(com.sdv.platform.web.schema.ReadinessOnly.NOT_READY, down.getStatus());
        assertEquals("SDV-AUT-004", down.getDetails().get("code"));
        assertEquals("127.0.0.1:" + hub.getAddress().getPort(), down.getDetails().get("host"));
        assertEquals("HTTP 503", down.getDetails().get("reason"));
        assertEquals(com.sdv.platform.web.schema.ReadinessOnly.NOT_READY, indicator.health().getStatus());

        answer.set(200);
        assertEquals(Status.UP, indicator.health().getStatus());
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
        Health unreachable = jwt("http://127.0.0.1:" + closed + "/certs").health();
        assertEquals(com.sdv.platform.web.schema.ReadinessOnly.NOT_READY, unreachable.getStatus());
        assertEquals("ConnectException", unreachable.getDetails().get("reason"));
        assertEquals("the answer is not a key set", jwt(url("/not-keys")).health().getDetails().get("reason"));
        assertEquals("no key set address is set", jwt("").health().getDetails().get("reason"));
    }

    @Test
    void aHubThatDoesNotAnswerIsDownWithinTheWait() {
        SignInKeysHealthIndicator slow = new SignInKeysHealthIndicator(true, "https://idp.example.test/certs", () -> {
            Thread.sleep(5_000);
            return null;
        }, Duration.ofMillis(200));
        long started = System.nanoTime();
        Health health = slow.health();
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 2_000, "the probe isn't held up");
        assertEquals(com.sdv.platform.web.schema.ReadinessOnly.NOT_READY, health.getStatus());
        assertEquals("no answer within 200 ms", health.getDetails().get("reason"));
        slow.stop();
    }

    @Test
    void withoutEnterpriseJwtNoKeysAreUsedAndItIsUp() {
        for (String mode : new String[]{"local-header", "disabled"}) {
            Health health = new SignInKeysHealthIndicator(mode, "").health();
            assertEquals(Status.UP, health.getStatus(), mode);
        }
    }

    @Test
    void theHostIsShownWithoutThePath() {
        assertEquals("idp.example.test", SignInKeysHealthIndicator.host("https://idp.example.test/realms/x/protocol/openid-connect/certs?k=1"));
        assertEquals("(not set)", SignInKeysHealthIndicator.host(""));
    }
}
