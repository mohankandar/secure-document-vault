package com.sdv.platform.application.business;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.AvailableDocumentSnapshot;
import com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionStatus;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0024, part 2: record detail returns the record's level and, for each document, its own level and its effective
 * level (the higher of the two). A document that arrives after the record got its level is covered by the same rule,
 * read when the record is opened; nothing is copied onto document rows.
 */
class RecordSensitivityReadTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID LATER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    private static final UUID KEY = TestRecordRefs.id("contractNumber", "300000000076");

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
    private final BusinessRecordRepository records = mock(BusinessRecordRepository.class);
    private final BusinessExperienceService service = new BusinessExperienceService(tenants, sources,
            mock(MetadataFieldDefinitionRepository.class), records, mock(AuditEventPort.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        IngestionSource source = mock(IngestionSource.class);
        when(source.sourceId()).thenReturn(SOURCE);
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(source));
        TestRecordRefs.group(records);
        when(records.findSubmissionIdsOfRecord(eq(TENANT), eq(SOURCE), any())).thenReturn(List.of(FIRST, LATER));
        when(records.findSubmissionsByIds(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new Submission(FIRST, TENANT, SOURCE, "s1", 1, "sha", SubmissionStatus.COMPLETE, NOW.minusSeconds(600), NOW, null),
                new Submission(LATER, TENANT, SOURCE, "s2", 2, "sha", SubmissionStatus.COMPLETE, NOW.minusSeconds(60), NOW, null)));
        when(records.findMetadataSnapshots(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new MetadataSnapshot(FIRST, "contractNumber", "Contract Number", "300000000076", "300000000076", true),
                new MetadataSnapshot(LATER, "contractNumber", "Contract Number", "3000-0000-0076", "300000000076", true)));
        when(records.findAvailableDocuments(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                document(FIRST, "contract.pdf", SensitivityLevel.STANDARD),
                // Arrived on a later submission, after the record's level was set, with no level of its own.
                document(LATER, "later.pdf", SensitivityLevel.STANDARD),
                document(LATER, "medical.pdf", SensitivityLevel.HIGHLY_CONFIDENTIAL)));
    }

    private static AvailableDocumentSnapshot document(UUID submission, String name, SensitivityLevel level) {
        return new AvailableDocumentSnapshot(UUID.randomUUID(), submission, name, name, NOW, NOW, null, 1, false, level);
    }

    private BusinessExperienceView.RecordView open() {
        String ref = TestRecordRefs.of(SOURCE, "contractNumber", "300000000076");
        // Task 0026: read with full clearance; locking is tested in DocumentLevelEnforcementTest.
        return service.record(TENANT, ref, "maya", "corr", com.sdv.platform.application.access.Clearance.ALL);
    }

    private static Map<String, List<String>> levels(BusinessExperienceView.RecordView view) {
        return view.documents().stream().collect(Collectors.toMap(
                BusinessExperienceView.DocumentSummary::originalFilename,
                d -> List.of(d.sensitivityLevel(), d.effectiveSensitivityLevel())));
    }

    @Test
    void eachDocumentGetsTheHigherOfItsOwnAndTheRecordsLevel() {
        when(records.findRecordSensitivityLevel(TENANT, SOURCE, KEY)).thenReturn(Optional.of(SensitivityLevel.CONFIDENTIAL));

        var view = open();
        assertEquals("CONFIDENTIAL", view.recordSensitivityLevel());
        assertEquals(Map.of(
                "contract.pdf", List.of("STANDARD", "CONFIDENTIAL"),
                "later.pdf", List.of("STANDARD", "CONFIDENTIAL"),
                "medical.pdf", List.of("HIGHLY_CONFIDENTIAL", "HIGHLY_CONFIDENTIAL")), levels(view));
        verify(records).findRecordSensitivityLevel(TENANT, SOURCE, KEY);
    }

    @Test
    void withoutARecordLevelTheEffectiveLevelIsTheDocumentsOwn() {
        when(records.findRecordSensitivityLevel(TENANT, SOURCE, KEY)).thenReturn(Optional.empty());

        var view = open();
        assertNull(view.recordSensitivityLevel());
        assertEquals(Map.of(
                "contract.pdf", List.of("STANDARD", "STANDARD"),
                "later.pdf", List.of("STANDARD", "STANDARD"),
                "medical.pdf", List.of("HIGHLY_CONFIDENTIAL", "HIGHLY_CONFIDENTIAL")), levels(view));
        assertEquals(3, view.documents().size(), "nothing is filtered yet");
    }

    @Test
    void aSubmissionWithoutIdentifiersReadsItsOwnRecordLevel() {
        UUID alone = UUID.randomUUID();
        when(records.findSubmissionsByIds(TENANT, SOURCE, List.of(alone))).thenReturn(List.of(
                new Submission(alone, TENANT, SOURCE, "s9", 1, "sha", SubmissionStatus.COMPLETE, NOW, NOW, null)));
        when(records.findMetadataSnapshots(TENANT, SOURCE, List.of(alone))).thenReturn(List.of());
        when(records.findAvailableDocuments(TENANT, SOURCE, List.of(alone))).thenReturn(List.of(
                document(alone, "note.pdf", SensitivityLevel.STANDARD)));
        when(records.findRecordSensitivityLevel(TENANT, SOURCE, alone))
                .thenReturn(Optional.of(SensitivityLevel.HIGHLY_CONFIDENTIAL));

        when(records.findSubmissionIdsOfRecord(TENANT, SOURCE, alone)).thenReturn(List.of(alone));
        var view = service.record(TENANT, TestRecordRefs.ref(SOURCE, alone),
                "maya", "corr", com.sdv.platform.application.access.Clearance.ALL);
        assertEquals("HIGHLY_CONFIDENTIAL", view.recordSensitivityLevel());
        assertEquals(Map.of("note.pdf", List.of("STANDARD", "HIGHLY_CONFIDENTIAL")), levels(view));
    }
}
