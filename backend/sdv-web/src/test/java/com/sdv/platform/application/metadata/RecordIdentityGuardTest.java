package com.sdv.platform.application.metadata;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.RecordSensitivityRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0024, part 2, section 4: while any record of a sending system has a level, changing which fields are its
 * identifiers, or how an identifier is normalized, is refused (it would put later documents in a record without the
 * level). The three places a field definition can change are the three service methods below.
 */
class RecordIdentityGuardTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final MetadataFieldDefinitionRepository definitions = mock(MetadataFieldDefinitionRepository.class);
    private final RecordSensitivityRepository levels = mock(RecordSensitivityRepository.class);
    private MetadataFieldAdminService service;

    @BeforeEach
    void setUp() {
        TenantRepository tenants = mock(TenantRepository.class);
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(mock(IngestionSource.class)));
        service = new MetadataFieldAdminService(tenants, sources, definitions, levels, mock(AuditEventPort.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        current("contractNumber", true, MetadataNormalization.DIGITS_ONLY);
        current("lastName", false, MetadataNormalization.TRIM);
    }

    private void current(String key, boolean identifier, MetadataNormalization normalization) {
        when(definitions.findCurrent(TENANT, SOURCE, key)).thenReturn(Optional.of(new MetadataFieldDefinition(
                UUID.randomUUID(), TENANT, SOURCE, key, 1, key, MetadataFieldType.TEXT, false, identifier, true,
                normalization, MetadataSearchMode.EXACT, "{}", true, NOW, null, NOW)));
    }

    private Executable fieldAdmin(String key, boolean identifier, MetadataNormalization normalization) {
        return () -> service.createOrRevise(TENANT, SOURCE, key, new UpsertMetadataFieldCommand(key, MetadataFieldType.TEXT,
                false, identifier, true, normalization, MetadataSearchMode.EXACT, "{}"), "kr", "corr");
    }

    private Executable businessAdmin(String key, boolean identifier, MetadataNormalization normalization) {
        return () -> service.reviseBusinessBehavior(TENANT, SOURCE, key,
                new BusinessMetadataBehaviorCommand("Label", identifier, true, normalization, MetadataSearchMode.EXACT), "kr", "corr");
    }

    private Executable sourceField(String key, MetadataNormalization normalization) {
        return () -> service.createOrReviseSourceField(TENANT, SOURCE, key,
                new SourceFieldContractCommand("Label", MetadataFieldType.TEXT, false, normalization, "{}"), "kr", "corr");
    }

    private void assertRefused(Executable change, long count, String expectedCount) {
        clearInvocations(definitions);
        var ex = assertThrows(com.sdv.platform.domain.error.CodedRefusal.Conflict.class, change);
        assertEquals("SDV-SEN-007", ex.errorCode().code());
        assertTrue(ex.getMessage().contains(expectedCount + " of this sending system"), ex.getMessage());
        assertTrue(ex.getMessage().contains("sensitivity level"), ex.getMessage());
        verify(definitions, never()).insert(any());
    }

    @Test
    void everyIdentityChangeIsRefusedWhileRecordLevelsExist() {
        when(levels.countForSource(TENANT, SOURCE)).thenReturn(3L);
        // Identifier switched off, on, normalization changed, and a new identifier field, on each path that can do it.
        assertRefused(fieldAdmin("contractNumber", false, MetadataNormalization.DIGITS_ONLY), 3, "3 records");
        assertRefused(fieldAdmin("lastName", true, MetadataNormalization.TRIM), 3, "3 records");
        assertRefused(fieldAdmin("contractNumber", true, MetadataNormalization.TRIM), 3, "3 records");
        assertRefused(fieldAdmin("newId", true, MetadataNormalization.TRIM), 3, "3 records");
        assertRefused(businessAdmin("contractNumber", false, MetadataNormalization.DIGITS_ONLY), 3, "3 records");
        assertRefused(businessAdmin("lastName", true, MetadataNormalization.TRIM), 3, "3 records");
        assertRefused(businessAdmin("contractNumber", true, MetadataNormalization.NONE), 3, "3 records");
        assertRefused(sourceField("contractNumber", MetadataNormalization.TRIM), 3, "3 records");

        when(levels.countForSource(TENANT, SOURCE)).thenReturn(1L);
        assertRefused(fieldAdmin("contractNumber", false, MetadataNormalization.DIGITS_ONLY), 1, "1 record");
    }

    @Test
    void changesThatKeepTheGroupingStayAllowedWhileRecordLevelsExist() {
        when(levels.countForSource(TENANT, SOURCE)).thenReturn(3L);
        assertDoesNotThrow(fieldAdmin("contractNumber", true, MetadataNormalization.DIGITS_ONLY));
        assertDoesNotThrow(businessAdmin("contractNumber", true, MetadataNormalization.DIGITS_ONLY));
        assertDoesNotThrow(sourceField("contractNumber", MetadataNormalization.DIGITS_ONLY));
        // A non-identifier field's normalization doesn't take part in the grouping.
        assertDoesNotThrow(businessAdmin("lastName", false, MetadataNormalization.UPPERCASE));
        assertDoesNotThrow(sourceField("lastName", MetadataNormalization.LOWERCASE));
        assertDoesNotThrow(sourceField("brandNew", MetadataNormalization.TRIM));
        verify(definitions, times(6)).insert(any());
    }

    @Test
    void identityChangesAreAllowedWhenNoRecordHasALevel() {
        when(levels.countForSource(TENANT, SOURCE)).thenReturn(0L);
        assertDoesNotThrow(fieldAdmin("contractNumber", false, MetadataNormalization.DIGITS_ONLY));
        assertDoesNotThrow(businessAdmin("lastName", true, MetadataNormalization.TRIM));
        assertDoesNotThrow(sourceField("contractNumber", MetadataNormalization.TRIM));
        assertDoesNotThrow(fieldAdmin("newId", true, MetadataNormalization.TRIM));
        verify(definitions, times(4)).insert(any());
    }
}
