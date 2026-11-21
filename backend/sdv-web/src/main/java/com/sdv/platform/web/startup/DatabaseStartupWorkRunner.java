package com.sdv.platform.web.startup;

import com.sdv.platform.application.port.DatabaseStartupWork;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.web.schema.DatabaseSchemaCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import org.springframework.dao.DataAccessException;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Task 0037: runs the start-up work that needs the database ({@link DatabaseStartupWork}) once, when the database
 * matches this version: at start, or later when a database that couldn't be reached at start comes back. While the
 * database doesn't match, nothing runs and the start goes on (SDV-SYS-012).
 * <p>
 * Fail closed. At start a failure stops the start, as before, but with SDV-SYS-012 and the exact cause instead of a
 * raw stack trace. After a late recovery the start is long over, so Vero logs the same and stops: it never serves
 * with start-up work undone.
 */
@Component
@Order(0)
public class DatabaseStartupWorkRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DatabaseStartupWorkRunner.class);

    private final DatabaseSchemaCheck check;
    private final List<DatabaseStartupWork> work;
    private final Runnable stopVero;
    private final AtomicBoolean done = new AtomicBoolean(false);

    @Autowired
    public DatabaseStartupWorkRunner(DatabaseSchemaCheck check, List<DatabaseStartupWork> work,
                                     ConfigurableApplicationContext context) {
        this(check, work, () -> {
            Thread exit = new Thread(() -> System.exit(SpringApplication.exit(context, () -> 1)), "sdv-startup-work-failed");
            exit.start();
        });
    }

    DatabaseStartupWorkRunner(DatabaseSchemaCheck check, List<DatabaseStartupWork> work, Runnable stopVero) {
        this.check = check;
        this.work = List.copyOf(work);
        this.stopVero = stopVero;
    }

    @Override
    public void run(ApplicationArguments args) {
        check.onChange(status -> {
            if (status.current()) runLate();
        });
        if (check.current()) {
            runAll();
        } else if (!work.isEmpty()) {
            log.warn("{} Start-up work waits for the database ({}): {}. It runs as soon as the database matches this version.",
                    SdvErrorCode.SYS_012.code(), check.status().state(), names());
        }
    }

    private void runLate() {
        try {
            runAll();
        } catch (StartupFailure ex) {
            log.error("{} {} {} {} Vero stops now.", ex.errorCode().code(), ex.sentence(), ex.detail(), ex.action());
            stopVero.run();
        }
    }

    /** Runs every piece once; throws StartupFailure (SDV-SYS-012 unless the work gave its own code). */
    void runAll() {
        if (!done.compareAndSet(false, true)) return;
        for (DatabaseStartupWork w : work) {
            try {
                w.run();
                log.info("Start-up work done: {}.", w.name());
            } catch (StartupFailure ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new StartupFailure(SdvErrorCode.SYS_012, "The " + w.name() + " failed: " + cause(ex), ex);
            }
        }
    }

    /** A database failure is named by its classes and SQL state only: its message can hold SQL. */
    static String cause(RuntimeException ex) {
        if (ex instanceof DataAccessException) {
            StringBuilder s = new StringBuilder(ex.getClass().getSimpleName());
            for (Throwable t = ex.getCause(); t != null; t = t.getCause()) {
                s.append(" < ").append(t.getClass().getSimpleName());
                if (t instanceof SQLException sql && sql.getSQLState() != null) s.append(" (SQL state ").append(sql.getSQLState()).append(')');
            }
            return s.toString();
        }
        return ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
    }

    private String names() {
        return String.join(", ", work.stream().map(DatabaseStartupWork::name).toList());
    }
}
