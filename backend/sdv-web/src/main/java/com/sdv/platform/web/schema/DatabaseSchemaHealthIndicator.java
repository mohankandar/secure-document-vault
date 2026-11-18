package com.sdv.platform.web.schema;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Makes /actuator/health DOWN (503) while the database doesn't match this version. */
@Component("databaseSchema")
public class DatabaseSchemaHealthIndicator implements HealthIndicator {
    private final DatabaseSchemaCheck check;

    public DatabaseSchemaHealthIndicator(DatabaseSchemaCheck check) {
        this.check = check;
    }

    @Override
    public Health health() {
        SchemaVersion.Status status = check.status();
        Health.Builder builder = status.current() ? Health.up() : Health.down();
        return builder.withDetail("state", status.state().name()).withDetail("message", status.message()).build();
    }
}
