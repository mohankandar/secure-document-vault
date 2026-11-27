package com.sdv.platform.web.schema;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaVersionTest {
    private static final SchemaVersion.Shipped SHIPPED = SchemaVersion.shipped();
    private static final SchemaVersion.Key SEED = new SchemaVersion.Key("002-local-foundation-seed", "sdv");

    /** Task 0057: platform-core 0.7.0's identity tables are version 5, licensing 2 (task 0067). */
    @Test
    void theIdentityRunIsAtVersionFiveAndLicensingAtTwo() {
        assertEquals(5, SchemaVersion.IDENTITY.version());
        assertEquals(2, SchemaVersion.LICENSING.version());
        assertEquals(com.documentplatform.fields.jdbc.FieldSchema.VERSION, SchemaVersion.FIELDS.version());
        assertEquals(2, SchemaVersion.FIELDS.version());
        assertEquals(3, SchemaVersion.ERASE_LOG.version());
        assertEquals(1, SchemaVersion.NOTIFICATIONS.version());
        assertEquals("notif_schema_history", SchemaVersion.NOTIFICATIONS.historyTable());
        assertEquals(1, SchemaVersion.NOTIFICATIONS.version());
        assertEquals("notif_schema_history", SchemaVersion.NOTIFICATIONS.historyTable());
        assertEquals(java.util.List.of(SchemaVersion.IDENTITY, SchemaVersion.LICENSING, SchemaVersion.FIELDS, SchemaVersion.ERASE_LOG, SchemaVersion.NOTIFICATIONS), SchemaVersion.PLATFORM_RUNS);
        assertEquals("identity V5", SchemaVersion.IDENTITY.key(5).id());
        assertTrue(com.documentplatform.identity.jdbc.IdentitySchema.scripts().contains("V5__identity_history_and_search_path.sql"));
    }

    /** Task 0057: platform-core 0.6.0 starts its start-up messages with their code; Vero never writes it twice. */
    @Test
    void aPlatformCodeIsNamedOnceInTheStartupMessage() {
        assertEquals("IDN_PRIVILEGES_MISSING: the app user lacks SELECT", DatabaseSchemaCheck.withCode("IDN_PRIVILEGES_MISSING",
                "IDN_PRIVILEGES_MISSING: the app user lacks SELECT"));
        assertEquals("IDN_PRIVILEGES_MISSING: the app user lacks SELECT", DatabaseSchemaCheck.withCode("IDN_PRIVILEGES_MISSING",
                "the app user lacks SELECT"));
        assertEquals("IDN_TABLES_MISSING: ", DatabaseSchemaCheck.withCode("IDN_TABLES_MISSING", null));
    }

    @Test
    void theLocalSeedIsShippedButNotRequiredForAProductionInstall() {
        assertTrue(SHIPPED.all().contains(SEED));
        assertFalse(SHIPPED.production().contains(SEED));
        assertTrue(SHIPPED.production().contains(new SchemaVersion.Key("018-search-date-indexes", "sdv")));
        assertEquals(SHIPPED.all().size() - 1, SHIPPED.production().size());
    }

    @Test
    void anEmptyDatabaseIsOlderAndEveryProductionChangesetIsMissing() {
        SchemaVersion.Status status = SchemaVersion.compare(SHIPPED, List.of());
        assertEquals(SchemaVersion.State.OLDER, status.state());
        assertEquals(SHIPPED.production(), status.missing());
        assertEquals("The database is older than this Vero version. Ask your database team to run the schema step (install guide, \"Update the database\").", status.message());
    }

    @Test
    void aDatabaseWithEveryProductionChangesetIsCurrent() {
        assertTrue(SchemaVersion.compare(SHIPPED, SHIPPED.production()).current());
    }

    @Test
    void aLocalDatabaseWithTheSeedAppliedIsAlsoCurrent() {
        List<SchemaVersion.Key> applied = new ArrayList<>(SHIPPED.production());
        applied.add(SEED);
        assertTrue(SchemaVersion.compare(SHIPPED, applied).current());
    }

    @Test
    void oneMissingChangesetMakesItOlder() {
        List<SchemaVersion.Key> applied = new ArrayList<>(SHIPPED.production());
        SchemaVersion.Key last = applied.remove(applied.size() - 1);
        SchemaVersion.Status status = SchemaVersion.compare(SHIPPED, applied);
        assertEquals(SchemaVersion.State.OLDER, status.state());
        assertEquals(List.of(last), status.missing());
    }

    @Test
    void anUnknownChangesetMakesItNewerEvenWhenSomethingIsAlsoMissing() {
        List<SchemaVersion.Key> applied = new ArrayList<>(SHIPPED.production().subList(0, 3));
        applied.add(new SchemaVersion.Key("099-from-a-later-version", "sdv"));
        SchemaVersion.Status status = SchemaVersion.compare(SHIPPED, applied);
        assertEquals(SchemaVersion.State.NEWER, status.state());
        assertTrue(status.message().startsWith("The database is newer than this Vero version."));
        assertEquals(List.of(new SchemaVersion.Key("099-from-a-later-version", "sdv")), status.unknown());
    }

    @Test
    void theSameIdByAnotherAuthorIsADifferentChangeset() {
        List<SchemaVersion.Key> applied = new ArrayList<>(SHIPPED.production());
        SchemaVersion.Key first = applied.remove(0);
        applied.add(0, new SchemaVersion.Key(first.id(), "someone-else"));
        assertEquals(SchemaVersion.State.NEWER, SchemaVersion.compare(SHIPPED, applied).state());
    }
}
