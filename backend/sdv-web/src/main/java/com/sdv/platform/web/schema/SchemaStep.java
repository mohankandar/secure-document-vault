package com.sdv.platform.web.schema;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import java.io.Console;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Properties;
import java.util.function.Function;

/**
 * The schema step, run by the customer's database team with their own (owner) credentials. Spring is not started.
 *
 * <pre>
 *   status               lists the changesets that would run (changes nothing)
 *   sql --output FILE    writes the SQL that update would run, for teams that review and run it themselves
 *   update               applies the changesets
 * </pre>
 *
 * The connection comes from SDV_SCHEMA_DB_URL and SDV_SCHEMA_DB_USERNAME (or --url / --username); the password from
 * SDV_SCHEMA_DB_PASSWORD or, when that is not set, a prompt. Vero never stores them. The context is always
 * "production" and cannot be changed, so the local seed data can never run through this step.
 * Exit codes: 0 done, 2 wrong usage, 3 database newer than this version, 1 any other failure.
 */
public final class SchemaStep {
    static final String URL_ENV = "SDV_SCHEMA_DB_URL";
    static final String USER_ENV = "SDV_SCHEMA_DB_USERNAME";
    static final String PASSWORD_ENV = "SDV_SCHEMA_DB_PASSWORD";

    private SchemaStep() { }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv()::get, System.console()));
    }

    /** Parsed command line; the password is never taken from the command line, where other users could see it. */
    record Command(String action, String url, String username, Path output) { }

    static Command parse(String[] args, Function<String, String> env) {
        if (args.length == 0) throw new IllegalArgumentException("Say what to do: status, sql --output FILE, or update.");
        String action = args[0];
        if (!Arrays.asList("status", "sql", "update").contains(action)) throw new IllegalArgumentException("Unknown step '" + action + "'. Use status, sql --output FILE, or update.");
        String url = env.apply(URL_ENV), username = env.apply(USER_ENV);
        Path output = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value after " + a + ".");
            String v = args[++i];
            switch (a) {
                case "--url" -> url = v;
                case "--username" -> username = v;
                case "--output" -> output = Path.of(v);
                case "--password" -> throw new IllegalArgumentException("Don't put the password on the command line. Set " + PASSWORD_ENV + " or type it when asked.");
                case "--contexts", "--context" -> throw new IllegalArgumentException("The schema step always uses the production context; it can't be changed.");
                default -> throw new IllegalArgumentException("Unknown option " + a + ".");
            }
        }
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Give the database address: set " + URL_ENV + " or use --url (jdbc:postgresql://host:5432/database).");
        if (!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("Vero supports PostgreSQL only; the address must start with jdbc:postgresql:");
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Give the database owner user: set " + USER_ENV + " or use --username.");
        if ("sql".equals(action) && output == null) throw new IllegalArgumentException("sql needs --output FILE.");
        if (!"sql".equals(action) && output != null) throw new IllegalArgumentException("--output is only for sql.");
        return new Command(action, url.trim(), username.trim(), output);
    }

    static int run(String[] args, Function<String, String> env, Console console) {
        System.setProperty("liquibase.analytics.enabled", "false");
        Command command;
        try {
            command = parse(args, env);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }
        if (command.output() != null && Files.exists(command.output())) {
            System.err.println(command.output() + " already exists. Choose a new file; the schema step doesn't overwrite.");
            return 2;
        }
        char[] password = password(env, console, command);
        if (password == null) {
            System.err.println("No password: set " + PASSWORD_ENV + " or run the step in a window where it can ask.");
            return 2;
        }
        Properties props = new Properties();
        props.setProperty("user", command.username());
        props.setProperty("password", new String(password));
        Arrays.fill(password, '\0');
        try (Connection connection = DriverManager.getConnection(command.url(), props)) {
            props.remove("password");
            SchemaVersion.Status before = SchemaVersion.check(connection);
            if (before.state() == SchemaVersion.State.NEWER) {
                System.out.println(SchemaVersion.NEWER_MESSAGE);
                System.out.println("Not known to this version: " + before.unknown());
                return 3;
            }
            return switch (command.action()) {
                case "status" -> status(before);
                case "sql" -> sql(connection, before, command.output());
                default -> update(connection, before);
            };
        } catch (Exception e) {
            System.err.println("The schema step failed: " + e.getMessage());
            return 1;
        }
    }

    private static char[] password(Function<String, String> env, Console console, Command command) {
        String fromEnv = env.apply(PASSWORD_ENV);
        if (fromEnv != null && !fromEnv.isEmpty()) return fromEnv.toCharArray();
        if (console == null) return null;
        return console.readPassword("Password for %s: ", command.username());
    }

    private static int status(SchemaVersion.Status status) {
        if (status.current()) {
            System.out.println("Up to date: " + status.applied() + " changesets applied, none to run.");
        } else {
            System.out.println(status.missing().size() + " changesets would run:");
            status.missing().forEach(k -> System.out.println("  " + k));
        }
        return 0;
    }

    private static int sql(Connection connection, SchemaVersion.Status before, Path output) throws Exception {
        try (Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write("-- Vero schema step: SQL for " + before.missing().size() + " changesets (context production).\n");
            writer.write("-- Run it as the database owner, without wrapping the whole file in one transaction:\n");
            writer.write("-- CREATE INDEX CONCURRENTLY (changeset 018) can't run inside a transaction.\n");
            liquibase(connection).update(new Contexts(SchemaVersion.PRODUCTION_CONTEXT), new LabelExpression(), writer);
        }
        System.out.println("Wrote the SQL for " + before.missing().size() + " changesets to " + output + ". Nothing was changed in the database.");
        return 0;
    }

    private static int update(Connection connection, SchemaVersion.Status before) throws Exception {
        if (before.current()) {
            System.out.println("Up to date: " + before.applied() + " changesets applied, none to run.");
            return 0;
        }
        System.out.println("Applying " + before.missing().size() + " changesets...");
        liquibase(connection).update(new Contexts(SchemaVersion.PRODUCTION_CONTEXT), new LabelExpression());
        SchemaVersion.Status after = SchemaVersion.check(connection);
        if (!after.current()) {
            System.err.println("After the update the database still doesn't match: " + after.message());
            return 1;
        }
        System.out.println("Done: " + after.applied() + " changesets applied. You can start Vero now.");
        return 0;
    }

    @SuppressWarnings("deprecation") // The Liquibase facade is the simplest API that takes a Writer for the SQL output.
    private static Liquibase liquibase(Connection connection) throws Exception {
        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        return new Liquibase(SchemaVersion.MASTER, new ClassLoaderResourceAccessor(SchemaStep.class.getClassLoader()), database);
    }
}
