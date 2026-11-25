package com.sdv.platform.web.erase;

import com.documentplatform.eraselog.EraseGuard;
import com.sdv.platform.web.schema.ReadinessOnly;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Task 0080: {@code /actuator/health/eraseLogState} and readiness. IDs and codes only; the state is kept, nothing is read here. */
@Component("eraseLogState")
public class EraseLogHealth implements HealthIndicator {
    private final EraseGuard guard;

    public EraseLogHealth(EraseGuard guard) {
        this.guard = guard;
    }

    @Override
    public Health health() {
        EraseGuard.State s = guard.state();
        Health.Builder h = s.mayTakeWork() ? Health.up() : Health.status(ReadinessOnly.NOT_READY);
        h.withDetail("mayTakeWork", s.mayTakeWork()).withDetail("logHead", s.logHead()).withDetail("applied", s.applied()).withDetail("pending", s.pending());
        if (s.code() != null) h.withDetail("code", s.code().code());
        if (s.checkedAt() != null) h.withDetail("checkedAt", s.checkedAt().toString());
        EraseGuard.ApplyFailure f = s.lastFailure();
        if (f != null) {
            h.withDetail("lastFailure", "entry " + f.seq() + " " + f.kind() + " " + f.recordId() + " " + EraseIds.shown(f.handlerCode() == null ? f.code().code() : f.handlerCode()));
        }
        return h.build();
    }
}
