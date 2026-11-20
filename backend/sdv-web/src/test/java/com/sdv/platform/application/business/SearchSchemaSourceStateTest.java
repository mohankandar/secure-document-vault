package com.sdv.platform.application.business;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.SourceActivity;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.source.IngestionSourceAuthType;
import com.sdv.platform.domain.source.IngestionSourceStatus;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0023: each sending system in the search schema says its status, whether it holds any record and when it
 * last delivered, read in one query for the caller's tenant only.
 */
class SearchSchemaSourceStateTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID LOANS = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PAUSED = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID EMPTY = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final Instant LOANS_LAST = Instant.parse("2026-11-19T09:30:00Z");
    private static final Instant PAUSED_LAST = Instant.parse("2026-11-18T13:16:00Z");

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
    private final MetadataFieldDefinitionRepository definitions = mock(MetadataFieldDefinitionRepository.class);
    private final BusinessRecordRepository records = mock(BusinessRecordRepository.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final BusinessExperienceService service = new BusinessExperienceService(
            tenants, sources, definitions, records, audit, Clock.fixed(NOW, ZoneOffset.UTC));

    private static IngestionSource source(UUID id, String name, IngestionSourceStatus status) {
        return new IngestionSource(id, TENANT, name.toLowerCase().replace(' ', '-'), name, status,
                IngestionSourceAuthType.values()[0], null, null, NOW, NOW);
    }

    @BeforeEach
    void setUp() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        when(sources.findAll(TENANT)).thenReturn(List.of(
                source(LOANS, "Loan Origination System", IngestionSourceStatus.ACTIVE),
                source(PAUSED, "Tour test system", IngestionSourceStatus.DISABLED),
                source(EMPTY, "Empty system", IngestionSourceStatus.ACTIVE)));
        when(definitions.findCurrent(any(), any())).thenReturn(List.of());
        when(definitions.findDisplayOrder(any(), any())).thenReturn(List.of());
        // A system with no record has no row value (max() over nothing is null).
        when(records.findSourceActivity(TENANT)).thenReturn(List.of(
                new SourceActivity(LOANS, LOANS_LAST),
                new SourceActivity(PAUSED, PAUSED_LAST),
                new SourceActivity(EMPTY, null)));
    }

    private Map<UUID, BusinessExperienceView.SourceSchema> schema() {
        return service.searchSchema(TENANT).sources().stream()
                .collect(Collectors.toMap(BusinessExperienceView.SourceSchema::sourceId, Function.identity()));
    }

    @Test
    void anActiveSystemWithRecords_saysActive_hasRecords_andWhenItLastDelivered() {
        BusinessExperienceView.SourceSchema loans = schema().get(LOANS);
        assertEquals("ACTIVE", loans.status());
        assertTrue(loans.hasRecords());
        assertEquals(LOANS_LAST, loans.lastDeliveredAt());
    }

    @Test
    void aPausedSystem_saysPaused_andStillSaysItsRecordsAndLastDelivery() {
        BusinessExperienceView.SourceSchema paused = schema().get(PAUSED);
        assertEquals("PAUSED", paused.status());
        assertTrue(paused.hasRecords());
        assertEquals(PAUSED_LAST, paused.lastDeliveredAt());
    }

    @Test
    void aSystemWithNoRecords_hasNoRecords_andNoLastDelivery() {
        BusinessExperienceView.SourceSchema empty = schema().get(EMPTY);
        assertEquals("ACTIVE", empty.status());
        assertFalse(empty.hasRecords());
        assertNull(empty.lastDeliveredAt());
    }

    @Test
    void aSystemMissingFromTheActivityResult_isTreatedAsHoldingNoRecords() {
        when(records.findSourceActivity(TENANT)).thenReturn(List.of(new SourceActivity(LOANS, LOANS_LAST)));
        BusinessExperienceView.SourceSchema paused = schema().get(PAUSED);
        assertFalse(paused.hasRecords());
        assertNull(paused.lastDeliveredAt());
    }

    @Test
    void oneActivityQueryForAllSystems_neverOnePerSystem() {
        schema();
        verify(records, times(1)).findSourceActivity(TENANT);
        verify(records, never()).findMatchingSubmissions(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    void tenantIsolation_onlyTheCallersTenantIsRead_andAnotherTenantsActivityIsNeverShown() {
        // Even if the port returned a row for a system of another tenant, only the caller's own systems are listed.
        UUID foreign = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        when(records.findSourceActivity(TENANT)).thenReturn(List.of(
                new SourceActivity(LOANS, LOANS_LAST), new SourceActivity(foreign, NOW)));
        Map<UUID, BusinessExperienceView.SourceSchema> schema = schema();
        assertEquals(3, schema.size());
        assertFalse(schema.containsKey(foreign));
        verify(records).findSourceActivity(TENANT);
        verify(records, never()).findSourceActivity(OTHER_TENANT);
        verify(sources, never()).findAll(OTHER_TENANT);
    }

}
