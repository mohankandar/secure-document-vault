package com.sdv.platform.web.licensing;

import com.sdv.platform.security.auth.ActiveTenantFilter;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.SdvRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0075: who is active, for the two seat counts (answer 10). Concurrent: people with a request in the last 15 minutes,
 * the day's peak. Named: people whose sign-in gave a steward or admin role in the month. Who they are stays in memory
 * here; only the counts and keyed hashes leave ({@link UsageJob}, {@link NamedSeats}). Sending systems are not people. Nothing is ever refused.
 */
@Component
public class SeatMeter implements HandlerInterceptor {
    static final Duration ACTIVE = Duration.ofMinutes(15);
    static final Set<SdvRole> NAMED = EnumSet.of(SdvRole.DOCUMENT_STEWARD, SdvRole.RESTRICTED_STEWARD, SdvRole.BUSINESS_ADMIN, SdvRole.IT_ADMIN, SdvRole.PLATFORM_OPERATIONS_ADMIN);
    private final Clock clock;
    private final Map<UUID, Entity> entities = new HashMap<>();
    private final Map<Month, Set<String>> pending = new HashMap<>();

    private static final class Entity {
        final Map<String, Instant> lastSeen = new HashMap<>();
        LocalDate day;
        int peak;
        YearMonth month;
        /** Task 0090: seen this month by this Vero, so each person is handed over once. */
        final Set<String> named = new HashSet<>();
    }

    /** The counts of one entity now. */
    public record Counts(LocalDate day, int concurrentPeak) { }

    /** Task 0090: people of an entity and month that are not in the table yet. */
    record Month(UUID entity, YearMonth month) { }

    @Autowired
    public SeatMeter() {
        this(Clock.systemUTC());
    }

    SeatMeter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getAttribute(ActiveTenantFilter.PRINCIPAL_ATTRIBUTE) instanceof SdvPrincipal p && p.tenantId() != null) {
            seen(p.tenantId(), p.subject(), p.roles());
        }
        return true;
    }

    synchronized void seen(UUID entity, String subject, Set<SdvRole> roles) {
        Instant now = clock.instant();
        Entity e = entities.computeIfAbsent(entity, k -> new Entity());
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        YearMonth month = YearMonth.from(day);
        if (!day.equals(e.day)) { e.day = day; e.peak = 0; }
        if (!month.equals(e.month)) { e.month = month; e.named.clear(); }
        e.lastSeen.values().removeIf(t -> t.isBefore(now.minus(ACTIVE)));
        e.lastSeen.put(subject, now);
        e.peak = Math.max(e.peak, e.lastSeen.size());
        if (roles.stream().anyMatch(NAMED::contains) && e.named.add(subject)) pending.computeIfAbsent(new Month(entity, month), k -> new HashSet<>()).add(subject);
    }

    /** The people to write; given back when that fails. */
    synchronized Map<Month, Set<String>> takePending() {
        Map<Month, Set<String>> out = new HashMap<>(pending);
        pending.clear();
        return out;
    }

    synchronized void giveBack(Month month, Set<String> people) {
        pending.computeIfAbsent(month, k -> new HashSet<>()).addAll(people);
    }

    /** The counts of every entity somebody used since Vero started. */
    synchronized Map<UUID, Counts> counts() {
        Map<UUID, Counts> out = new HashMap<>();
        entities.forEach((id, e) -> out.put(id, new Counts(e.day, e.peak)));
        return out;
    }
}
