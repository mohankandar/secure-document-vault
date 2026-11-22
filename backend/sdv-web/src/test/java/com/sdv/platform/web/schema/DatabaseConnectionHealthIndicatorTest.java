package com.sdv.platform.web.schema;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0037, item 2: readiness pings the database after start, turns not-ready while it is gone, and back. */
class DatabaseConnectionHealthIndicatorTest {

    @Test
    void readinessTurnsNotReadyWhileTheDatabaseIsGoneAndReadyWhenItAnswers() {
        AtomicReference<String> database = new AtomicReference<>("up");
        DatabaseConnectionHealthIndicator ping = new DatabaseConnectionHealthIndicator(() -> {
            if ("down".equals(database.get())) throw new SQLTransientConnectionException("Connection is not available");
            return "up".equals(database.get());
        }, Duration.ofSeconds(2));

        assertEquals(Status.UP, ping.health().getStatus());
        database.set("down");
        var down = ping.health();
        assertEquals(ReadinessOnly.NOT_READY, down.getStatus());
        assertEquals("SQLTransientConnectionException", down.getDetails().get("reason"), "the class only, never the message");
        database.set("invalid");
        assertEquals(ReadinessOnly.NOT_READY, ping.health().getStatus(), "a connection that isn't valid");
        database.set("up");
        assertEquals(Status.UP, ping.health().getStatus(), "back without a restart");
    }

    @Test
    void aDatabaseThatDoesntAnswerNeverHoldsTheProbeAndPingsDontPileUp() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger pings = new AtomicInteger();
        DatabaseConnectionHealthIndicator ping = new DatabaseConnectionHealthIndicator(() -> {
            pings.incrementAndGet();
            release.await();
            return true;
        }, Duration.ofMillis(100));

        long started = System.nanoTime();
        var health = ping.health();
        assertEquals(ReadinessOnly.NOT_READY, health.getStatus());
        assertEquals("no answer within 100 ms", health.getDetails().get("reason"));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1500);
        assertEquals(ReadinessOnly.NOT_READY, ping.health().getStatus());
        assertEquals(1, pings.get(), "the second probe waits for the same ping");
        release.countDown();
        Thread.sleep(50);
        assertEquals(Status.UP, ping.health().getStatus());
        ping.stop();
    }
}
