package com.sdv.platform.web.schema;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
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
        }, WAIT);
    }

    DatabaseConnectionHealthIndicator(Callable<Boolean> ping, Duration wait) {
        this.ping = ping;
        this.wait = wait;
    }

    @Override
    public Health health() {
        Future<Boolean> f = ping();
        try {
            boolean ok = f.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            return ok ? Health.up().build() : Health.down().withDetail("reason", "the database didn't answer the ping").build();
        } catch (TimeoutException ex) {
            return Health.down().withDetail("reason", "no answer within " + wait.toMillis() + " ms").build();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            return Health.down().withDetail("reason", cause.getClass().getSimpleName()).build();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("reason", "interrupted").build();
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
