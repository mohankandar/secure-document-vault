package com.sdv.platform.web.foundation;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0060 (plan 0059, 1.6): the busy answer reads HikariCP's own exception: a full pool is
 * {@link SQLTransientConnectionException} without a cause; a database that can't be reached gives one with a cause.
 * Proved on a real pool, so a HikariCP that says it differently is seen.
 */
class FullPoolBaselineTest {
    private static HikariDataSource pool(boolean reachable) {
        HikariConfig config = new HikariConfig();
        config.setDataSource(new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                if (!reachable) throw new SQLException("Connection refused", "08001");
                Connection c = mock(Connection.class);
                when(c.isValid(anyInt())).thenReturn(true);
                return c;
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        });
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setInitializationFailTimeout(-1);
        config.setConnectionTimeout(1000);
        config.setValidationTimeout(250);
        return new HikariDataSource(config);
    }

    @Test
    void aFullPoolIsBusyAndAMissingDatabaseIsNot() throws Exception {
        try (HikariDataSource full = pool(true); Connection taken = full.getConnection()) {
            SQLException ex = assertThrows(SQLTransientConnectionException.class, full::getConnection);
            assertNull(ex.getCause());
            assertTrue(ErrorReplies.isBusy(ex));
        }
        try (HikariDataSource away = pool(false)) {
            SQLException ex = assertThrows(SQLTransientConnectionException.class, away::getConnection);
            assertNotNull(ex.getCause());
            assertFalse(ErrorReplies.isBusy(ex));
        }
    }
}
