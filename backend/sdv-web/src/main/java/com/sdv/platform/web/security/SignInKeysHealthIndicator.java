package com.sdv.platform.web.security;

import com.documentplatform.monitoring.CheckResult;
import com.documentplatform.monitoring.CheckState;
import com.documentplatform.monitoring.DependencyStatus;
import com.sdv.platform.web.schema.ReadinessOnly;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/** Task 0038: readiness counts the sign-in keys. Task 0074: the last result of {@link SignInKeysCheck}; none yet is not ready. */
@Component("signInKeys")
public class SignInKeysHealthIndicator implements HealthIndicator {
    private final boolean used;
    private final Supplier<List<CheckResult>> results;

    @Autowired
    public SignInKeysHealthIndicator(SignInKeysCheck check, DependencyStatus status) {
        this(check.used(), status::results);
    }

    SignInKeysHealthIndicator(boolean used, Supplier<List<CheckResult>> results) {
        this.used = used;
        this.results = results;
    }

    @Override
    public Health health() {
        if (!used) return Health.up().withDetail("signIn", "not enterprise-jwt; no sign-in keys are used").build();
        CheckResult last = results.get().stream().filter(r -> r.name().equals(SignInKeysCheck.NAME)).findFirst().orElse(null);
        if (last == null) return Health.status(ReadinessOnly.NOT_READY).withDetail("reason", "not checked yet").build();
        if (last.state() != CheckState.DOWN) return Health.up().build();
        return Health.status(ReadinessOnly.NOT_READY).withDetail("code", last.code().code()).withDetail("reason", last.cause()).build();
    }
}
