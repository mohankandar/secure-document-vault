package com.sdv.platform.web.schema;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * While the database doesn't match this version Vero is up but not ready:
 * /actuator/health and /actuator/health/readiness report OUT_OF_SERVICE (503); /actuator/health/liveness stays UP,
 * so a platform that restarts dead processes leaves Vero running and showing why.
 * <p>
 * Task 0037: when the database couldn't be reached at start and comes back matching, readiness turns ready again
 * without a restart.
 */
@Component("databaseSchema")
public class DatabaseSchemaHealthIndicator implements HealthIndicator {
    private final DatabaseSchemaCheck check;
    private final ApplicationEventPublisher events;

    public DatabaseSchemaHealthIndicator(DatabaseSchemaCheck check, ApplicationEventPublisher events) {
        this.check = check;
        this.events = events;
        check.onChange(status -> AvailabilityChangeEvent.publish(events, this,
                status.current() ? ReadinessState.ACCEPTING_TRAFFIC : ReadinessState.REFUSING_TRAFFIC));
    }

    @Override
    public Health health() {
        SchemaVersion.Status status = check.status();
        Health.Builder builder = status.current() ? Health.up() : Health.outOfService();
        return builder.withDetail("state", status.state().name()).withDetail("message", status.message()).build();
    }

    /** Spring marks the application ready to take traffic when it has started; a mismatched database takes that back. */
    @EventListener(ApplicationReadyEvent.class)
    public void refuseTrafficWhileTheDatabaseDoesNotMatch() {
        if (!check.current()) AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
    }
}
