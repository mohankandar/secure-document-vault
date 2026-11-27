package com.sdv.platform.web.notifications;

import com.sdv.platform.application.notifications.RoleHolderService;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.SdvPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 0100 (VSEC-203): each sign-in's list roles reach the service, only when they changed or grew old. */
class RoleHolderFilterTest {
    final RoleHolderService service = mock(RoleHolderService.class);
    final PlatformIdentityReads identity = mock(PlatformIdentityReads.class);
    final MovingClock clock = new MovingClock();
    final RoleHolderFilter filter;

    @SuppressWarnings("unchecked")
    RoleHolderFilterTest() {
        ObjectProvider<RoleHolderService> s = mock(ObjectProvider.class);
        ObjectProvider<PlatformIdentityReads> i = mock(ObjectProvider.class);
        when(s.getIfAvailable()).thenReturn(service);
        when(i.getIfAvailable()).thenReturn(identity);
        when(identity.slotOf(NotificationKit.ENTITY)).thenReturn(Optional.of(new PlatformIdentityReads.EntitySlot(NotificationKit.CUSTOMER, "ENT-A", "ACTIVE", "A")));
        when(service.seen(any(), any(), any(), any())).thenReturn(List.of());
        filter = new RoleHolderFilter(s, i, clock);
    }

    @Test
    void writtenOnChangeAndAgainOnlyWhenOld() {
        SdvPrincipal admin = new SdvPrincipal(NotificationKit.ENTITY, "ba-1", Set.of(SdvRole.BUSINESS_ADMIN));
        filter.note(admin);
        filter.note(admin);
        verify(service, times(1)).seen(NotificationKit.CUSTOMER, NotificationKit.ENTITY, "ba-1", Set.of("BUSINESS_ADMIN"));
        filter.note(new SdvPrincipal(NotificationKit.ENTITY, "ba-1", Set.of(SdvRole.BUSINESS_USER)));
        verify(service).seen(NotificationKit.CUSTOMER, NotificationKit.ENTITY, "ba-1", Set.of("BUSINESS_USER"));
        clock.now = clock.now.plus(RoleHolderFilter.FRESH).plusSeconds(1);
        filter.note(new SdvPrincipal(NotificationKit.ENTITY, "ba-1", Set.of(SdvRole.BUSINESS_USER)));
        verify(service, times(2)).seen(NotificationKit.CUSTOMER, NotificationKit.ENTITY, "ba-1", Set.of("BUSINESS_USER"));
    }

    @Test
    void aFailureIsTriedAgainOnTheNextRequest() {
        when(service.seen(any(), any(), eq("ba-2"), any())).thenThrow(new IllegalStateException("database away"));
        SdvPrincipal admin = new SdvPrincipal(NotificationKit.ENTITY, "ba-2", Set.of(SdvRole.BUSINESS_ADMIN));
        filter.note(admin);
        filter.note(admin);
        verify(service, times(2)).seen(any(), any(), eq("ba-2"), any());
    }

    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-11-27T12:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
