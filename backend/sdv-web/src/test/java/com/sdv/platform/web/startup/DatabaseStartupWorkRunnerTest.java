package com.sdv.platform.web.startup;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DatabaseStartupWork;
import com.sdv.platform.application.port.RoleGovernancePort;
import com.sdv.platform.application.roles.RoleMappingWatchService;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.security.auth.enterprise.RestrictedStewardMappingWatcher;
import com.sdv.platform.web.schema.DatabaseSchemaCheckAccess;
import com.sdv.platform.web.schema.SchemaVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Task 0037, item 3 (SDV-SYS-012): the start-up work that needs the database, with RestrictedStewardMappingWatcher
 * among it, waits for the schema check like the rest of Vero.
 */
class DatabaseStartupWorkRunnerTest {

    /** The watcher over a sign-in group store whose database is missing, until {@code up}. */
    private static final class Watcher {
        final RoleGovernancePort store = mock(RoleGovernancePort.class);
        final AuditEventPort audit = mock(AuditEventPort.class);
        final RestrictedStewardMappingWatcher watcher =
                new RestrictedStewardMappingWatcher(new RoleMappingWatchService(store, audit), "vero-restricted");

        Watcher() {
            when(store.findRecordedGroups(any())).thenThrow(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                    new SQLException("Connection to 127.0.0.1:5433 refused.", "08001")));
        }

        void up() {
            reset(store);
            when(store.findRecordedGroups(any())).thenReturn(Optional.empty());
            when(store.tenantIds()).thenReturn(List.of());
        }
    }

    @Test
    void theWatcherOnItsOwnStopsTheStartWithARawDatabaseError_theCatalogsLikely() {
        // What task 0034 called "likely": run as an ApplicationRunner, the watcher reached the database during the
        // start and its exception (a raw Spring JDBC one) stopped it. Confirmed live with the jar built before this task.
        Watcher w = new Watcher();
        assertThrows(CannotGetJdbcConnectionException.class, w.watcher::run);
    }

    @Test
    void withAMissingSchemaTheWatcherWaitsAndTheStartGoesOn_thenItRunsWhenTheDatabaseIsBack() throws Exception {
        Watcher w = new Watcher();
        DatabaseSchemaCheckAccess db = DatabaseSchemaCheckAccess.unreachable();
        List<Runnable> stops = new ArrayList<>();
        DatabaseStartupWorkRunner runner = new DatabaseStartupWorkRunner(db.check(), List.of(w.watcher), () -> stops.add(() -> { }));

        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()), "the start goes on");
        verify(w.store, never()).findRecordedGroups(any());

        w.up();
        db.comesBack(SchemaVersion.State.CURRENT);
        verify(w.store, times(1)).findRecordedGroups("RESTRICTED_STEWARD");
        assertTrue(stops.isEmpty());
        db.comesBack(SchemaVersion.State.CURRENT);
        verify(w.store, times(1)).findRecordedGroups("RESTRICTED_STEWARD");
    }

    @Test
    void aDatabaseThatComesBackMismatchedStillRunsNothing() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        DatabaseSchemaCheckAccess db = DatabaseSchemaCheckAccess.unreachable();
        new DatabaseStartupWorkRunner(db.check(), List.of(work("count", runs::incrementAndGet)), () -> { })
                .run(new DefaultApplicationArguments());
        db.comesBack(SchemaVersion.State.OLDER);
        assertEquals(0, runs.get());
    }

    @Test
    void withAMatchingDatabaseTheWorkRunsAtStartOnce() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        DatabaseStartupWorkRunner runner = new DatabaseStartupWorkRunner(DatabaseSchemaCheckAccess.current(),
                List.of(work("count", runs::incrementAndGet)), () -> { });
        runner.run(new DefaultApplicationArguments());
        runner.runAll();
        assertEquals(1, runs.get());
    }

    @Test
    void failedWorkAtStartStopsTheStartWithSys012AndNoSql() {
        RuntimeException sqlFailure = new BadSqlGrammarException("watch", "select secret_column from role_group_mappings where x = 'value'",
                new SQLException("relation does not exist", "42P01"));
        DatabaseStartupWorkRunner runner = new DatabaseStartupWorkRunner(DatabaseSchemaCheckAccess.current(),
                List.of(work("Restricted Steward sign-in group check", () -> { throw sqlFailure; })), () -> { });
        StartupFailure f = assertThrows(StartupFailure.class, () -> runner.run(new DefaultApplicationArguments()));
        assertEquals(SdvErrorCode.SYS_012, f.errorCode());
        assertTrue(f.detail().startsWith("The Restricted Steward sign-in group check failed: BadSqlGrammarException"), f.detail());
        assertTrue(f.detail().contains("SQL state 42P01"), f.detail());
        assertFalse(f.getMessage().contains("secret_column") || f.getMessage().contains("value"), "never the SQL");
    }

    @Test
    void workThatGivesItsOwnCodeKeepsIt() {
        DatabaseStartupWorkRunner runner = new DatabaseStartupWorkRunner(DatabaseSchemaCheckAccess.current(),
                List.of(work("bootstrap", () -> { throw new StartupFailure(SdvErrorCode.SYS_005, "bad tenant"); })), () -> { });
        assertEquals(SdvErrorCode.SYS_005, assertThrows(StartupFailure.class, () -> runner.run(new DefaultApplicationArguments())).errorCode());
    }

    @Test
    void failedWorkAfterALateRecoveryStopsVero() throws Exception {
        AtomicInteger stops = new AtomicInteger();
        DatabaseSchemaCheckAccess db = DatabaseSchemaCheckAccess.unreachable();
        new DatabaseStartupWorkRunner(db.check(), List.of(work("watch", () -> { throw new IllegalStateException("boom"); })),
                stops::incrementAndGet).run(new DefaultApplicationArguments());
        db.comesBack(SchemaVersion.State.CURRENT);
        assertEquals(1, stops.get(), "fail closed: Vero never serves with start-up work undone");
    }

    private static DatabaseStartupWork work(String name, Runnable body) {
        return new DatabaseStartupWork() {
            @Override public String name() { return name; }
            @Override public void run() { body.run(); }
        };
    }
}
