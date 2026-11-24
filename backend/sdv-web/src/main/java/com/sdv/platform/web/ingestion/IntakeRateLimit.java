package com.sdv.platform.web.ingestion;

import com.sdv.platform.domain.error.BusyFailure;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Task 0071: how many intake requests one sending system may make in a minute, in this Vero process. Over it: the busy
 * answer (503 SDV-SYS-018 with Retry-After), before anything of the request is read. A bucket that refills steadily, as
 * large as a minute's worth, so a burst after a quiet time is fine.
 */
@Component
public class IntakeRateLimit implements HandlerInterceptor, WebMvcConfigurer {
    static final int MIN = 60;
    static final int MAX = 1_000_000;

    private static final class Bucket {
        double left;
        long at;
    }

    private final int perMinute;
    private final LongSupplier nanos;
    /** One per sending system that signed in: the number is bounded by the systems that exist. */
    private final ConcurrentHashMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public IntakeRateLimit(@Value("${sdv.limits.intake-requests-per-minute:6000}") int perMinute) {
        this(perMinute, System::nanoTime);
    }

    IntakeRateLimit(int perMinute, LongSupplier nanos) {
        if (perMinute < MIN || perMinute > MAX) {
            throw new StartupFailure(SdvErrorCode.SYS_005, "sdv.limits.intake-requests-per-minute (SDV_INTAKE_REQUESTS_PER_MINUTE) must be from " + MIN + " to " + MAX + ".");
        }
        this.perMinute = perMinute;
        this.nanos = nanos;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/ingestion/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication who = SecurityContextHolder.getContext().getAuthentication();
        if (who != null && who.getPrincipal() instanceof IngestionSourcePrincipal source && !take(source.sourceId())) {
            throw new BusyFailure("sending system " + source.sourceId() + " is over " + perMinute + " requests a minute");
        }
        return true;
    }

    boolean take(UUID sourceId) {
        Bucket bucket = buckets.computeIfAbsent(sourceId, id -> {
            Bucket b = new Bucket();
            b.left = perMinute;
            b.at = nanos.getAsLong();
            return b;
        });
        synchronized (bucket) {
            long now = nanos.getAsLong();
            bucket.left = Math.min(perMinute, bucket.left + (now - bucket.at) / 60e9 * perMinute);
            bucket.at = now;
            if (bucket.left < 1) return false;
            bucket.left -= 1;
            return true;
        }
    }
}
