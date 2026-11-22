package com.sdv.platform.application.access;

import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task 0039 (owner answer to report 0038, 3): every signed-in request checks that its tenant exists and is active, so a
 * suspended tenant is refused everywhere (SDV-AUT-009, the same answer as {@link ActiveTenant}). The status is kept for
 * a short time, so the check is not a database read on every request: {@code sdv.tenant-status-cache-seconds}
 * (SDV_TENANT_STATUS_CACHE_SECONDS, default 30, at most 60; 0 reads every time). A suspension therefore takes effect
 * within that time; the places that read the tenant anyway ({@link ActiveTenant}) refuse it at once.
 * <p>
 * An unknown tenant is kept as unknown for the same time. If the database can't be read the check fails (the
 * request gets SDV-SYS-014): it is never assumed active.
 */
@Component
public class ActiveTenantCache {
    public static final int MAX_SECONDS = 60;

    private final TenantRepository tenants;
    private final Duration keep;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Seen> seen = new ConcurrentHashMap<>();

    @Autowired
    public ActiveTenantCache(TenantRepository tenants, @Value("${sdv.tenant-status-cache-seconds:30}") int seconds) {
        this(tenants, Duration.ofSeconds(checked(seconds)), Clock.systemUTC());
    }

    ActiveTenantCache(TenantRepository tenants, Duration keep, Clock clock) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.keep = keep;
        this.clock = clock;
    }

    private static int checked(int seconds) {
        if (seconds < 0 || seconds > MAX_SECONDS) {
            throw new com.sdv.platform.domain.error.StartupFailure(SdvErrorCode.SYS_005,
                    "sdv.tenant-status-cache-seconds (SDV_TENANT_STATUS_CACHE_SECONDS) must be 0 to " + MAX_SECONDS
                            + "; it is " + seconds + ".");
        }
        return seconds;
    }

    /** Refuses (403 SDV-AUT-009) unless the tenant exists and is active, as last seen within the cache time. */
    public void requireActive(UUID tenantId) {
        if (tenantId == null) throw refused(null, "missing");
        Instant now = clock.instant();
        Seen known = seen.get(tenantId);
        if (known == null || !now.isBefore(known.until())) {
            Tenant tenant = tenants.findById(tenantId).orElse(null);
            known = new Seen(tenant == null ? null : tenant.status(), now.plus(keep));
            if (!keep.isZero()) seen.put(tenantId, known);
            if (seen.size() > 10_000) seen.clear(); // tenant IDs come from verified sign-ins; never grows without end
        }
        if (known.status() == null) throw refused(tenantId, "missing");
        if (known.status() != TenantStatus.ACTIVE) throw refused(tenantId, "suspended (" + known.status() + ")");
    }

    private static CodedRefusal.Forbidden refused(UUID tenantId, String why) {
        return new CodedRefusal.Forbidden(SdvErrorCode.AUT_009, ActiveTenant.MESSAGE,
                "tenant=" + tenantId + " " + why + " (request check)");
    }

    private record Seen(TenantStatus status, Instant until) { }
}
