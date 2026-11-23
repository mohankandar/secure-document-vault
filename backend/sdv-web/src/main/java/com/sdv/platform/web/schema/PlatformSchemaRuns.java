package com.sdv.platform.web.schema;

import com.documentplatform.identity.jdbc.IdentitySchema;
import com.documentplatform.licensing.jdbc.LicensingSchema;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Task 0041: the schema step's platform-core runs (owner answer 1, item 2): identity ({@code db/platform/identity},
 * history {@code idn_schema_history}) and licensing tables only ({@code db/platform/licensing}, history
 * {@code lic_schema_history}). Each is its own Flyway run beside Liquibase, with its own history table, as the
 * platform-core install guides say. Never at startup; only the schema step runs them, as the database owner.
 * <p>
 * Vero's schema already holds Liquibase's tables, so Flyway sees a schema that is not empty. Each run therefore starts
 * its history at the version the platform tables say they are at ({@code idn_schema_version}, {@code lic_schema_version};
 * 0 when there are none yet), never later. A database team that ran the SQL of {@code sql --output} by hand has the
 * version in the version tables, which Vero checks; the next {@code update} with a platform run to do starts the history there.
 */
final class PlatformSchemaRuns {
    private PlatformSchemaRuns() { }

    /** Runs every platform run that is behind; returns the number of migrations applied. */
    static int migrate(Connection connection) throws SQLException {
        int applied = 0;
        for (SchemaVersion.PlatformRun run : SchemaVersion.PLATFORM_RUNS) {
            int have = SchemaVersion.platformVersion(connection, run);
            if (have >= run.version()) continue;
            SingleConnectionDataSource ds = new SingleConnectionDataSource(connection, true);
            Flyway flyway = Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:" + run.location())
                    .table(run.historyTable())
                    .baselineOnMigrate(true)
                    .baselineVersion(String.valueOf(have))
                    .baselineDescription("tables at version " + have + " before this run")
                    .cleanDisabled(true)
                    .load();
            applied += flyway.migrate().migrationsExecuted;
            System.out.println("  " + run.name() + ": tables now at version " + SchemaVersion.platformVersion(connection, run)
                    + " (history table " + run.historyTable() + ").");
        }
        return applied;
    }

    /** Writes the SQL of the platform migrations the database lacks, after Liquibase's. */
    static void writeSql(Connection connection, Writer writer) throws SQLException, IOException {
        for (SchemaVersion.PlatformRun run : SchemaVersion.PLATFORM_RUNS) {
            int have = SchemaVersion.platformVersion(connection, run);
            List<String> scripts = scripts(run);
            for (String name : scripts) {
                int v = Integer.parseInt(name.substring(1, name.indexOf("__")));
                if (v <= have || v > run.version()) continue;
                writer.write("\n-- platform-core " + run.name() + " " + name + " (its own run; history table "
                        + run.historyTable() + ", recorded by the next \"update\").\n");
                writer.write(script(run, name));
                writer.write("\n");
            }
        }
    }

    static List<String> scripts(SchemaVersion.PlatformRun run) {
        return run == SchemaVersion.IDENTITY ? IdentitySchema.scripts() : LicensingSchema.scripts();
    }

    static String script(SchemaVersion.PlatformRun run, String name) {
        return run == SchemaVersion.IDENTITY ? IdentitySchema.script(name) : LicensingSchema.script(name);
    }
}
