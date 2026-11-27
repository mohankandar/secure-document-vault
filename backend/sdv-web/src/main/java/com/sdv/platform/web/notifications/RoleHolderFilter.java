package com.sdv.platform.web.notifications;

import com.sdv.platform.application.notifications.RoleHolderService;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.SdvPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task 0100 (VSEC-203): after sign-in, what the request's person holds in their entity goes to {@link RoleHolderService}
 * (a role gone takes them off that role's lists). Written only when it changed since this Vero last saw it, and again
 * at most every {@link #FRESH}, so another Vero of the database can't leave a stale answer for long. A failure is
 * logged and the request goes on: lists change nothing about what the person may do.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public final class RoleHolderFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RoleHolderFilter.class);
    static final Duration FRESH = Duration.ofMinutes(10);
    static final int MAX_REMEMBERED = 10_000;

    private record Seen(Set<String> roles, Instant at) { }

    private final ObjectProvider<RoleHolderService> service;
    private final ObjectProvider<PlatformIdentityReads> identity;
    private final Clock clock;
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public RoleHolderFilter(ObjectProvider<RoleHolderService> service, ObjectProvider<PlatformIdentityReads> identity) {
        this(service, identity, Clock.systemUTC());
    }

    RoleHolderFilter(ObjectProvider<RoleHolderService> service, ObjectProvider<PlatformIdentityReads> identity, Clock clock) {
        this.service = service;
        this.identity = identity;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.isAuthenticated() && a.getPrincipal() instanceof SdvPrincipal p) note(p);
        chain.doFilter(request, response);
    }

    void note(SdvPrincipal p) {
        Set<String> roles = new TreeSet<>();
        for (SdvRole r : p.roles()) roles.add(r.name());
        roles.retainAll(RoleHolderService.LIST_ROLES);
        String key = p.tenantId() + "|" + p.subject();
        Instant now = clock.instant();
        Seen last = seen.get(key);
        if (last != null && last.roles().equals(roles) && last.at().plus(FRESH).isAfter(now)) return;
        try {
            RoleHolderService s = service.getIfAvailable();
            PlatformIdentityReads ids = identity.getIfAvailable();
            if (s == null || ids == null) return;
            String customer = ids.slotOf(p.tenantId()).map(PlatformIdentityReads.EntitySlot::tenantId).orElse(null);
            if (customer == null) return;
            int removed = s.seen(customer, p.tenantId(), p.subject(), roles).size();
            if (seen.size() >= MAX_REMEMBERED) seen.clear();
            seen.put(key, new Seen(Set.copyOf(roles), now));
            if (removed > 0) log.info("A role gone at sign-in took {} entr{} off the notification lists of entity {}.",
                    removed, removed == 1 ? "y" : "ies", p.tenantId());
        } catch (RuntimeException ex) {
            log.warn("Who holds which list role could not be written for entity {} ({}).", p.tenantId(), ex.getClass().getSimpleName());
        }
    }
}
