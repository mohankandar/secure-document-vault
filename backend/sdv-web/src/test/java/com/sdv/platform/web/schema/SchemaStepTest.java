package com.sdv.platform.web.schema;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SchemaStepTest {
    private static final Function<String, String> ENV = Map.of(
            SchemaStep.URL_ENV, "jdbc:postgresql://db.example:5432/vero",
            SchemaStep.USER_ENV, "vero_owner")::get;

    @Test
    void readsTheConnectionFromTheEnvironment() {
        SchemaStep.Command c = SchemaStep.parse(new String[]{"status"}, ENV);
        assertEquals("status", c.action());
        assertEquals("jdbc:postgresql://db.example:5432/vero", c.url());
        assertEquals("vero_owner", c.username());
    }

    @Test
    void optionsOverrideTheEnvironment() {
        SchemaStep.Command c = SchemaStep.parse(new String[]{"sql", "--output", "out.sql", "--username", "other"}, ENV);
        assertEquals("other", c.username());
        assertEquals("out.sql", c.output().toString());
    }

    @Test
    void refusesThePasswordOnTheCommandLine() {
        var e = assertThrows(IllegalArgumentException.class, () -> SchemaStep.parse(new String[]{"update", "--password", "x"}, ENV));
        assertTrue(e.getMessage().contains(SchemaStep.PASSWORD_ENV));
    }

    @Test
    void theContextCannotBeChanged() {
        assertThrows(IllegalArgumentException.class, () -> SchemaStep.parse(new String[]{"update", "--contexts", "local"}, ENV));
        assertThrows(IllegalArgumentException.class, () -> SchemaStep.parse(new String[]{"update", "--context", "local"}, ENV));
    }

    @Test
    void refusesUnclearRequests() {
        assertThrows(IllegalArgumentException.class, () -> SchemaStep.parse(new String[]{}, ENV));
        assertThrows(IllegalArgumentException.class, () -> SchemaStep.parse(new String[]{"migrate"}, ENV));
        assertThrows(IllegalArgumentException.class, () -> SchemaStep.parse(new String[]{"sql"}, ENV));
        assertThrows(IllegalArgumentException.class, () -> SchemaStep.parse(new String[]{"update", "--output", "x.sql"}, ENV));
        assertThrows(IllegalArgumentException.class, () -> SchemaStep.parse(new String[]{"status"}, k -> null));
        assertThrows(IllegalArgumentException.class, () -> SchemaStep.parse(new String[]{"status", "--url", "jdbc:mysql://x/y"}, ENV));
        assertThrows(IllegalArgumentException.class, () -> SchemaStep.parse(new String[]{"status", "--url"}, ENV));
    }

    @Test
    void wrongUsageExitsWithTwoWithoutConnecting() {
        assertEquals(2, SchemaStep.run(new String[]{"update", "--contexts", "local"}, ENV, null));
        // No password in the environment and no console to ask on: refused before any connection is tried.
        assertEquals(2, SchemaStep.run(new String[]{"status"}, ENV, null));
    }
}
