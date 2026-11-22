package com.sdv.platform.web.schema;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0037, item 2: readiness pings the database after start, turns not-ready while it is gone, and back. */
class DatabaseConnectionHealthIndicatorTest {

    @Test
    void readinessTurnsNotReadyWhileTheDatabaseIsGoneAndReadyWhenItAnswers() {
        AtomicReference<String> database = new AtomicReference<>("up");
        DatabaseConnectionHealthIndicator ping = new DatabaseConnectionHealthIndicator(() -> {
            // Task 0040 part 2: as Hikari throws it for a database that can't be reached, with the connection failure
            // as its cause (without a cause it means the pool is full: busy, below).
            if ("down".equals(database.get())) throw new SQLTransientConnectionException("Connection is not available",
                    "08001", new SQLException("Connection refused", "08001"));
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

    /**
     * Task 0040 part 2 (master section item 2): BUSY, not NOT_READY, when the ping finds no free connection: the pool is
     * full while the ping waits, or PostgreSQL has none left (53300), or the pool's own timeout (no cause).
     */
    @Test
    void noFreeConnectionIsBusyAndEveryOtherFailureStaysNotReady() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean full = new AtomicBoolean(true);
        DatabaseConnectionHealthIndicator waiting = new DatabaseConnectionHealthIndicator(() -> {
            release.await();
            return true;
        }, Duration.ofMillis(100), full::get);
        assertEquals(ReadinessOnly.BUSY, waiting.health().getStatus(), "pool full while the ping waits");
        full.set(false);
        assertEquals(ReadinessOnly.NOT_READY, waiting.health().getStatus(), "no answer, pool not full: unavailable");
        release.countDown();
        waiting.stop();

        AtomicReference<Exception> failure = new AtomicReference<>();
        DatabaseConnectionHealthIndicator failing = new DatabaseConnectionHealthIndicator(() -> { throw failure.get(); },
                Duration.ofSeconds(2), () -> false);
        failure.set(new SQLException("sorry, too many clients already", "53300"));
        assertEquals(ReadinessOnly.BUSY, failing.health().getStatus(), "PostgreSQL has no connection left");
        failure.set(new SQLTransientConnectionException("pool - Connection is not available, request timed out"));
        assertEquals(ReadinessOnly.BUSY, failing.health().getStatus(), "the pool's own timeout");
        failure.set(new SQLException("disk full", "53100"));
        assertEquals(ReadinessOnly.NOT_READY, failing.health().getStatus());
        failure.set(new SQLException("refused", "08001"));
        assertEquals(ReadinessOnly.NOT_READY, failing.health().getStatus());
        failing.stop();
    }

    /** A real HikariCP pool: full only while every connection is in use and someone waits. */
    @Test
    void poolFullReadsVerosHikariPool() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setDataSource(standIn());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(1000);
        config.setInitializationFailTimeout(-1);
        config.setPoolName("readiness-busy-test");
        try (HikariDataSource pool = new HikariDataSource(config)) {
            assertFalse(DatabaseConnectionHealthIndicator.poolFull(pool), "nothing in use");
            try (Connection held = pool.getConnection()) {
                assertNotNull(held);
                assertFalse(DatabaseConnectionHealthIndicator.poolFull(pool), "all in use, nobody waiting");
                var waiter = java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try (Connection c = pool.getConnection()) { assertNotNull(c); } catch (SQLException ignored) { }
                });
                boolean seen = false;
                for (int i = 0; i < 50 && !seen; i++) { seen = DatabaseConnectionHealthIndicator.poolFull(pool); Thread.sleep(10); }
                assertTrue(seen, "all in use and one waiting: full");
                held.close();
                waiter.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertFalse(DatabaseConnectionHealthIndicator.poolFull(pool));
        }
        assertFalse(DatabaseConnectionHealthIndicator.poolFull(standIn()), "not a Hikari pool");
    }

    /** A stand-in database whose connections answer every question harmlessly. */
    private static DataSource standIn() {
        return (DataSource) Proxy.newProxyInstance(DatabaseConnectionHealthIndicatorTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection();
                    case "isWrapperFor" -> false;
                    default -> defaultFor(method.getReturnType());
                });
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(DatabaseConnectionHealthIndicatorTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isValid", "getAutoCommit" -> true;
                    case "isClosed", "isWrapperFor" -> false;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "stand-in connection";
                    default -> defaultFor(method.getReturnType());
                });
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
