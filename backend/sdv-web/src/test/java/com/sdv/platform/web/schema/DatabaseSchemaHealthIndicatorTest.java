package com.sdv.platform.web.schema;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
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
        for (SchemaVersion.State state : List.of(SchemaVersion.State.OLDER, SchemaVersion.State.NEWER)) {
            var health = new DatabaseSchemaHealthIndicator(check(state), e -> { }).health();
            assertEquals(Status.OUT_OF_SERVICE, health.getStatus(), state.name());
            assertEquals(state.name(), health.getDetails().get("state"));
        }
    }

    /** Task 0039 part 2 (owner answer 4): unreachable at start is readiness-only, as after the start. */
    @Test
    void aDatabaseThatCantBeReachedAtStartIsNotReadyOnlyAndDoesNotRefuseTraffic() {
        List<Object> published = new ArrayList<>();
        var indicator = new DatabaseSchemaHealthIndicator(check(SchemaVersion.State.UNREACHABLE), published::add);
        var health = indicator.health();
        assertEquals(ReadinessOnly.NOT_READY, health.getStatus());
        assertEquals("UNREACHABLE", health.getDetails().get("state"));
        indicator.refuseTrafficWhileTheDatabaseDoesNotMatch();
        assertTrue(published.isEmpty(), "readiness comes from the checks, not from a refusal");
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
