package com.sdv.platform.web.schema;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import liquibase.Contexts;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.changelog.filter.ContextChangeSetFilter;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compares the changesets this Vero version ships with the ones recorded in the database, without changing anything.
 * Used by the startup check (as the app's own read/write user) and by the schema step's "status".
 * Liquibase's own status call is not used because it creates its tracking tables when they are missing.
 */
public final class SchemaVersion {
    public static final String MASTER = "db/changelog/db.changelog-master.yaml";
    /** Customer installs always use this context, so "context: local" seed data never runs there. */
    public static final String PRODUCTION_CONTEXT = "production";

    public static final String OLDER_MESSAGE = "The database is older than this Vero version. Ask your database team to run the schema step (install guide, \"Update the database\").";
    public static final String NEWER_MESSAGE = "The database is newer than this Vero version. Start the Vero version the database was updated for, or ask your database team which version that is.";
    /** Task 0037: Vero keeps trying, so no restart is needed once the database is back. */
    public static final String UNREACHABLE_MESSAGE = "Vero can't reach its database. It keeps trying and starts serving as soon as the database answers; check that PostgreSQL is running and that Vero can reach it.";

    public enum State { CURRENT, OLDER, NEWER, UNREACHABLE }

    /** A changeset as Liquibase identifies it, without the file name (which differs between ways of running). */
    public record Key(String id, String author) {
        @Override public String toString() { return id + " (" + author + ")"; }
    }

    /** missing: shipped for production and not applied; unknown: applied but not shipped by this version at all. */
    public record Status(State state, List<Key> missing, List<Key> unknown, int applied) {
        public boolean current() { return state == State.CURRENT; }
        /** False only while the database couldn't be read (task 0037: Vero keeps trying). */
        public boolean reachable() { return state != State.UNREACHABLE; }
        public String message() {
            return switch (state) {
                case CURRENT -> "The database matches this Vero version.";
                case OLDER -> OLDER_MESSAGE;
                case NEWER -> NEWER_MESSAGE;
                case UNREACHABLE -> UNREACHABLE_MESSAGE;
            };
        }
        public static Status unreachable() { return new Status(State.UNREACHABLE, List.of(), List.of(), 0); }
    }

    /** The changesets of this version: every one (any context) and the ones a production install must have. */
    public record Shipped(List<Key> production, Set<Key> all) { }

    /**
     * Task 0041: changesets this version no longer ships but knows. A database that ran one is not "newer" because of
     * it. 002 was the local seed (context local, two fixed demo entities); entities now come from setup and the demo
     * step (owner answer 3). Removing it from the file leaves its rows where it ran; the demo reset clears those.
     */
    public static final Set<Key> RETIRED = Set.of(new Key("002-local-foundation-seed", "sdv"));

    private SchemaVersion() { }

    public static Shipped shipped() {
        try {
            ResourceAccessor accessor = new ClassLoaderResourceAccessor(SchemaVersion.class.getClassLoader());
            DatabaseChangeLog log = ChangeLogParserFactory.getInstance().getParser(MASTER, accessor)
                    .parse(MASTER, new ChangeLogParameters(), accessor);
            ContextChangeSetFilter production = new ContextChangeSetFilter(new Contexts(PRODUCTION_CONTEXT));
            List<Key> required = new ArrayList<>();
            Set<Key> all = new LinkedHashSet<>();
            for (ChangeSet cs : log.getChangeSets()) {
                Key key = new Key(cs.getId(), cs.getAuthor());
                all.add(key);
                if (production.accepts(cs).isAccepted()) required.add(key);
            }
            all.addAll(RETIRED);
            return new Shipped(List.copyOf(required), Set.copyOf(all));
        } catch (Exception e) {
            throw new StartupFailure(SdvErrorCode.SYS_004, "The changelog " + MASTER + " inside the package could not be read: "
                    + e.getClass().getSimpleName() + ".", e);
        }
    }

    /** Pure comparison. A database with changesets this version doesn't know is "newer", even if it also lacks some. */
    public static Status compare(Shipped shipped, List<Key> applied) {
        Set<Key> appliedSet = new LinkedHashSet<>(applied);
        List<Key> missing = shipped.production().stream().filter(k -> !appliedSet.contains(k)).toList();
        List<Key> unknown = appliedSet.stream().filter(k -> !shipped.all().contains(k)).toList();
        State state = !unknown.isEmpty() ? State.NEWER : !missing.isEmpty() ? State.OLDER : State.CURRENT;
        return new Status(state, missing, unknown, appliedSet.size());
    }

    /** Reads Liquibase's record of applied changesets. A database without that table has nothing applied. */
    public static List<Key> applied(Connection connection) throws SQLException {
        try (PreparedStatement exists = connection.prepareStatement("select to_regclass('databasechangelog') is not null");
             ResultSet rs = exists.executeQuery()) {
            rs.next();
            if (!rs.getBoolean(1)) return List.of();
        }
        List<Key> keys = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("select id, author from databasechangelog order by orderexecuted");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) keys.add(new Key(rs.getString(1), rs.getString(2)));
        }
        return keys;
    }

    /**
     * Task 0041: one of platform-core's own Flyway runs that the schema step makes beside Liquibase, each with its own
     * history table (owner answer 1, item 2). Its tables carry their version in {@code versionTable}.
     */
    public record PlatformRun(String name, String location, String historyTable, String versionTable, int version) {
        /** The pseudo changeset key of version {@code v}, for status lines and the startup check. */
        Key key(int v) { return new Key(name + " V" + v, "platform-core"); }
    }

    public static final PlatformRun IDENTITY = new PlatformRun("identity",
            com.documentplatform.identity.jdbc.IdentitySchema.LOCATION,
            com.documentplatform.identity.jdbc.IdentitySchema.HISTORY_TABLE,
            com.documentplatform.identity.jdbc.IdentitySchema.VERSION_TABLE,
            com.documentplatform.identity.jdbc.IdentitySchema.VERSION);
    /** Licensing tables only: Vero does not turn licensing on (owner answers 1 and 15). */
    public static final PlatformRun LICENSING = new PlatformRun("licensing",
            com.documentplatform.licensing.jdbc.LicensingSchema.LOCATION,
            com.documentplatform.licensing.jdbc.LicensingSchema.HISTORY_TABLE,
            com.documentplatform.licensing.jdbc.LicensingSchema.VERSION_TABLE,
            com.documentplatform.licensing.jdbc.LicensingSchema.VERSION);
    /** In the order the schema step runs them, after Liquibase. */
    public static final List<PlatformRun> PLATFORM_RUNS = List.of(IDENTITY, LICENSING);

    /** The version a platform run's tables are at: 0 when its version table is missing or empty. */
    public static int platformVersion(Connection connection, PlatformRun run) throws SQLException {
        try (PreparedStatement exists = connection.prepareStatement("select to_regclass(?) is not null")) {
            exists.setString(1, run.versionTable());
            try (ResultSet rs = exists.executeQuery()) {
                rs.next();
                if (!rs.getBoolean(1)) return 0;
            }
        }
        try (PreparedStatement select = connection.prepareStatement("select coalesce(max(version), 0) from " + run.versionTable());
             ResultSet rs = select.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Liquibase's changesets, then each platform run: the versions missing, or one this Vero version doesn't know. */
    public static Status check(Connection connection) throws SQLException {
        Status liquibase = compare(shipped(), applied(connection));
        List<Key> missing = new ArrayList<>(liquibase.missing());
        List<Key> unknown = new ArrayList<>(liquibase.unknown());
        int applied = liquibase.applied();
        for (PlatformRun run : PLATFORM_RUNS) {
            int have = platformVersion(connection, run);
            if (have > run.version()) unknown.add(run.key(have));
            for (int v = have + 1; v <= run.version(); v++) missing.add(run.key(v));
            applied += Math.min(have, run.version());
        }
        State state = !unknown.isEmpty() ? State.NEWER : !missing.isEmpty() ? State.OLDER : State.CURRENT;
        return new Status(state, List.copyOf(missing), List.copyOf(unknown), applied);
    }
}
