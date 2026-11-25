package com.sdv.platform.web.monitoring;

import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.Verdict;
import com.documentplatform.monitoring.checks.LoginSystemCheck;
import com.documentplatform.monitoring.spring.AppVersions;
import com.documentplatform.monitoring.spring.RequestReferenceFilter;
import com.sdv.platform.application.operations.BusyAnswers;
import com.sdv.platform.application.port.KeyServiceHealthProbe;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.port.UnidentifiedIntakeAttemptRepository;
import com.sdv.platform.application.storage.StorageVolumeAdminService;
import com.sdv.platform.application.storage.StorageVolumeView;
import com.sdv.platform.domain.storage.StorageVolumeState;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.web.schema.DatabaseSchemaCheck;
import com.sdv.platform.web.schema.SchemaVersion;
import com.sdv.platform.web.security.SignInKeysCheck;
import com.sdv.platform.domain.identity.VeroProduct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.health.application.ReadinessStateHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Task 0074: Vero's checks beside platform-core's, in one list: Platform operations, Health; IT, Problems;
 * {@code /actuator/health/dependencies}. Readiness stays Vero's own (application.yml): the list is up to a minute old.
 */
@Configuration
public class VeroMonitoringConfiguration {
    /** Fewer are somebody's mistyped password; the Problems list shows every one. */
    static final int ATTEMPTS_AN_HOUR = 10;
    private static final Duration EVERY = Duration.ofSeconds(60);

    @Bean
    DependencyCheck veroSchemaCheck(DatabaseSchemaCheck schema) {
        return new VeroCheck("vero-schema", "database", EVERY, () -> schema(schema.status()));
    }

    static Verdict schema(SchemaVersion.Status status) {
        return switch (status.state()) {
            case CURRENT -> Verdict.working();
            case OLDER -> Verdict.down(VeroCheckCode.SCHEMA_OLDER);
            case NEWER -> Verdict.down(VeroCheckCode.SCHEMA_NEWER);
            case UNREACHABLE -> Verdict.down(VeroCheckCode.DATABASE_AWAY);
        };
    }

    @Bean
    DependencyCheck veroBusyCheck(BusyAnswers busy) {
        return new VeroCheck("busy", "database", EVERY, () -> busy(busy.count()));
    }

    static Verdict busy(BusyAnswers.Count count) {
        return count.lastHour() == 0 ? Verdict.working() : Verdict.problems(VeroCheckCode.POOL_BUSY, Map.of("count", count.lastHour()));
    }

    @Bean
    DependencyCheck veroStorageCheck(TenantRepository tenants, StorageVolumeAdminService storage) {
        return new VeroCheck("storage", "folders", EVERY, () ->
                storage(tenants.findActive().stream().flatMap(t -> storage.list(t.tenantId()).stream()).toList()));
    }

    /** Counts only: the list is for all of Vero, and a volume's name and folder belong to one entity. */
    static Verdict storage(List<StorageVolumeView> volumes) {
        List<StorageVolumeView> active = volumes.stream().filter(v -> v.volume().state() == StorageVolumeState.ACTIVE).toList();
        long stopped = active.stream().filter(v -> v.observationError() != null || v.stopWriteThresholdReached()).count();
        long warning = active.stream().filter(v -> v.observationError() == null && !v.stopWriteThresholdReached() && v.warningThresholdReached()).count();
        Map<String, Object> details = Map.of("warning", warning, "stopped", stopped, "active", active.size());
        if (stopped > 0 && stopped == active.size()) return Verdict.down(VeroCheckCode.STORAGE, details);
        return stopped + warning > 0 ? Verdict.problems(VeroCheckCode.STORAGE, details) : Verdict.working();
    }

    @Bean
    DependencyCheck veroIntakeAttemptsCheck(TenantRepository tenants, UnidentifiedIntakeAttemptRepository attempts) {
        return new VeroCheck("intake-attempts", "intake", EVERY, () -> {
            Instant since = Instant.now().minus(Duration.ofHours(1));
            return attempts(tenants.findActive().stream().mapToLong(t -> attempts.count(t.tenantId(), since)).max().orElse(0));
        });
    }

