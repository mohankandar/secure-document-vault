package com.sdv.platform.persistence.fields;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Task 0077: platform-fields commits its own work. Inside one of Vero's transactions it gets that transaction's connection,
 * whose commit, rollback and auto-commit are Vero's: a definition, its copy for the old tables and the audit event are stored
 * together or not at all. (A failure in platform-fields is an exception, so Vero's transaction rolls back.) Outside one it is an
 * ordinary connection.
 */
final class JoinedDataSource extends DelegatingDataSource {
    JoinedDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        DataSource target = obtainTargetDataSource();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return target.getConnection();
        Connection joined = DataSourceUtils.getConnection(target);
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "commit", "rollback", "setAutoCommit" -> {
                    return null;
                }
                case "close" -> {
                    DataSourceUtils.releaseConnection(joined, target);
                    return null;
                }
                default -> {
                    try {
                        return method.invoke(joined, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                }
            }
        });
    }
}
