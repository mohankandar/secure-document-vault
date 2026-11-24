package com.sdv.platform.web.schema;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Startup check of the database against this version. It never changes the database.
 *
 * Local mode migrates at startup (Liquibase, application-local.yml), so there is nothing to check. Every other start
 * reads Liquibase's record of applied changesets. When the database is older or newer, Vero keeps running but serves
 * only its health and system information (SchemaGateFilter), and the background jobs stay off, until it is restarted
 * against a matching database.
 *
 * Task 0037: when the database can't be reached at start (SDV-SYS-003), Vero no longer waits for a restart. It keeps
 * trying with a back-off (2 s, doubling, at most every minute); meanwhile requests get 503 SDV-SYS-014. As soon as the
 * database answers the check runs again, and listeners hear the new state: readiness, and the start-up work that
 * needs the database.
 */
@Component
public class DatabaseSchemaCheck {
    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaCheck.class);
    static final Duration FIRST_RETRY = Duration.ofSeconds(2);
    static final Duration MAX_RETRY = Duration.ofMinutes(1);
    private static final Pattern HOST_AND_PORT = Pattern.compile("^jdbc:[a-z]+://([^/?;]+)");

    /** Reads the database's state; an exception means it couldn't be reached. */
    @FunctionalInterface
    interface Reader {
        SchemaVersion.Status read() throws Exception;
    }

    private final boolean local;
    private final Reader reader;
    private final String where;
    private final Duration maxRetry;
    private final ScheduledExecutorService retries;
    private final List<Consumer<SchemaVersion.Status>> listeners = new CopyOnWriteArrayList<>();
    private volatile SchemaVersion.Status status;
    private volatile Duration nextRetry;
    private int attempts;

    @Autowired
    public DatabaseSchemaCheck(DataSource dataSource, Environment environment) {
        // Task 0041 (owner answer 10): the local profile no longer migrates at startup, so it is checked like any start.
        this(false, () -> {
                    SchemaVersion.Status status;
                    try (Connection connection = dataSource.getConnection()) {
                        status = SchemaVersion.check(connection);
                    }
                    if (status.current()) requireIdentityRights(dataSource);
                    return status;
                }, hostAndPort(environment.getProperty("spring.datasource.url")),
                FIRST_RETRY, MAX_RETRY, Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "sdv-database-retry");
                    t.setDaemon(true);
                    return t;
                }));
    }

    /** For tests: a check that already has its result and never retries. */
    DatabaseSchemaCheck(SchemaVersion.Status status) {
        this.local = false;
        this.reader = () -> status;
        this.where = "test";
        this.maxRetry = MAX_RETRY;
        this.retries = null;
        this.status = status;
    }

    /** With {@code retries} null nothing is scheduled; a test calls {@link #retryNow()} itself. */
    DatabaseSchemaCheck(boolean local, Reader reader, String where, Duration firstRetry, Duration maxRetry,
                        ScheduledExecutorService retries) {
        this.local = local;
        this.reader = reader;
        this.where = where;
        this.maxRetry = maxRetry;
        this.retries = retries;
        if (local) {
            this.status = new SchemaVersion.Status(SchemaVersion.State.CURRENT, List.of(), List.of(), 0);
            return;
        }
        this.status = read(true);
        report(status);
        if (!status.reachable()) {
            this.nextRetry = firstRetry;
            schedule();
        }
    }

    /** One more try, after the database couldn't be reached. True when it answered. */
    synchronized boolean retryNow() {
        if (status.reachable()) return true;
        SchemaVersion.Status now = read(false);
        if (!now.reachable()) {
            Duration doubled = nextRetry == null ? FIRST_RETRY : nextRetry.multipliedBy(2);
            nextRetry = doubled.compareTo(maxRetry) > 0 ? maxRetry : doubled;
            log.warn("{} Database still unreachable after {} tries ({}); trying again in {} s.",
                    SdvErrorCode.SYS_003.code(), attempts, where, nextRetry.toSeconds());
            schedule();
            return false;
        }
        status = now;
        log.info("{} The database at {} can be reached again after {} tries; Vero didn't need a restart.",
                SdvErrorCode.SYS_003.code(), where, attempts);
        report(now);
        for (Consumer<SchemaVersion.Status> listener : listeners) {
            try {
                listener.accept(now);
            } catch (RuntimeException ex) {
                log.error("A listener of the database schema check failed: {}", ex.toString(), ex);
            }
        }
        return true;
    }

    private void schedule() {
        if (retries == null || retries.isShutdown()) return;
        retries.schedule(this::retryNow, nextRetry.toMillis(), TimeUnit.MILLISECONDS);
    }

    private SchemaVersion.Status read(boolean first) {
        attempts++;
        try {
            return reader.read();
        } catch (StartupFailure ex) {
            throw ex; // the package itself is damaged (SDV-SYS-004): Vero doesn't start
        } catch (Exception e) {
            if (first) {
                log.error("{} {} {} Requests get 503 {} meanwhile. Cause: database {}: {}{}{}", SdvErrorCode.SYS_003.code(),
                        StartupFailure.sentence(SdvErrorCode.SYS_003), StartupFailure.action(SdvErrorCode.SYS_003),
                        SdvErrorCode.SYS_014.code(), where, e.getClass().getSimpleName(), sqlState(e), causeText(e));
            }
            return SchemaVersion.Status.unreachable();
        }
    }

    private void report(SchemaVersion.Status s) {
        if (local) return;
        if (s.current()) {
            log.info("Database schema check: the database matches this Vero version ({} changesets applied).", s.applied());
        } else if (s.reachable()) {
            SdvErrorCode code = SchemaGateFilter.code(s.state());
            log.error("{} {} Vero is serving only its health and system information until it is restarted against a matching database. {}",
                    code.code(), s.message(), StartupFailure.action(code));
            if (!s.missing().isEmpty()) log.info("Database schema check: not applied: {}", s.missing());
            if (!s.unknown().isEmpty()) log.info("Database schema check: not known to this version: {}", s.unknown());
        }
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) return " (SQL state " + sql.getSQLState() + ")";
        }
        return "";
    }

    /** PostgreSQL's message can name the database user; it never holds the password. */
    private static String causeText(Throwable e) {
        return e.getMessage() == null ? "" : ": " + e.getMessage();
    }

    /** Only the host and port, never the user, the password or other URL parameters. */
    static String hostAndPort(String url) {
        if (url == null) return "(no database URL set)";
        Matcher m = HOST_AND_PORT.matcher(url.trim());
        return m.find() ? m.group(1).replaceAll(".*@", "") : "(database URL not readable)";
    }

    /**
     * Task 0041: platform-core's own check of the identity tables, as its install guide asks: every idn_ table there and
     * the app's database user holding the rights it needs. Missing rights or tables stop the start with the IDN_ code
     * and what to do (SDV-SYS-005); a check the database refused counts as "can't reach the database".
     */
    static void requireIdentityRights(DataSource dataSource) throws java.sql.SQLException {
        try {
            com.documentplatform.identity.jdbc.IdentitySchema.verify(dataSource);
        } catch (com.documentplatform.identity.jdbc.IdentitySchemaException e) {
            if (e.problem() == com.documentplatform.identity.jdbc.IdentitySchemaException.Problem.IDN_CHECK_FAILED) {
                throw new java.sql.SQLException("identity tables could not be checked", e);
            }
            throw new StartupFailure(SdvErrorCode.SYS_005, withCode(e.problem().name(), e.getMessage()), e);
        }
    }

    /**
     * Task 0057: since platform-core 0.6.0 the messages of its start-up refusals start with their code
     * ({@code IDN_PRIVILEGES_MISSING: …}); the code is added only when the message doesn't already start with it.
     */
    static String withCode(String code, String message) {
        String text = message == null ? "" : message;
        return text.startsWith(code) ? text : code + ": " + text;
    }

    /** Hears every later change of state (after the database came back). Called on the retry thread. */
    public void onChange(Consumer<SchemaVersion.Status> listener) {
        listeners.add(listener);
    }

    /** For tests: the wait before the next try while the database can't be reached. */
    Duration nextRetry() { return nextRetry; }

    public boolean current() { return status.current(); }

    public SchemaVersion.Status status() { return status; }

    /** True when this start migrated with Liquibase (local profile) instead of checking. */
    public boolean localMode() { return local; }

    @PreDestroy
    void stop() {
        if (retries != null) retries.shutdownNow();
    }
}
