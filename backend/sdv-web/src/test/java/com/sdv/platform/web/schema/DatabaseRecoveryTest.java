package com.sdv.platform.web.schema;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.ConnectException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0037, item 1: a database that can't be reached at start no longer leaves Vero refusing until a restart. Vero
 * keeps trying with a back-off; meanwhile requests get 503 SDV-SYS-014 with the reference and readiness is not ready;
 * when the database answers, Vero serves without a restart.
 */
class DatabaseRecoveryTest {
    private static final SchemaVersion.Status CURRENT = new SchemaVersion.Status(SchemaVersion.State.CURRENT, List.of(), List.of(), 40);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(DatabaseSchemaCheck.class)).addAppender(logs);
    }

    @AfterEach
    void stopCapturing() {
        ((Logger) LoggerFactory.getLogger(DatabaseSchemaCheck.class)).detachAppender(logs);
    }

    /** A database that refuses connections until {@code up} is set. */
    private static final class Database implements DatabaseSchemaCheck.Reader {
        volatile SchemaVersion.Status up;
        final AtomicInteger tries = new AtomicInteger();

        @Override
        public SchemaVersion.Status read() throws Exception {
            tries.incrementAndGet();
            if (up == null) throw new SQLException("Connection to 127.0.0.1:5433 refused.", "08001", new ConnectException("refused"));
            return up;
        }
    }

    private static MockHttpServletResponse request(DatabaseSchemaCheck check, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new SchemaGateFilter(check).doFilter(new MockHttpServletRequest("GET", path), response, new MockFilterChain());
        return response;
    }

    @Test
    void startWithTheDatabaseDownThenBringItUp_veroRecoversWithoutARestart() throws Exception {
        Database db = new Database();
        DatabaseSchemaCheck check = new DatabaseSchemaCheck(false, db, "localhost:5433", Duration.ofSeconds(2), Duration.ofMinutes(1), null);
        List<Object> readiness = new ArrayList<>();
        DatabaseSchemaHealthIndicator health = new DatabaseSchemaHealthIndicator(check, readiness::add);

        // Down at start: one ERROR line with SDV-SYS-003, the host and port and the SQL state.
        assertFalse(check.current());
        List<ILoggingEvent> errors = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size());
        String line = errors.getFirst().getFormattedMessage();
        assertTrue(line.startsWith("SDV-SYS-003 Vero couldn't reach its database at start."), line);
        assertTrue(line.contains("localhost:5433") && line.contains("SQL state 08001") && line.contains("SDV-SYS-014"), line);

        // Meanwhile: 503 SDV-SYS-014 with the reference. Task 0039 part 2: readiness-only NOT_READY, and no refusal is
        // published at ready (the same rule as when the database goes away after the start).
        MockHttpServletResponse refused = request(check, "/api/v1/business/search");
        assertEquals(503, refused.getStatus());
        assertTrue(refused.getContentAsString().contains("\"code\":\"SDV-SYS-014\""), refused.getContentAsString());
        assertTrue(refused.getContentAsString().contains("\"reference\":\"" + refused.getHeader("X-Correlation-Id") + "\""));
        assertEquals(ReadinessOnly.NOT_READY, health.health().getStatus());
        health.refuseTrafficWhileTheDatabaseDoesNotMatch();
        assertTrue(readiness.isEmpty(), "no REFUSING_TRAFFIC for a database that can't be reached");

        // Still down: it keeps trying.
        assertFalse(check.retryNow());
        assertFalse(check.retryNow());
        assertFalse(check.current());

        // The database comes up: the next try finds it; Vero serves and is ready again, without a restart.
        db.up = CURRENT;
        assertTrue(check.retryNow());
        assertTrue(check.current());
        assertEquals(4, db.tries.get());
        assertEquals(200, request(check, "/api/v1/business/search").getStatus());
        assertEquals(Status.UP, health.health().getStatus());
        assertEquals(ReadinessState.ACCEPTING_TRAFFIC, ((AvailabilityChangeEvent<?>) readiness.getLast()).getState());
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("can be reached again after 4 tries")));
        assertTrue(check.retryNow(), "nothing more to do");
        assertEquals(4, db.tries.get(), "no more tries once it answered");
    }

    @Test
    void theTriesBackOffFromTwoSecondsToOncePerMinute() {
        Database db = new Database();
        DatabaseSchemaCheck check = new DatabaseSchemaCheck(false, db, "db:5432", Duration.ofSeconds(2), Duration.ofMinutes(1), null);
        List<Long> waits = new ArrayList<>();
        waits.add(check.nextRetry().toSeconds());
        for (int i = 0; i < 7; i++) {
            check.retryNow();
            waits.add(check.nextRetry().toSeconds());
        }
        assertEquals(List.of(2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L), waits);
    }

    @Test
    void theRetriesRunOnTheirOwn() throws Exception {
        Database db = new Database();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            DatabaseSchemaCheck check = new DatabaseSchemaCheck(false, db, "db:5432", Duration.ofMillis(20), Duration.ofMillis(80), scheduler);
            CountDownLatch matched = new CountDownLatch(1);
            check.onChange(s -> { if (s.current()) matched.countDown(); });
            while (db.tries.get() < 3) Thread.sleep(10);
            assertFalse(check.current());
            db.up = CURRENT;
            assertTrue(matched.await(5, TimeUnit.SECONDS), "it recovered without anyone calling it");
            assertTrue(check.current());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void aDatabaseThatComesBackOlderIsRefusedAsOlderAndStaysNotReady() throws Exception {
        Database db = new Database();
        DatabaseSchemaCheck check = new DatabaseSchemaCheck(false, db, "db:5432", Duration.ofSeconds(2), Duration.ofMinutes(1), null);
        List<Object> readiness = new ArrayList<>();
        new DatabaseSchemaHealthIndicator(check, readiness::add);
        db.up = new SchemaVersion.Status(SchemaVersion.State.OLDER, List.of(new SchemaVersion.Key("033", "sdv")), List.of(), 39);
        assertTrue(check.retryNow());
        assertFalse(check.current());
        MockHttpServletResponse refused = request(check, "/api/v1/business/search");
        assertTrue(refused.getContentAsString().contains("SDV-SYS-001"));
        assertEquals(ReadinessState.REFUSING_TRAFFIC, ((AvailabilityChangeEvent<?>) readiness.getLast()).getState());
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().startsWith("SDV-SYS-001 ")));
    }

    @Test
    void aDamagedPackageStillStopsTheStart() {
        StartupFailure damaged = new StartupFailure(SdvErrorCode.SYS_004, "The changelog could not be read.");
        assertSame(damaged, assertThrows(StartupFailure.class, () -> new DatabaseSchemaCheck(false, () -> { throw damaged; },
                "db:5432", Duration.ofSeconds(2), Duration.ofMinutes(1), null)));
    }

    @Test
    void theLogNamesOnlyTheHostAndPortOfTheDatabaseUrl() {
        assertEquals("db.example.org:5432", DatabaseSchemaCheck.hostAndPort("jdbc:postgresql://db.example.org:5432/sdv?user=vero&password=secret"));
        assertEquals("db:5432", DatabaseSchemaCheck.hostAndPort("jdbc:postgresql://vero:secret@db:5432/sdv"));
        assertEquals("(no database URL set)", DatabaseSchemaCheck.hostAndPort(null));
    }

    @Test
    void aLocalStartChecksNothing() {
        DatabaseSchemaCheck check = new DatabaseSchemaCheck(true, () -> { throw new SQLException("never read"); }, "x",
                Duration.ofSeconds(2), Duration.ofMinutes(1), null);
        assertTrue(check.current());
        assertTrue(check.localMode());
    }
}
