package com.sdv.platform.web.schema;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseSchemaHealthIndicatorTest {
    private static DatabaseSchemaCheck check(SchemaVersion.State state) {
        return new DatabaseSchemaCheck(new SchemaVersion.Status(state, List.of(), List.of(), 0));
    }

    @Test
    void aMismatchedDatabaseIsOutOfServiceNotDown() {
        for (SchemaVersion.State state : List.of(SchemaVersion.State.OLDER, SchemaVersion.State.NEWER, SchemaVersion.State.UNREACHABLE)) {
            var health = new DatabaseSchemaHealthIndicator(check(state), e -> { }).health();
            assertEquals(Status.OUT_OF_SERVICE, health.getStatus(), state.name());
            assertEquals(state.name(), health.getDetails().get("state"));
        }
    }

    @Test
    void aMatchingDatabaseIsUp() {
        assertEquals(Status.UP, new DatabaseSchemaHealthIndicator(check(SchemaVersion.State.CURRENT), e -> { }).health().getStatus());
    }

    @Test
    void whenStartedAgainstAMismatchedDatabaseVeroRefusesTraffic() {
        List<Object> published = new ArrayList<>();
        new DatabaseSchemaHealthIndicator(check(SchemaVersion.State.OLDER), published::add).refuseTrafficWhileTheDatabaseDoesNotMatch();
        assertEquals(1, published.size());
        assertEquals(ReadinessState.REFUSING_TRAFFIC, ((AvailabilityChangeEvent<?>) published.get(0)).getState());
    }

    @Test
    void aMatchingDatabaseLeavesReadinessAlone() {
        List<Object> published = new ArrayList<>();
        new DatabaseSchemaHealthIndicator(check(SchemaVersion.State.CURRENT), published::add).refuseTrafficWhileTheDatabaseDoesNotMatch();
        assertTrue(published.isEmpty());
    }
}
