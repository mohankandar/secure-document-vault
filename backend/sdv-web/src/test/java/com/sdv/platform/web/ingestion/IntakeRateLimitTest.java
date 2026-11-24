package com.sdv.platform.web.ingestion;

import com.sdv.platform.domain.error.BusyFailure;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0071. */
class IntakeRateLimitTest {
    private final AtomicLong nanos = new AtomicLong();
    private final IntakeRateLimit limit = new IntakeRateLimit(60, nanos::get);

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private int taken(UUID source, int asked) {
        int n = 0;
        for (int i = 0; i < asked; i++) if (limit.take(source)) n++;
        return n;
    }

    @Test
    void aMinutesWorthAtOnceThenAsFastAsItRefillsAndEachSystemForItself() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertEquals(60, taken(a, 200));
        assertEquals(60, taken(b, 200), "another system is not touched");
        nanos.addAndGet(10_000_000_000L);
        assertEquals(10, taken(a, 200));
        nanos.addAndGet(3_600_000_000_000L);
        assertEquals(60, taken(a, 200), "never more than a minute's worth");
    }

    @Test
    void overItTheRequestIsBusyAndPeopleAreNotCounted() {
        UUID source = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new IngestionSourcePrincipal(UUID.randomUUID(), source, "loans"), null, List.of()));
        taken(source, 60);
        assertThrows(BusyFailure.class, () -> limit.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), this));
        SecurityContextHolder.clearContext();
        assertTrue(limit.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), this), "nobody signed in: the sign-in answers");
    }

    @Test
    void aSettingThatMakesNoSenseStopsTheStart() {
        for (int bad : new int[] {0, -1, 59, 1_000_001}) assertThrows(StartupFailure.class, () -> new IntakeRateLimit(bad, nanos::get));
    }
}
