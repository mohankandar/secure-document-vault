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
    void aProblemCarriesItsRecordNumberAndSendingSystemButNoTitle() {
        knownRecord();
        var found = resolver.resolve(TENANT, Map.of(SUB_KNOWN, SOURCE)).get(SUB_KNOWN);
        assertEquals("20000000090", found.recordNumber(), "the first identifier in display order");
        assertEquals("Member Number", found.recordNumberLabel());
        // Task 0028, 0.1: admin views never carry the record title; the title fields are not even read.
        verify(definitions, never()).findRecordTitleFields(any(), any());
        assertEquals("LexaVault", found.sourceName());
    }

    @Test
    void everyLookupStaysInTheProblemsTenant() {
        knownRecord();
        resolver.resolve(TENANT, Map.of(SUB_KNOWN, SOURCE));
        verify(sources, never()).findById(argThat(t -> !TENANT.equals(t)), any());
        verify(records, never()).findMetadataSnapshots(argThat(t -> !TENANT.equals(t)), any(), anyList());

    }

    @Test
    void aProblemWhoseRecordCantBeResolvedStillAppearsWithoutATitle() {
        knownRecord();
        var unknown = resolver.resolve(TENANT, Map.of(SUB_UNKNOWN, SOURCE)).get(SUB_UNKNOWN);
        assertNotNull(unknown);
        assertNull(unknown.recordNumber());

        assertEquals("LexaVault", unknown.sourceName());

        // In the summary: a failing lookup never drops the problem itself.
        OperationalRepository ops = mock(OperationalRepository.class);
        when(ops.databaseAvailable()).thenReturn(true);
        when(ops.countProcessingFailures(TENANT)).thenReturn(1L);
        when(ops.findProcessingFailures(eq(TENANT), anyInt())).thenReturn(List.of(new OperationalRepository.ProcessingFailure(
                UUID.randomUUID(), SUB_UNKNOWN, SOURCE, "DOCUMENT_FINALIZATION_FAILED", 3, Instant.now(), 12,
                com.sdv.platform.domain.document.SensitivityLevel.STANDARD)));
        TenantRepository tenants = mock(TenantRepository.class);
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        ProblemRecordResolver broken = mock(ProblemRecordResolver.class);
        when(broken.resolve(any(), anyMap())).thenThrow(new IllegalStateException("lookup failed"));
        OperationsService service = new OperationsService(ops, mock(StorageVolumeAdminService.class), mock(KeyServiceHealthProbe.class), tenants, broken,
                guard());
        var summary = service.summary(TENANT);
        assertEquals(1, summary.processingFailures().size());
        assertEquals(12, summary.processingFailures().getFirst().pageCount());
        assertEquals(com.sdv.platform.domain.document.SensitivityLevel.STANDARD, summary.processingFailures().getFirst().sensitivityLevel());
        assertTrue(summary.problemRecords().isEmpty());
    }

    /**
     * Task 0028, 0.1: the Problems summary, read by IT Admins and Platform Ops, carries the record's primary identifier,
     * its label and the sending system, and never the record title: no field named like a title, and none of the
     * title fields' values (first and last name here) anywhere in the JSON.
     */
    @Test
    void theProblemsSummaryNeverCarriesARecordTitle() throws Exception {
        knownRecord();
        OperationalRepository ops = mock(OperationalRepository.class);
        when(ops.databaseAvailable()).thenReturn(true);
        when(ops.countProcessingFailures(TENANT)).thenReturn(1L);
        when(ops.findProcessingFailures(eq(TENANT), anyInt())).thenReturn(List.of(new OperationalRepository.ProcessingFailure(
                UUID.randomUUID(), SUB_KNOWN, SOURCE, "DOCUMENT_FINALIZATION_FAILED", 1, Instant.now(), 3,
                com.sdv.platform.domain.document.SensitivityLevel.STANDARD)));
        TenantRepository tenants = mock(TenantRepository.class);
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        OperationsService service = new OperationsService(ops, mock(StorageVolumeAdminService.class),
                mock(KeyServiceHealthProbe.class), tenants, resolver, guard());

        var summary = service.summary(TENANT);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(summary);
        com.fasterxml.jackson.databind.JsonNode record = mapper.readTree(json).at("/problemRecords/" + SUB_KNOWN);
        assertEquals(java.util.Set.of("recordNumber", "recordNumberLabel", "sourceName"),
                new java.util.TreeSet<>(iterable(record.fieldNames())), "exactly these fields");
        assertEquals("20000000090", record.get("recordNumber").asText());
        assertFalse(json.toLowerCase().contains("title"), json);
        assertFalse(json.contains("Mia"), json);
        assertFalse(json.contains("Patel"), json);
    }

    /**
     * Task 0039: Problems lists intake attempts with no known sending system (this tenant's and those without one), the
     * last 7 days, at most 50; a read failure marks the section degraded and never hides the rest of Problems.
     */
    @Test
    void problemsCarryTheUnidentifiedIntakeAttempts() {
        OperationalRepository ops = mock(OperationalRepository.class);
        when(ops.databaseAvailable()).thenReturn(true);
        TenantRepository tenants = mock(TenantRepository.class);
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        var attempts = mock(com.sdv.platform.application.port.UnidentifiedIntakeAttemptRepository.class);
        var one = com.sdv.platform.application.port.UnidentifiedIntakeAttemptRepository.Attempt.one(UUID.randomUUID(), null,
                Instant.now(), "203.0.113.9", "POST", "/api/v1/ingestion/submissions", 401, "SDV-AUT-003", "ref-1");
        when(attempts.count(eq(TENANT), any())).thenReturn(12L);
        when(attempts.findRecent(eq(TENANT), any(), eq(50))).thenReturn(List.of(one));
        OperationsService service = new OperationsService(ops, mock(StorageVolumeAdminService.class),
                mock(KeyServiceHealthProbe.class), tenants, resolver, guard(), attempts);

        var summary = service.summary(TENANT);
        assertEquals(12L, summary.unidentifiedIntakeAttemptCount());
        assertEquals(List.of(one), summary.unidentifiedIntakeAttempts());
        var since = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(attempts).count(eq(TENANT), since.capture());
        long days = java.time.Duration.between(since.getValue(), Instant.now()).toDays();
        assertEquals(7, days, "the last 7 days");

        when(attempts.count(eq(TENANT), any())).thenThrow(new IllegalStateException("down"));
        var degraded = service.summary(TENANT);
        assertEquals(List.of(), degraded.unidentifiedIntakeAttempts());
        assertTrue(degraded.dataObservations().stream().anyMatch(o -> o.section().equals("unidentified-intake-attempts") && o.degraded()));
    }

    /**
     * Task 0040 part 2 (owner answer 4 to report 0040 part 2): Problems carries the count of busy answers (503
     * SDV-SYS-018), in the last hour and since start, so sustained busy is visible to Tenant IT. None without a count.
     */
    @Test
    void problemsCarryTheBusyAnswerCount() throws Exception {
        OperationalRepository ops = mock(OperationalRepository.class);
        when(ops.databaseAvailable()).thenReturn(true);
        TenantRepository tenants = mock(TenantRepository.class);
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        BusyAnswers busy = new BusyAnswers("7");
        busy.record();
        busy.record();
        OperationsService service = new OperationsService(ops, mock(StorageVolumeAdminService.class),
                mock(KeyServiceHealthProbe.class), tenants, resolver, guard(), null, busy);

        var summary = service.summary(TENANT);
        assertEquals(2, summary.busyAnswers().lastHour());
        assertEquals(2, summary.busyAnswers().sinceStart());
        assertEquals(7, summary.busyAnswers().retryAfterSeconds());
        assertNotNull(summary.busyAnswers().lastAt());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .readTree(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(summary));
        assertEquals(2, json.at("/busyAnswers/lastHour").asLong());
        assertEquals(2, json.at("/busyAnswers/sinceStart").asLong());

        OperationsService without = new OperationsService(ops, mock(StorageVolumeAdminService.class),
                mock(KeyServiceHealthProbe.class), tenants, resolver, guard());
        assertEquals(BusyAnswers.Count.NONE, without.summary(TENANT).busyAnswers());
    }

    private static java.util.List<String> iterable(java.util.Iterator<String> names) {
        java.util.List<String> list = new java.util.ArrayList<>();
        names.forEachRemaining(list::add);
        return list;
    }

    /** Task 0026: a real guard; with no record level a Standard document stays Standard. */
    private static com.sdv.platform.application.access.DocumentAccessGuard guard() {
        return new com.sdv.platform.application.access.DocumentAccessGuard(
                mock(com.sdv.platform.application.port.BusinessRecordRepository.class), mock(AuditEventPort.class));
    }

    @Test
    void theAuditListCarriesNamesRecordsAndDocumentFactsAndOlderEventsStillList() {
        AuditQueryPort query = mock(AuditQueryPort.class);
        AuditEvent named = new AuditEvent(UUID.randomUUID(), TENANT, "USER", "okta|00u1", "DOCUMENT_VIEW_OPENED", "DOCUMENT",
                UUID.randomUUID().toString(), Instant.parse("2026-11-18T12:00:00Z"), null, AuditOutcome.SUCCESS, "{}", "Maya Okafor");
        AuditEvent older = new AuditEvent(UUID.randomUUID(), TENANT, "USER", "okta|00u2", "BUSINESS_SEARCH", "INGESTION_SOURCE",
                SOURCE.toString(), Instant.parse("2026-11-01T12:00:00Z"), null, AuditOutcome.SUCCESS, "{}");
        when(query.findRows(eq(TENANT), any(), anyInt())).thenReturn(List.of(
                new AuditQueryPort.Row(named, "300000000090",
                        new com.sdv.platform.application.access.DocumentAccessGuard.DocumentLevelRef(UUID.fromString(named.targetId()),
                                SOURCE, UUID.randomUUID(), com.sdv.platform.domain.document.SensitivityLevel.STANDARD),
                        Instant.parse("2026-11-14T09:30:00Z"), 4), new AuditQueryPort.Row(older, null)));
        AuditQueryService service = new AuditQueryService(query, mock(AuditEventPort.class), guard());

        var page = service.search(TENANT, "ops", new AuditQueryService.SearchRequest(null, null, null, null, null, null, null,
                null, null, null, null, " 300000000090 "), "c");
        assertEquals("Maya Okafor", page.events().get(0).actorDisplayName());
        assertEquals("300000000090", page.events().get(0).recordNumber());
        assertEquals(UUID.fromString(named.targetId()), page.events().get(0).documentId());
        assertEquals(com.sdv.platform.domain.document.SensitivityLevel.STANDARD, page.events().get(0).sensitivityLevel());
        assertEquals(Instant.parse("2026-11-14T09:30:00Z"), page.events().get(0).documentReceivedAt());
        assertEquals(4, page.events().get(0).documentPageCount());
        assertNull(page.events().get(1).documentId(), "an event without a document names none");
        assertNull(page.events().get(1).actorDisplayName(), "older events have no name; screens show the subject");
        assertEquals("okta|00u2", page.events().get(1).actorId());

        ArgumentCaptor<AuditQueryPort.Filter> filter = ArgumentCaptor.forClass(AuditQueryPort.Filter.class);
        verify(query).findRows(eq(TENANT), filter.capture(), anyInt());
        assertEquals("300000000090", filter.getValue().recordNumber(), "the record-number filter reaches the query, trimmed");
    }
}
