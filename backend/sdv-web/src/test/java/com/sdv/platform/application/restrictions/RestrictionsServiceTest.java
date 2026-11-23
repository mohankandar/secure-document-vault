package com.sdv.platform.application.restrictions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.business.RecordKey;
import com.sdv.platform.application.port.RestrictionsRepository;
import com.sdv.platform.application.port.RestrictionsRepository.Holds;
import com.sdv.platform.application.port.RestrictionsRepository.Identifier;
import com.sdv.platform.application.port.RestrictionsRepository.LevelChange;
import com.sdv.platform.application.port.RestrictionsRepository.Quarantine;
import com.sdv.platform.application.port.RestrictionsRepository.RecordLevel;
import com.sdv.platform.application.port.RestrictionsRepository.Waiting;
import com.sdv.platform.application.reports.StorageReportService;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0055: the Restrictions page: each section, records by primary identifier only, pages, and nothing restricted leaks. */
class RestrictionsServiceTest {
    private static final UUID T = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LOANS = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID CARDS = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID SUB = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID DOC_SUB = UUID.fromString("30000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private final RestrictionsRepository repo = mock(RestrictionsRepository.class);
    private final StorageReportService storage = mock(StorageReportService.class);
    private RestrictionsService service;

    @BeforeEach
    void setUp() {
        when(storage.report(T)).thenReturn(new StorageReportService.StorageReport("2026-11-23T09:50:00Z", 100, 15, "UTC", "TENANT",
                List.of(), List.of(
                        new StorageReportService.StoredRow(LOANS, "Loans", SensitivityLevel.STANDARD, 90, 0, 0),
                        new StorageReportService.StoredRow(LOANS, "Loans", SensitivityLevel.CONFIDENTIAL, 7, 0, 0),
                        new StorageReportService.StoredRow(LOANS, "Loans", SensitivityLevel.HIGHLY_CONFIDENTIAL, 2, 0, 0),
                        new StorageReportService.StoredRow(CARDS, "Cards", SensitivityLevel.CONFIDENTIAL, 3, 0, 0)),
                List.of(), null, List.of(), List.of(), List.of(), null));
        String identifiers = RecordKey.ofIdentifiers(List.of(new RecordKey.Part("contractNumber", "300000000001"),
                new RecordKey.Part("customerId", "C77")));
        List<RecordLevel> records = new ArrayList<>();
        records.add(new RecordLevel(UUID.randomUUID(), LOANS, identifiers, "HIGHLY_CONFIDENTIAL", NOW.minusSeconds(60)));
        records.add(new RecordLevel(UUID.randomUUID(), CARDS, "submission:" + SUB, "CONFIDENTIAL", NOW.minusSeconds(120)));
        records.add(new RecordLevel(UUID.randomUUID(), CARDS, "not a key", "CONFIDENTIAL", NOW.minusSeconds(180)));
        when(repo.findRestrictedRecords(eq(T), any(), anyInt())).thenReturn(records);
        when(repo.countRestrictedRecords(T)).thenReturn(3L);
        when(repo.findLevelChanges(eq(T), any(), any(), anyInt())).thenReturn(List.of(
                new LevelChange(UUID.randomUUID(), "Dee Steward", NOW.minusSeconds(30), "DOCUMENT", "STANDARD", "CONFIDENTIAL", null, null, DOC_SUB),
                new LevelChange(UUID.randomUUID(), "Ada Admin", NOW.minusSeconds(90), "RECORD", "HIGHLY_CONFIDENTIAL", "CONFIDENTIAL", LOANS, identifiers, null)));
        when(repo.countLevelChanges(eq(T), any())).thenReturn(2L);
        when(repo.findSubmissionIdentifiers(eq(T), any())).thenReturn(Map.of(
                SUB, new Identifier("Card number", "4000123412341234"), DOC_SUB, new Identifier("Contract number", "300000000009")));
        // Task 0056: a non-identifier field first; the contract number is the first identifier shown.
        when(repo.findDisplayOrders(eq(T), any())).thenReturn(Map.of(LOANS, List.of("branch", "contractNumber", "customerId")));
        when(repo.findIdentifierLabels(eq(T), any())).thenReturn(Map.of(LOANS, Map.of("contractNumber", "Contract number", "customerId", "Customer ID")));
        when(repo.findWaitingLowerings(T)).thenReturn(new Waiting(2, NOW.minusSeconds(86_400)));
        when(repo.countHolds(T)).thenReturn(new Holds(4, 6));
        when(repo.countQuarantine(T)).thenReturn(new Quarantine(2, 1500, 1));
        service = new RestrictionsService(repo, storage, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void eachSectionCountsWhatItShould() {
        RestrictionsService.Overview o = service.overview(T);
        assertEquals("2026-11-23T09:50:00Z", o.levelsCountedAt(), "from the kept storage counts, no document read");
        assertEquals(2, o.levels().size(), "only systems with documents above Standard");
        assertEquals(new RestrictionsService.SystemLevels("Loans", 7, 2), o.levels().getFirst());
        assertEquals(10, o.confidential());
        assertEquals(2, o.highlyConfidential());
        assertEquals(3, o.restrictedRecords());
        assertEquals(2, o.levelChanges());
        assertEquals(new RestrictionsService.Lowerings(2, NOW.minusSeconds(86_400)), o.lowerings());
        assertEquals(new RestrictionsService.LegalHolds(4, 6), o.legalHolds());
        assertEquals(new RestrictionsService.QuarantineCounts(2, 1500, 1), o.quarantine());
        verify(repo).countLevelChanges(T, NOW.minus(RestrictionsService.RECENT));
    }

    @Test
    void recordsAreNamedByTheirPrimaryIdentifierOnly() {
        RestrictionsService.Overview o = service.overview(T);
        List<RestrictionsService.RestrictedRecord> r = o.records().items();
        assertEquals("Contract number", r.get(0).identifierLabel());
        assertEquals("300000000001", r.get(0).identifier(), "the primary field, not the other identifier");
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, r.get(0).level());
        assertEquals("4000123412341234", r.get(1).identifier(), "a record of one submission: its primary identifier");
        assertNull(r.get(2).identifier(), "a key that can't be read names nothing, never a guess");
        // A document's change names its record, never the document.
        assertEquals("300000000009", o.changes().items().get(0).identifier());
        assertEquals("DOCUMENT", o.changes().items().get(0).scope());
        assertEquals("300000000001", o.changes().items().get(1).identifier());
    }

    @Test
    void theDisplayOrderNotTheFieldKeyPicksThePrimaryIdentifier() {
        // Task 0056: customerId is shown before contractNumber, though contractNumber comes first by field key.
        when(repo.findDisplayOrders(eq(T), any())).thenReturn(Map.of(LOANS, List.of("branch", "customerId", "contractNumber")));
        RestrictionsService.RestrictedRecord r = service.overview(T).records().items().getFirst();
        assertEquals("Customer ID", r.identifierLabel());
        assertEquals("C77", r.identifier());
        // No saved display order: by field key.
        when(repo.findDisplayOrders(eq(T), any())).thenReturn(Map.of());
        assertEquals("300000000001", service.overview(T).records().items().getFirst().identifier());
    }

    @Test
    void nothingRestrictedLeaks() throws Exception {
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(service.overview(T));
        for (String never : List.of(SUB.toString(), DOC_SUB.toString(), "C77", "documentId", "reason", "title", "filename", ".pdf")) {
            assertFalse(json.contains(never), never);
        }
    }

    @Test
    void listsPageWithPositionsVeroGave() {
        List<RecordLevel> many = new ArrayList<>();
        for (int i = 0; i < RestrictionsService.PAGE + 1; i++) {
            many.add(new RecordLevel(UUID.randomUUID(), LOANS, "submission:" + SUB, "CONFIDENTIAL", NOW.minusSeconds(i)));
        }
        when(repo.findRestrictedRecords(eq(T), isNull(), eq(RestrictionsService.PAGE + 1))).thenReturn(many);
        RestrictionsService.RecordPage first = service.records(T, null);
        assertEquals(RestrictionsService.PAGE, first.items().size());
        assertNotNull(first.next());
        service.records(T, first.next());
        verify(repo).findRestrictedRecords(eq(T), argThat(k -> k != null && k.levelId().equals(many.get(RestrictionsService.PAGE - 1).levelId())),
                eq(RestrictionsService.PAGE + 1));
        CodedRefusal refusal = (CodedRefusal) assertThrows(RuntimeException.class, () -> service.records(T, "not-a-page"));
        assertEquals("SDV-RST-002", refusal.errorCode().code());
        refusal = (CodedRefusal) assertThrows(RuntimeException.class, () -> service.changes(T, "eHl6"));
        assertEquals("SDV-RST-002", refusal.errorCode().code());
    }
}
