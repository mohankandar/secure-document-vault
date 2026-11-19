package com.sdv.platform.web.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/**
 * Startup check of the database against this version. It never changes the database.
 *
 * Local mode migrates at startup (Liquibase, application-local.yml), so there is nothing to check. Every other start
 * reads Liquibase's record of applied changesets. When the database is older, newer or can't be read, Vero keeps
 * running but serves only its health and system information (SchemaGateFilter), and the background jobs stay off.
 * The check runs once, while the application context starts and before the web server takes requests.
 */
@Component
public class DatabaseSchemaCheck {
    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaCheck.class);
    private final SchemaVersion.Status status;
    private final boolean local;

    @Autowired
    public DatabaseSchemaCheck(DataSource dataSource, Environment environment) {
        this.local = environment.acceptsProfiles(Profiles.of("local"));
        this.status = local ? new SchemaVersion.Status(SchemaVersion.State.CURRENT, List.of(), List.of(), 0) : read(dataSource);
        if (!local) {
            if (status.current()) {
                log.info("Database schema check: the database matches this Vero version ({} changesets applied).", status.applied());
            } else {
                log.error("{} Vero is serving only its health and system information until it is restarted against a matching database.", status.message());
                if (!status.missing().isEmpty()) log.info("Database schema check: not applied: {}", status.missing());
                if (!status.unknown().isEmpty()) log.info("Database schema check: not known to this version: {}", status.unknown());
            }
        }
    }

    /** For tests: a check that already has its result. */
    DatabaseSchemaCheck(SchemaVersion.Status status) {
        this.status = status;
        this.local = false;
    }

    private static SchemaVersion.Status read(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return SchemaVersion.check(connection);
        } catch (Exception e) {
            log.warn("Database schema check could not read the database: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            return SchemaVersion.Status.unreachable();
        }
    }

    public boolean current() { return status.current(); }

    public SchemaVersion.Status status() { return status; }

    /** True when this start migrated with Liquibase (local profile) instead of checking. */
    public boolean localMode() { return local; }
}
