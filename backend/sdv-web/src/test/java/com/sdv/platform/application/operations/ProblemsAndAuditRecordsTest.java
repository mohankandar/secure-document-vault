package com.sdv.platform.application.operations;

import com.sdv.platform.application.audit.AuditQueryService;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.AuditQueryPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.KeyServiceHealthProbe;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.OperationalRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.storage.StorageVolumeAdminService;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0011 Part C.1, C.3 and C.4: problems carry their record; the audit list carries names, records and file names. */
class ProblemsAndAuditRecordsTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SUB_KNOWN = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SUB_UNKNOWN = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final BusinessRecordRepository records = mock(BusinessRecordRepository.class);
    private final MetadataFieldDefinitionRepository definitions = mock(MetadataFieldDefinitionRepository.class);
    private final IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
    private final ProblemRecordResolver resolver = new ProblemRecordResolver(records, definitions, sources);

    private void knownRecord() {
        IngestionSource source = mock(IngestionSource.class);
        when(source.displayName()).thenReturn("LexaVault");
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(source));
        when(definitions.findDisplayOrder(TENANT, SOURCE)).thenReturn(List.of("memberNumber", "contractNumber", "lastName"));
        when(definitions.findRecordTitleFields(TENANT, SOURCE)).thenReturn(List.of("firstName", "lastName"));
        when(records.findMetadataSnapshots(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new MetadataSnapshot(SUB_KNOWN, "contractNumber", "Contract Number", "300000000090", "300000000090", true),
                new MetadataSnapshot(SUB_KNOWN, "memberNumber", "Member Number", "20000000090", "20000000090", true),
                new MetadataSnapshot(SUB_KNOWN, "firstName", "First Name", "Mia", "MIA", false),
                new MetadataSnapshot(SUB_KNOWN, "lastName", "Last Name", "Patel", "PATEL", false)));
    }

    @Test
    void aProblemCarriesItsRecordNumberTitleAndSendingSystem() {
        knownRecord();
        var found = resolver.resolve(TENANT, Map.of(SUB_KNOWN, SOURCE)).get(SUB_KNOWN);
        assertEquals("20000000090", found.recordNumber(), "the first identifier in display order");
        assertEquals("Member Number", found.recordNumberLabel());
        assertEquals("Mia Patel", found.recordTitle());
        assertEquals("LexaVault", found.sourceName());
    }

    @Test
    void everyLookupStaysInTheProblemsTenant() {
        knownRecord();
        resolver.resolve(TENANT, Map.of(SUB_KNOWN, SOURCE));
        verify(sources, never()).findById(argThat(t -> !TENANT.equals(t)), any());
        verify(records, never()).findMetadataSnapshots(argThat(t -> !TENANT.equals(t)), any(), anyList());
        verify(definitions, never()).findRecordTitleFields(argThat(t -> !TENANT.equals(t)), any());
    }

    @Test
    void aProblemWhoseRecordCantBeResolvedStillAppearsWithoutATitle() {
        knownRecord();
        var unknown = resolver.resolve(TENANT, Map.of(SUB_UNKNOWN, SOURCE)).get(SUB_UNKNOWN);
        assertNotNull(unknown);
        assertNull(unknown.recordNumber());
        assertNull(unknown.recordTitle());
        assertEquals("LexaVault", unknown.sourceName());

        // In the summary: a failing lookup never drops the problem itself.
        OperationalRepository ops = mock(OperationalRepository.class);
        when(ops.databaseAvailable()).thenReturn(true);
        when(ops.countProcessingFailures(TENANT)).thenReturn(1L);
        when(ops.findProcessingFailures(eq(TENANT), anyInt())).thenReturn(List.of(new OperationalRepository.ProcessingFailure(
                UUID.randomUUID(), SUB_UNKNOWN, SOURCE, "DOCUMENT_FINALIZATION_FAILED", 3, Instant.now(), "appraisal.pdf")));
        TenantRepository tenants = mock(TenantRepository.class);
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        ProblemRecordResolver broken = mock(ProblemRecordResolver.class);
        when(broken.resolve(any(), anyMap())).thenThrow(new IllegalStateException("lookup failed"));
        OperationsService service = new OperationsService(ops, mock(StorageVolumeAdminService.class), mock(KeyServiceHealthProbe.class), tenants, broken);
        var summary = service.summary(TENANT);
        assertEquals(1, summary.processingFailures().size());
        assertEquals("appraisal.pdf", summary.processingFailures().getFirst().originalFilename());
        assertTrue(summary.problemRecords().isEmpty());
    }

    @Test
    void theAuditListCarriesNamesRecordsAndFileNamesAndOlderEventsStillList() {
        AuditQueryPort query = mock(AuditQueryPort.class);
        AuditEvent named = new AuditEvent(UUID.randomUUID(), TENANT, "USER", "okta|00u1", "DOCUMENT_VIEW_OPENED", "DOCUMENT",
                UUID.randomUUID().toString(), Instant.parse("2026-11-18T12:00:00Z"), null, AuditOutcome.SUCCESS, "{}", "Maya Okafor");
        AuditEvent older = new AuditEvent(UUID.randomUUID(), TENANT, "USER", "okta|00u2", "BUSINESS_SEARCH", "INGESTION_SOURCE",
                SOURCE.toString(), Instant.parse("2026-11-01T12:00:00Z"), null, AuditOutcome.SUCCESS, "{}");
        when(query.findRows(eq(TENANT), any(), anyInt())).thenReturn(List.of(
                new AuditQueryPort.Row(named, "300000000090", "loan-agreement.pdf"), new AuditQueryPort.Row(older, null, null)));
        AuditQueryService service = new AuditQueryService(query, mock(AuditEventPort.class));

        var page = service.search(TENANT, "ops", new AuditQueryService.SearchRequest(null, null, null, null, null, null, null,
                null, null, null, null, " 300000000090 "), "c");
        assertEquals("Maya Okafor", page.events().get(0).actorDisplayName());
        assertEquals("300000000090", page.events().get(0).recordNumber());
        assertEquals("loan-agreement.pdf", page.events().get(0).filename());
        assertNull(page.events().get(1).actorDisplayName(), "older events have no name; screens show the subject");
        assertEquals("okta|00u2", page.events().get(1).actorId());

        ArgumentCaptor<AuditQueryPort.Filter> filter = ArgumentCaptor.forClass(AuditQueryPort.Filter.class);
        verify(query).findRows(eq(TENANT), filter.capture(), anyInt());
        assertEquals("300000000090", filter.getValue().recordNumber(), "the record-number filter reaches the query, trimmed");
    }
}
