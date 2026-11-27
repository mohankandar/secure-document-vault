package com.sdv.platform.web.notifications;

import com.sdv.platform.application.notifications.VeroEvent;
import com.sdv.platform.application.notifications.VeroNotice;
import com.sdv.platform.application.port.KeyServiceHealthProbe;
import com.sdv.platform.application.port.NotificationPort;
import com.sdv.platform.application.port.OperationalRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.port.UnidentifiedIntakeAttemptRepository;
import com.sdv.platform.application.storage.StorageVolumeAdminService;
import com.sdv.platform.application.storage.StorageVolumeView;
import com.sdv.platform.domain.tenant.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 0081: the technical events, from what Vero's screens already show. Every few minutes, for each active entity:
 * <ul>
 *   <li>documents that could not be stored (IT › Problems) and deliveries still missing documents: conditions, alerted
 *       once they last past their threshold, Recovered once gone;</li>
 *   <li>a storage volume past its warning or its stop threshold (IT › Storage): conditions with the highest percent;</li>
 *   <li>unidentified intake attempts since the last check: one event with their count.</li>
 * </ul>
 * and once for the install: the key service not reachable (a condition, critical). The watch reads; it never changes
 * anything. A failing check is skipped for that round (one WARN line with the check and the exception's class).
 */
@Component
public class TechnicalWatch {
    private static final Logger log = LoggerFactory.getLogger(TechnicalWatch.class);
    static final Duration PROCESSING_STUCK_AFTER = Duration.ofMinutes(15);
    static final Duration TRANSITION_STUCK_AFTER = Duration.ofMinutes(10);

    private final TenantRepository tenants;
    private final OperationalRepository operations;
    private final StorageVolumeAdminService storage;
    private final UnidentifiedIntakeAttemptRepository attempts;
    private final KeyServiceHealthProbe keyService;
    private final NotificationPort notifications;
    private final Clock clock;
    private final Map<UUID, Instant> attemptsSince = new HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public TechnicalWatch(TenantRepository tenants, OperationalRepository operations, StorageVolumeAdminService storage,
                          UnidentifiedIntakeAttemptRepository attempts, KeyServiceHealthProbe keyService,
                          NotificationPort notifications) {
        this(tenants, operations, storage, attempts, keyService, notifications, Clock.systemUTC());
    }

    TechnicalWatch(TenantRepository tenants, OperationalRepository operations, StorageVolumeAdminService storage,
                   UnidentifiedIntakeAttemptRepository attempts, KeyServiceHealthProbe keyService,
                   NotificationPort notifications, Clock clock) {
        this.tenants = tenants;
        this.operations = operations;
        this.storage = storage;
        this.attempts = attempts;
        this.keyService = keyService;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "${sdv.notifications.watch-initial-delay-ms:90000}",
            fixedDelayString = "${sdv.notifications.watch-delay-ms:300000}")
    public synchronized void run() {
        if (notifications == NotificationPort.NONE) return;
        Instant now = clock.instant();
        check("key service", () -> keyService(now));
        List<Tenant> active;
        try {
            active = tenants.findActive();
        } catch (RuntimeException ex) {
            log.warn("Notifications watch: the entities could not be read ({}).", ex.getClass().getSimpleName());
            return;
        }
        for (Tenant t : active) {
            UUID entity = t.tenantId();
            check("problems", () -> problems(entity, now));
            check("storage", () -> storage(entity));
            check("unidentified attempts", () -> unidentified(entity, now));
        }
    }

    private void keyService(Instant now) {
        KeyServiceHealthProbe.Health h = keyService.check();
        if (h.status() == KeyServiceHealthProbe.Status.DOWN) {
            notifications.failing(VeroNotice.of(VeroEvent.KEY_SERVICE_AWAY, null).build());
        } else if (h.status() == KeyServiceHealthProbe.Status.UP) {
            notifications.cleared(VeroEvent.KEY_SERVICE_AWAY, null);
        }
    }

    private void problems(UUID entity, Instant now) {
        long failures = operations.countProcessingFailures(entity)
                + operations.countStuckDocuments(entity, now.minus(PROCESSING_STUCK_AFTER), now.minus(TRANSITION_STUCK_AFTER));
        condition(VeroEvent.DOCUMENTS_NOT_STORED, entity, VeroEvent.COUNT_DOCUMENTS, failures);
        condition(VeroEvent.DELIVERIES_INCOMPLETE, entity, VeroEvent.COUNT_DELIVERIES, operations.countAttentionSubmissions(entity, now));
    }

    private void storage(UUID entity) {
        int nearFull = -1;
        int full = -1;
        for (StorageVolumeView v : storage.list(entity)) {
            if (v.capacity() == null) continue;
            int used = Math.max(0, Math.min(100, v.capacity().usedPercent()));
            if (v.stopWriteThresholdReached()) full = Math.max(full, used);
            else if (v.warningThresholdReached()) nearFull = Math.max(nearFull, used);
        }
        condition(VeroEvent.STORAGE_FULL, entity, VeroEvent.COUNT_PERCENT_USED, full < 0 ? 0 : Math.max(1, full));
        condition(VeroEvent.STORAGE_NEAR_FULL, entity, VeroEvent.COUNT_PERCENT_USED, nearFull < 0 ? 0 : Math.max(1, nearFull));
    }

    private void unidentified(UUID entity, Instant now) {
        Instant since = attemptsSince.getOrDefault(entity, now.minus(Duration.ofMinutes(5)));
        long count = attempts.count(entity, since);
        attemptsSince.put(entity, now);
        if (count > 0) {
            notifications.publish(VeroNotice.of(VeroEvent.UNIDENTIFIED_ATTEMPTS, entity).count(VeroEvent.COUNT_ATTEMPTS, count).build());
        }
    }

    /** value 0: the condition is gone; more: it is failing, with that count. */
    private void condition(VeroEvent event, UUID entity, String count, long value) {
        if (value > 0) notifications.failing(VeroNotice.of(event, entity).count(count, value).build());
        else notifications.cleared(event, entity);
    }

    private static void check(String what, Runnable check) {
        try {
            check.run();
        } catch (RuntimeException ex) {
            log.warn("Notifications watch: the {} check was skipped this round ({}).", what, ex.getClass().getSimpleName());
        }
    }
}