    static Verdict attempts(long count) {
        return count < ATTEMPTS_AN_HOUR ? Verdict.working() : Verdict.problems(VeroCheckCode.INTAKE_ATTEMPTS, Map.of("count", count));
    }

    @Bean
    DependencyCheck veroKeyServiceCheck(KeyServiceHealthProbe keys) {
        return new VeroCheck("key-service", "keys", EVERY, () -> keys(keys.check().status()));
    }

    static Verdict keys(KeyServiceHealthProbe.Status status) {
        Map<String, Object> details = Map.of("state", status.name());
        return switch (status) {
            case UP -> Verdict.working();
            case NOT_CONFIGURED -> Verdict.problems(VeroCheckCode.KEY_SERVICE, details);
            case DOWN -> Verdict.down(VeroCheckCode.KEY_SERVICE, details);
        };
    }

    @Bean
    DependencyCheck veroEraseLogCheck(EraseGuard guard) {
        return new VeroCheck("erase-log", "erase-log", EVERY, () -> eraseLog(guard.state()));
    }

    /** IDs and codes only; an entry holds nothing else. */
    static Verdict eraseLog(EraseGuard.State state) {
        if (!state.mayTakeWork()) return Verdict.down(VeroCheckCode.ERASE_LOG, Map.of("code", state.code() == null ? "" : state.code().code()));
        EraseGuard.ApplyFailure f = state.lastFailure();
        if (f == null) return Verdict.working();
        return Verdict.problems(VeroCheckCode.ERASE_PENDING, Map.of("seq", f.seq(), "kind", f.kind(), "record", f.recordId(),
                "code", com.sdv.platform.web.erase.EraseIds.shown(f.handlerCode() == null ? f.code().code() : f.handlerCode())));
    }

    @Bean
    SignInKeysCheck signInKeysCheck(@Value("${sdv.auth.mode:disabled}") String mode, @Value("${sdv.auth.jwt.jwk-set-uri:}") String jwkSetUri) {
        return new SignInKeysCheck(mode, jwkSetUri);
    }

    /** platform-core's check, made here because Vero's customer comes from the setup tables, not from a setting. */
    @Bean
    DependencyCheck veroLoginSystemCheck(TenantRepository tenants, PlatformIdentityReads reads, LoginConnectionStore connections,
                                         IssuerFetch fetch, DevelopmentLicence licence) {
        // The licence is read when the check runs, never while Vero starts.
        return new VeroCheck("login-system", "sign-in", EVERY, () -> new LoginSystemCheck(VeroProduct.CODE, () -> tenants.findActive().stream().findFirst()
                .flatMap(t -> reads.slotOf(t.tenantId())).flatMap(slot -> connections.active(slot.tenantId(), VeroProduct.CODE)),
                fetch, licence.signedByDevelopmentKey(), Clock.systemUTC()).check());
    }

    /** Vero's readiness group has its own live checks: the module's readiness would keep up to a minute-old result in it. */
    @Bean
    HealthIndicator readinessStateHealthIndicator(ApplicationAvailability availability) {
        return new ReadinessStateHealthIndicator(availability);
    }

    /** The first filter of all: every answer and log line has the reference, also one refused before sign-in. */
    @Bean
    FilterRegistrationBean<RequestReferenceFilter> requestReferenceFilter(RequestReferenceFilter filter) {
        FilterRegistrationBean<RequestReferenceFilter> first = new FilterRegistrationBean<>(filter);
        first.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return first;
    }

    @Bean
    AppVersions veroVersion(ObjectProvider<BuildProperties> build) {
        return () -> Map.of("vero", Optional.ofNullable(build.getIfAvailable()).map(BuildProperties::getVersion).orElse("unknown"));
    }
}
