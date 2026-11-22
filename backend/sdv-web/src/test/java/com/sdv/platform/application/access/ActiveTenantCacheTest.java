package com.sdv.platform.application.access;

import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantGuardrails;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Task 0039 (owner answer to report 0038, 3): every request checks that the tenant is active, with a short cache. A
 * suspension takes effect within the cache time; inside it the database is not read again. Unknown and suspended give
 * the same answer (SDV-AUT-009).
 */
class ActiveTenantCacheTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant T0 = Instant.parse("2026-11-22T10:00:00Z");

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private void tenant(TenantStatus status) {
        when(tenants.findById(TENANT)).thenReturn(Optional.of(
                new Tenant(TENANT, "acme", "Acme", status, TenantGuardrails.defaults(), T0, T0)));
    }

    @Test
    void aSuspensionTakesEffectWithinTheCacheTimeAndTheDatabaseIsReadOncePerPeriod() {
        ActiveTenantCache cache = new ActiveTenantCache(tenants, Duration.ofSeconds(60), clock);
        tenant(TenantStatus.ACTIVE);
        for (int i = 0; i < 100; i++) cache.requireActive(TENANT);
        verify(tenants, times(1)).findById(TENANT);

        tenant(TenantStatus.SUSPENDED);
        now.set(T0.plusSeconds(59));
        cache.requireActive(TENANT); // still the status seen at T0
        now.set(T0.plusSeconds(60));
        CodedRefusal.Forbidden refused = assertThrows(CodedRefusal.Forbidden.class, () -> cache.requireActive(TENANT));
        assertEquals(SdvErrorCode.AUT_009, refused.errorCode());
        assertEquals(ActiveTenant.MESSAGE, refused.getMessage());
        assertTrue(refused.logDetail().contains("suspended (SUSPENDED)"), refused.logDetail());
        verify(tenants, times(2)).findById(TENANT);

        // And back: reactivated, accepted again within the cache time.
        tenant(TenantStatus.ACTIVE);
        now.set(T0.plusSeconds(121));
        cache.requireActive(TENANT);
    }

    @Test
    void unknownAndSuspendedGetTheSameAnswer() {
        ActiveTenantCache cache = new ActiveTenantCache(tenants, Duration.ZERO, clock);
        tenant(TenantStatus.SUSPENDED);
        var suspended = assertThrows(CodedRefusal.Forbidden.class, () -> cache.requireActive(TENANT));
        when(tenants.findById(TENANT)).thenReturn(Optional.empty());
        var unknown = assertThrows(CodedRefusal.Forbidden.class, () -> cache.requireActive(TENANT));
        assertEquals(suspended.errorCode(), unknown.errorCode());
        assertEquals(suspended.getMessage(), unknown.getMessage());
        assertTrue(unknown.logDetail().contains("missing"));
        assertThrows(CodedRefusal.Forbidden.class, () -> cache.requireActive(null));
    }

    @Test
    void zeroSecondsReadsEveryTimeAndADatabaseFailureIsNeverTakenAsActive() {
        ActiveTenantCache cache = new ActiveTenantCache(tenants, Duration.ZERO, clock);
        tenant(TenantStatus.ACTIVE);
        cache.requireActive(TENANT);
        cache.requireActive(TENANT);
        verify(tenants, times(2)).findById(TENANT);
        when(tenants.findById(TENANT)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> cache.requireActive(TENANT));
    }

    @Test
    void theCacheTimeIsAtMostSixtySeconds() {
        assertThrows(StartupFailure.class, () -> new ActiveTenantCache(tenants, 61));
        assertThrows(StartupFailure.class, () -> new ActiveTenantCache(tenants, -1));
        assertDoesNotThrow(() -> new ActiveTenantCache(tenants, 60));
    }
}
