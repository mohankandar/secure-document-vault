package com.sdv.platform.web.licensing;

import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.LicenceService;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.web.schema.DatabaseSchemaCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Task 0075: writes the usage into platform-core's ledger: storage once a day (whole GB, rounded up), the seats whenever a
 * count grew. Numbers only, per entity; recorded, never enforced. The month's highest counts, so writing one again after
 * a restart changes nothing.
 */
@Component
public class UsageJob {
    private static final Logger log = LoggerFactory.getLogger(UsageJob.class);
    static final long GB = 1_000_000_000L;

    @FunctionalInterface
    interface Ledger { void record(String slotId, AllowanceType type, long amount); }

    private final DatabaseSchemaCheck schema;
    private final TenantRepository tenants;
    private final PlatformIdentityReads identity;
    private final JdbcTemplate db;
    private final SeatMeter seats;
    private final Ledger ledger;
    private final Clock clock = Clock.systemUTC();
    private final Map<UUID, LocalDate> storageDone = new HashMap<>();
    private final Map<UUID, SeatMeter.Counts> written = new HashMap<>();

    public UsageJob(DatabaseSchemaCheck schema, TenantRepository tenants, PlatformIdentityReads identity, JdbcTemplate db,
                    SeatMeter seats, LicenceService licence) {
        this.schema = schema;
        this.tenants = tenants;
        this.identity = identity;
        this.db = db;
        this.seats = seats;
        this.ledger = (slot, type, amount) -> licence.recordUsage(slot, type, amount, true);
    }

    @Scheduled(initialDelayString = "${sdv.licensing.usage-initial-delay-ms:60000}", fixedDelayString = "${sdv.licensing.usage-delay-ms:300000}")
    public synchronized void run() {
        if (!schema.current()) return;
        try {
            LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            Map<UUID, SeatMeter.Counts> counts = seats.counts();
            for (var tenant : tenants.findActive()) {
                UUID entity = tenant.tenantId();
                String slot = identity.slotOf(entity).map(PlatformIdentityReads.EntitySlot::slotId).orElse(null);
                if (slot == null) continue;
                if (!today.equals(storageDone.get(entity))) {
                    Long bytes = db.queryForObject("select coalesce(sum(content_size_bytes), 0) from documents where tenant_id = ? and status = 'AVAILABLE'", Long.class, entity);
                    ledger.record(slot, AllowanceType.STORAGE, gigabytes(bytes == null ? 0 : bytes));
                    storageDone.put(entity, today);
                }
                if (counts.containsKey(entity)) {
                    seats(slot, counts.get(entity), written.get(entity), YearMonth.from(today), ledger);
                    written.put(entity, counts.get(entity));
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Writing the usage failed; the next run continues. Nothing is limited by it. Cause: {}", ex.getClass().getSimpleName());
        }
    }

    static long gigabytes(long bytes) {
        return bytes == 0 ? 0 : (bytes - 1) / GB + 1;
    }

    /** A count goes in when it grew, or when its day or month is new. A day of an earlier month is left out. */
    static void seats(String slot, SeatMeter.Counts now, SeatMeter.Counts before, YearMonth month, Ledger ledger) {
        if (YearMonth.from(now.day()).equals(month) && now.concurrentPeak() > 0
                && (before == null || !now.day().equals(before.day()) || now.concurrentPeak() > before.concurrentPeak())) {
            ledger.record(slot, AllowanceType.CONCURRENT_SEATS, now.concurrentPeak());
        }
        if (now.month().equals(month) && now.named() > 0
                && (before == null || !now.month().equals(before.month()) || now.named() > before.named())) {
            ledger.record(slot, AllowanceType.NAMED_SEATS, now.named());
        }
    }
}
