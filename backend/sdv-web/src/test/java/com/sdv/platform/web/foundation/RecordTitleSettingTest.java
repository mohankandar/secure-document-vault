package com.sdv.platform.web.foundation;

import com.sdv.platform.application.metadata.MetadataFieldAdminService;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.source.IngestionSourceAdminService;
import com.sdv.platform.application.source.SourceOperationsService;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0009 Part C: only CONFIGURE_METADATA_SEARCH changes the Record title; the setting is checked and audited. */
class RecordTitleSettingTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final RoleCapabilityPolicyPort overrides = mock(RoleCapabilityPolicyPort.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final IngestionSourceRepository sourceRepository = mock(IngestionSourceRepository.class);
    private final MetadataFieldDefinitionRepository definitions = mock(MetadataFieldDefinitionRepository.class);
    private final MetadataFieldAdminService metadata = new MetadataFieldAdminService(tenants, sourceRepository, definitions,
            mock(com.sdv.platform.application.port.RecordSensitivityRepository.class), audit);
    private final FoundationAdminController controller = new FoundationAdminController(
            new SecurityContextFacade(),
            new AuthorizationService(audit, new CapabilityPolicyService(overrides)),
            mock(IngestionSourceAdminService.class), metadata, mock(SourceOperationsService.class));

    private static MetadataFieldDefinition field(String key, boolean identifier) {
        Instant t = Instant.parse("2026-11-01T00:00:00Z");
        return new MetadataFieldDefinition(UUID.randomUUID(), TENANT, SOURCE, key, 1, key, MetadataFieldType.TEXT,
                false, identifier, true, MetadataNormalization.NONE, MetadataSearchMode.EXACT, "{}", true, t, null, t);
    }

    @BeforeEach
    void setUp() {
        when(overrides.findOverrides(any(), anyString())).thenReturn(Map.of());
        com.sdv.platform.domain.tenant.Tenant tenant = mock(com.sdv.platform.domain.tenant.Tenant.class);
        when(tenant.status()).thenReturn(com.sdv.platform.domain.tenant.TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        when(sourceRepository.findById(TENANT, SOURCE)).thenReturn(Optional.of(mock(IngestionSource.class)));
        when(definitions.findCurrent(TENANT, SOURCE)).thenReturn(List.of(
                field("contractNumber", true), field("firstName", false), field("lastName", false)));
        when(definitions.findRecordTitleFields(TENANT, SOURCE)).thenReturn(List.of("firstName", "lastName"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(SdvRole role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SdvPrincipal(TENANT, "person-" + role.name().toLowerCase(), Set.of(role)), null, List.of()));
    }

    private static FoundationAdminController.MetadataDisplayOrderRequest keys(String... keys) {
        return new FoundationAdminController.MetadataDisplayOrderRequest(List.of(keys));
    }

    @Test
    void aBusinessAdminCanChangeItAndTheChangeIsAudited() {
        signIn(SdvRole.BUSINESS_ADMIN);
        assertEquals(List.of("firstName", "lastName"), controller.updateRecordTitleFields(SOURCE, keys("firstName", "lastName")));
        verify(definitions).replaceRecordTitleFields(TENANT, SOURCE, List.of("firstName", "lastName"));
        verify(audit).append(argThat(e -> e.action().equals("RECORD_TITLE_FIELDS_UPDATED")
                && e.targetId().equals(SOURCE.toString()) && e.safeDetailsJson().equals("{\"fieldCount\":2}")));
    }

    @Test
    void everyOtherRoleIsRefusedAndNothingIsStored() {
        for (SdvRole role : List.of(SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD, SdvRole.IT_ADMIN, SdvRole.PLATFORM_OPERATIONS_ADMIN)) {
            signIn(role);
            assertThrows(AccessDeniedException.class, () -> controller.updateRecordTitleFields(SOURCE, keys("firstName")), role.name());
            assertThrows(AccessDeniedException.class, () -> controller.getRecordTitleFields(SOURCE), role.name());
        }
        verify(definitions, never()).replaceRecordTitleFields(any(), any(), anyList());
        verify(audit, never()).append(argThat(e -> e.action().equals("RECORD_TITLE_FIELDS_UPDATED")));
    }

    @Test
    void unclearSettingsAreRefused() {
        signIn(SdvRole.BUSINESS_ADMIN);
        assertThrows(IllegalArgumentException.class, () -> controller.updateRecordTitleFields(SOURCE, keys("contractNumber")), "identifier");
        assertThrows(IllegalArgumentException.class, () -> controller.updateRecordTitleFields(SOURCE, keys("firstName", "lastName", "contractNumber")), "three fields");
        assertThrows(IllegalArgumentException.class, () -> controller.updateRecordTitleFields(SOURCE, keys("firstName", "firstName")), "twice");
        assertThrows(IllegalArgumentException.class, () -> controller.updateRecordTitleFields(SOURCE, keys("middleName")), "unknown");
        assertThrows(IllegalArgumentException.class, () -> controller.updateRecordTitleFields(SOURCE, null), "missing body");
        verify(definitions, never()).replaceRecordTitleFields(any(), any(), anyList());
    }

    @Test
    void anEmptyListGoesBackToTheDefault() {
        signIn(SdvRole.BUSINESS_ADMIN);
        controller.updateRecordTitleFields(SOURCE, keys());
        verify(definitions).replaceRecordTitleFields(TENANT, SOURCE, List.of());
    }
}
