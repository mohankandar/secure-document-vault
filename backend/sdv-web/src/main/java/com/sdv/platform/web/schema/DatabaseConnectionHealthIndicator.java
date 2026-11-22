package com.sdv.platform.web.schema;

import com.sdv.platform.web.foundation.ErrorReplies;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.BooleanSupplier;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Task 0037: readiness watches the database after start, with a cheap ping (a connection from the pool and
 * {@code isValid}). While it fails, /actuator/health/readiness is 503 DOWN, so a load balancer or Platform Ops sees
 * it; liveness stays UP. It turns ready again on its own when the database answers.
 * <p>
 * The ping never makes a probe wait long: it runs on its own thread and an answer that takes more than 2 s counts as
 * down. Only one ping runs at a time; probes that come meanwhile wait for the same one.
 */
@Component("databaseConnection")
public class DatabaseConnectionHealthIndicator implements HealthIndicator {
    static final Duration WAIT = Duration.ofSeconds(2);

    private final Callable<Boolean> ping;
    private final Duration wait;
    /** Task 0040 part 2: every pool connection is in use and someone waits for one (the pool is full). */
    private final BooleanSupplier poolFull;
    private final ExecutorService pinger = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sdv-database-ping");
        t.setDaemon(true);
        return t;
    });
    private Future<Boolean> inFlight;

    @Autowired
    public DatabaseConnectionHealthIndicator(DataSource dataSource) {
        this(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return connection.isValid((int) WAIT.toSeconds());
            }
        }, WAIT, () -> poolFull(dataSource));
    }

    DatabaseConnectionHealthIndicator(Callable<Boolean> ping, Duration wait) {
        this(ping, wait, () -> false);
    }

    DatabaseConnectionHealthIndicator(Callable<Boolean> ping, Duration wait, BooleanSupplier poolFull) {
        this.ping = ping;
        this.wait = wait;
        this.poolFull = poolFull;
    }

    /**
     * Vero's HikariCP pool is full: every connection it may open is in use, and at least one caller (the ping, at
     * least) waits for one. A database that can't be reached doesn't look like this: its connections fail and leave
     * the pool. Not a Hikari pool, or not started yet: not full.
     */
    static boolean poolFull(DataSource dataSource) {
        try {
            if (!dataSource.isWrapperFor(HikariDataSource.class)) return false;
            HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            return pool != null && pool.getActiveConnections() >= hikari.getMaximumPoolSize()
                    && pool.getThreadsAwaitingConnection() > 0;
        } catch (SQLException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * Task 0040 part 2 (master section item 2): BUSY when the ping found no free connection (the pool is full, or
     * PostgreSQL has no connection left, SQL state 53300); NOT_READY for every other failure, as before.
     */
    @Override
    public Health health() {
        Future<Boolean> f = ping();
        try {
            boolean ok = f.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            return ok ? Health.up().build() : Health.status(ReadinessOnly.NOT_READY).withDetail("reason", "the database didn't answer the ping").build();
        } catch (TimeoutException ex) {
            if (poolFull.getAsBoolean()) {
                return Health.status(ReadinessOnly.BUSY).withDetail("reason", "no free connection within " + wait.toMillis() + " ms").build();
            }
            return Health.status(ReadinessOnly.NOT_READY).withDetail("reason", "no answer within " + wait.toMillis() + " ms").build();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (ErrorReplies.isBusy(cause)) {
                return Health.status(ReadinessOnly.BUSY).withDetail("reason", cause.getClass().getSimpleName()).build();
            }
            return Health.status(ReadinessOnly.NOT_READY).withDetail("reason", cause.getClass().getSimpleName()).build();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Health.status(ReadinessOnly.NOT_READY).withDetail("reason", "interrupted").build();
        }
    }

    private synchronized Future<Boolean> ping() {
        if (inFlight == null || inFlight.isDone()) inFlight = pinger.submit(ping);
        return inFlight;
    }

    @PreDestroy
    void stop() {
        pinger.shutdownNow();
    }
}
