package com.sdv.platform.web.schema;

import java.time.Duration;
import java.util.List;

/** For tests in other packages: a schema check whose database is unreachable until a test brings it back. */
public final class DatabaseSchemaCheckAccess {
    private final DatabaseSchemaCheck check;
    private volatile SchemaVersion.Status up;

    private DatabaseSchemaCheckAccess() {
        this.check = new DatabaseSchemaCheck(false, () -> {
            if (up == null) throw new java.sql.SQLException("refused", "08001");
            return up;
        }, "test:5432", Duration.ofSeconds(2), Duration.ofMinutes(1), null);
    }

    public static DatabaseSchemaCheckAccess unreachable() {
        return new DatabaseSchemaCheckAccess();
    }

    public static DatabaseSchemaCheck current() {
        return new DatabaseSchemaCheck(new SchemaVersion.Status(SchemaVersion.State.CURRENT, List.of(), List.of(), 1));
    }

    public DatabaseSchemaCheck check() {
        return check;
    }

    /** The database answers; the next try finds it in {@code state}. */
    public void comesBack(SchemaVersion.State state) {
        up = new SchemaVersion.Status(state, List.of(), List.of(), 1);
        check.retryNow();
    }
}
