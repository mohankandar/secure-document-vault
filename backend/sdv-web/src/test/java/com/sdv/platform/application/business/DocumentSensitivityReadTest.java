package com.sdv.platform.application.business;

import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.AvailableDocumentSnapshot;
import com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.TenantRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0024, part 3: record detail sends each document's sensitivity level, as information only (nothing filtered). */
class DocumentSensitivityReadTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SUB = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
    private final MetadataFieldDefinitionRepository definitions = mock(MetadataFieldDefinitionRepository.class);
    private final BusinessRecordRepository records = mock(BusinessRecordRepository.class);
    private final BusinessExperienceService service = new BusinessExperienceService(
            tenants, sources, definitions, records, mock(AuditEventPort.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        IngestionSource source = mock(IngestionSource.class);
        when(source.sourceId()).thenReturn(SOURCE);
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(source));
        when(records.findMetadataSnapshots(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new MetadataSnapshot(SUB, "contractNumber", "Contract Number", "300000000076", "300000000076", true)));
        when(records.findSubmissionIdsByRecordIdentity(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(SUB));
        Submission submission = new Submission(SUB, TENANT, SOURCE, "src-1", 3, "sha", SubmissionStatus.COMPLETE, NOW.minusSeconds(60), NOW, null);
        when(records.findSubmissionsByIds(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(submission));
        when(records.findMatchingSubmissions(eq(TENANT), eq(SOURCE), anyList(), any(), any(), any(), any(), anyBoolean(), eq(0), anyInt()))
                .thenReturn(List.of(submission));
        when(records.findAvailableDocuments(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new AvailableDocumentSnapshot(UUID.randomUUID(), SUB, "d1", "a.pdf", NOW, NOW, null, 1, false, SensitivityLevel.STANDARD),
                new AvailableDocumentSnapshot(UUID.randomUUID(), SUB, "d2", "b.pdf", NOW, NOW, null, 1, false, SensitivityLevel.CONFIDENTIAL),
                new AvailableDocumentSnapshot(UUID.randomUUID(), SUB, "d3", "c.pdf", NOW, NOW, null, 1, false, SensitivityLevel.HIGHLY_CONFIDENTIAL)));
    }

    @Test
    void recordDetailSendsEachDocumentsLevelAndHidesNothing() {
        var found = service.search(TENANT, SOURCE, java.util.Map.of(), NOW.minusSeconds(3600), null, null, null,
                false, 1, 25, "maya", "corr");
        var detail = service.record(TENANT, found.records().getFirst().recordRef(), "maya", "corr");
        assertEquals(java.util.Map.of("a.pdf", "STANDARD", "b.pdf", "CONFIDENTIAL", "c.pdf", "HIGHLY_CONFIDENTIAL"),
                detail.documents().stream().collect(java.util.stream.Collectors.toMap(
                        BusinessExperienceView.DocumentSummary::originalFilename,
                        BusinessExperienceView.DocumentSummary::sensitivityLevel)));
        assertEquals(3, found.records().getFirst().availableDocumentCount(), "search counts every document: no filtering yet");
    }
}
