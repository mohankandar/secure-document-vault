package com.sdv.platform.application.retention;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RetentionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantGuardrails;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0019 follow-up (migration 022): the person's name is kept with each new retention rule. */
class RetentionPolicyCreatorNameTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final RetentionRepository retention = mock(RetentionRepository.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final RetentionPolicyService service =
            new RetentionPolicyService(tenants, retention, audit, mock(PlatformTransactionManager.class));

    private RetentionRepository.PolicyVersion row(int version, String createdBy, String name, Instant until) {
        return new RetentionRepository.PolicyVersion(UUID.randomUUID(), TENANT, version, "Rule " + version, null,
                365, T0, until, createdBy, name, T0);
    }

    private RetentionRepository.PolicyVersion saveAs(String displayName) {
        when(tenants.findById(TENANT)).thenReturn(Optional.of(new Tenant(TENANT, "acme", "Acme", TenantStatus.ACTIVE,
                TenantGuardrails.defaults(), T0, T0)));
        when(retention.nextPolicyVersion(TENANT)).thenReturn(2);
        RetentionRepository.PolicyVersion stored = row(2, "priya-sub", displayName, null);
        when(retention.findCurrentPolicy(TENANT)).thenReturn(Optional.of(stored));
        when(retention.findPolicyHistory(eq(TENANT), anyInt())).thenReturn(List.of(stored));
        service.update(TENANT, "Keep for 1 year", "why", 365, "priya-sub", displayName, "corr");
        ArgumentCaptor<RetentionRepository.PolicyVersion> inserted = ArgumentCaptor.forClass(RetentionRepository.PolicyVersion.class);
        verify(retention).insertPolicyVersion(inserted.capture());
        return inserted.getValue();
    }

    @Test
    void aNewRuleKeepsTheSignedInPersonsNameNextToTheirSignInId() {
        RetentionRepository.PolicyVersion inserted = saveAs("  Priya Natarajan ");
        assertEquals("priya-sub", inserted.createdBy());
        assertEquals("Priya Natarajan", inserted.createdByDisplayName());
    }

    @Test
    void aBlankOrMissingNameIsNotStored() {
        assertNull(saveAs("   ").createdByDisplayName());
    }

    @Test
    void anOverLongNameIsNotStored() {
        assertNull(RetentionPolicyService.displayName("x".repeat(301)));
        assertEquals("x".repeat(300), RetentionPolicyService.displayName("x".repeat(300)));
        assertNull(RetentionPolicyService.displayName(null));
    }

    @Test
    void theOverviewCarriesTheNameForTheRuleInUseAndEachEarlierRuleAndNullForOlderRules() {
        when(tenants.findById(TENANT)).thenReturn(Optional.of(new Tenant(TENANT, "acme", "Acme", TenantStatus.ACTIVE,
                TenantGuardrails.defaults(), T0, T0)));
        RetentionRepository.PolicyVersion current = row(3, "priya-sub", "Priya Natarajan", null);
        RetentionRepository.PolicyVersion older = row(2, "old-sub", null, T0);
        when(retention.findCurrentPolicy(TENANT)).thenReturn(Optional.of(current));
        when(retention.findPolicyHistory(eq(TENANT), anyInt())).thenReturn(List.of(current, older));

        RetentionPolicyService.RetentionPolicyOverview overview = service.current(TENANT);
        assertEquals("Priya Natarajan", overview.createdByDisplayName());
        assertEquals("Priya Natarajan", overview.history().get(0).createdByDisplayName());
        assertNull(overview.history().get(1).createdByDisplayName());
        assertEquals("old-sub", overview.history().get(1).createdBy());
    }
}
