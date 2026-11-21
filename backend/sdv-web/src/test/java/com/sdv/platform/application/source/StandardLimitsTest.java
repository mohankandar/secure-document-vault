package com.sdv.platform.application.source;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.source.SourceLimits;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantGuardrails;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Task 0019 follow-up: the standard limits Integrations shows when a sending system sets none of its own. */
class StandardLimitsTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final IngestionSourceAdminService service =
            new IngestionSourceAdminService(tenants, mock(IngestionSourceRepository.class), mock(AuditEventPort.class));

    private void tenant(TenantStatus status, TenantGuardrails g) {
        when(tenants.findById(TENANT)).thenReturn(Optional.of(new Tenant(TENANT, "acme", "Acme", status, g, T0, T0)));
    }

    @Test
    void theStandardIsTheTenantsGuardrails() {
        tenant(TenantStatus.ACTIVE, new TenantGuardrails(50L * 1024 * 1024, 500, 20, 1024L * 1024 * 1024, 48, 90, 95, null));
        assertEquals(new SourceLimits(50L * 1024 * 1024, 500, 20, 1024L * 1024 * 1024, 48), service.standardLimits(TENANT));
    }

    @Test
    void anInactiveOrUnknownTenantIsRefused() {
        tenant(TenantStatus.SUSPENDED, TenantGuardrails.defaults());
        // Task 0038: one answer for both (SDV-AUT-009), so it never tells whether the tenant exists.
        var suspended = assertThrows(com.sdv.platform.domain.error.CodedRefusal.Forbidden.class, () -> service.standardLimits(TENANT));
        when(tenants.findById(TENANT)).thenReturn(Optional.empty());
        var unknown = assertThrows(com.sdv.platform.domain.error.CodedRefusal.Forbidden.class, () -> service.standardLimits(TENANT));
        assertEquals(com.sdv.platform.domain.error.SdvErrorCode.AUT_009, suspended.errorCode());
        assertEquals(suspended.errorCode(), unknown.errorCode());
        assertEquals(suspended.getMessage(), unknown.getMessage());
    }
}
