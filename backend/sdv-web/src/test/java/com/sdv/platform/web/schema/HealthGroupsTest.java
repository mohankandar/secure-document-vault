package com.sdv.platform.web.schema;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.autoconfigure.application.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0039 (owner answer to report 0038, 4): the sign-in keys check and the database ping belong to readiness only.
 * With Vero's own health settings (application.yml): while the database or the sign-in hub is away, readiness is not
 * ready (503); liveness and the overall /actuator/health stay UP (200), so an orchestrator never restarts Vero for it.
 */
class HealthGroupsTest {

    /** Vero's management.* settings, as application.yml has them. */
    private static String[] veroHealthSettings() throws Exception {
        List<String> values = new ArrayList<>();
        for (var source : new YamlPropertySourceLoader().load("vero", new ClassPathResource("application.yml"))) {
            for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                if (name.startsWith("management.")) values.add(name + "=" + source.getProperty(name));
            }
        }
        assertTrue(values.stream().anyMatch(v -> v.startsWith("management.endpoint.health.group.readiness.include=")));
        return values.toArray(String[]::new);
    }

    private static ApplicationContextRunner vero(Status database, Status keys) throws Exception {
        return vero(new DatabaseSchemaCheck(new SchemaVersion.Status(SchemaVersion.State.CURRENT, List.of(), List.of(), 0)),
                database, keys);
    }

    private static ApplicationContextRunner vero(DatabaseSchemaCheck schema, Status database, Status keys) throws Exception {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ApplicationAvailabilityAutoConfiguration.class,
                        EndpointAutoConfiguration.class, HealthContributorAutoConfiguration.class,
                        org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class, AvailabilityHealthContributorAutoConfiguration.class,
                        AvailabilityProbesAutoConfiguration.class))
                .withPropertyValues(veroHealthSettings())
                .withBean("databaseSchema", HealthIndicator.class,
                        () -> new DatabaseSchemaHealthIndicator(schema, event -> { }))
                .withBean("databaseConnection", HealthIndicator.class, () -> () -> Health.status(database).build())
                .withBean("signInKeys", HealthIndicator.class, () -> () -> Health.status(keys).build());
    }

    private record Answer(Status status, int http) { }

    private static Answer answer(org.springframework.context.ApplicationContext context, String group) {
        // What Spring publishes when the application has started and is ready (the runner doesn't).
        org.springframework.boot.availability.AvailabilityChangeEvent.publish(context,
                org.springframework.boot.availability.LivenessState.CORRECT);
        org.springframework.boot.availability.AvailabilityChangeEvent.publish(context,
                org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC);
        HealthEndpoint endpoint = context.getBean(HealthEndpoint.class);
        HealthEndpointGroups groups = context.getBean(HealthEndpointGroups.class);
        Status status = group == null ? endpoint.health().getStatus() : endpoint.healthForPath(group).getStatus();
        var mapper = (group == null ? groups.getPrimary() : groups.get(group)).getHttpCodeStatusMapper();
        return new Answer(status, mapper.getStatusCode(status));
    }

    @Test
    void theDatabaseOrTheHubAwayMakesOnlyReadinessNotReady() throws Exception {
        for (Status[] away : new Status[][]{
                {ReadinessOnly.NOT_READY, Status.UP}, {Status.UP, ReadinessOnly.NOT_READY},
                {ReadinessOnly.NOT_READY, ReadinessOnly.NOT_READY}}) {
            vero(away[0], away[1]).run(context -> {
                assertEquals(new Answer(ReadinessOnly.NOT_READY, 503), answer(context, "readiness"), "readiness");
                assertEquals(new Answer(Status.UP, 200), answer(context, "liveness"), "liveness");
                assertEquals(new Answer(Status.UP, 200), answer(context, null), "overall /actuator/health");
            });
        }
    }

    /**
     * Task 0039 part 2 (owner answer 4 to report 0039): the database can't be reached at start. The same rule as after
     * the start: readiness not ready (503), liveness and the overall health UP (200).
     */
    @Test
    void theDatabaseAwayAtStartIsTheSameRuleAsLater() throws Exception {
        DatabaseSchemaCheck unreachable = new DatabaseSchemaCheck(SchemaVersion.Status.unreachable());
        vero(unreachable, ReadinessOnly.NOT_READY, Status.UP).run(context -> {
            assertEquals(new Answer(ReadinessOnly.NOT_READY, 503), answer(context, "readiness"), "readiness");
            assertEquals(new Answer(Status.UP, 200), answer(context, "liveness"), "liveness");
            assertEquals(new Answer(Status.UP, 200), answer(context, null), "overall /actuator/health");
        });
        // A database that answers and doesn't match is unchanged: out of service everywhere but liveness.
        DatabaseSchemaCheck older = new DatabaseSchemaCheck(
                new SchemaVersion.Status(SchemaVersion.State.OLDER, List.of(), List.of(), 0));
        vero(older, Status.UP, Status.UP).run(context -> {
            assertEquals(new Answer(Status.OUT_OF_SERVICE, 503), answer(context, "readiness"));
            assertEquals(new Answer(Status.OUT_OF_SERVICE, 503), answer(context, null));
            assertEquals(new Answer(Status.UP, 200), answer(context, "liveness"));
        });
    }

    /**
     * Task 0040 part 2 (master section item 2): a busy database ping makes readiness BUSY (503), only readiness; when
     * something else is also wrong (the hub away), readiness is NOT_READY: busy is the answer only as the one reason.
     */
    @Test
    void busyIsReadinessOnlyAndOnlyWhenItIsTheOneReason() throws Exception {
        vero(ReadinessOnly.BUSY, Status.UP).run(context -> {
            assertEquals(new Answer(ReadinessOnly.BUSY, 503), answer(context, "readiness"), "readiness");
            assertEquals(new Answer(Status.UP, 200), answer(context, "liveness"), "liveness");
            assertEquals(new Answer(Status.UP, 200), answer(context, null), "overall /actuator/health");
        });
        vero(ReadinessOnly.BUSY, ReadinessOnly.NOT_READY).run(context ->
                assertEquals(new Answer(ReadinessOnly.NOT_READY, 503), answer(context, "readiness")));
        DatabaseSchemaCheck older = new DatabaseSchemaCheck(
                new SchemaVersion.Status(SchemaVersion.State.OLDER, List.of(), List.of(), 0));
        vero(older, ReadinessOnly.BUSY, Status.UP).run(context ->
                assertEquals(new Answer(Status.OUT_OF_SERVICE, 503), answer(context, "readiness")));
    }

    @Test
    void allBackMeansReadyAndARealDownStillCounts() throws Exception {
        vero(Status.UP, Status.UP).run(context -> {
            assertEquals(new Answer(Status.UP, 200), answer(context, "readiness"));
            assertEquals(new Answer(Status.UP, 200), answer(context, null));
        });
        // Readiness keeps Spring's own statuses too: a DOWN is 503 there.
        vero(Status.DOWN, Status.UP).run(context -> assertEquals(503, answer(context, "readiness").http()));
    }

    @Test
    void spingsOwnDatabaseCheckIsOffAndTheGroupsAreAsDocumented() throws Exception {
        String all = String.join("\n", veroHealthSettings());
        assertTrue(all.contains("management.health.db.enabled=false"), all);
        assertTrue(all.contains("management.endpoint.health.group.readiness.include=readinessState,databaseSchema,databaseConnection,signInKeys"), all);
        assertFalse(all.contains("management.endpoint.health.group.liveness"), "liveness stays Spring's: livenessState only");
    }
}
