package com.sdv.platform.application.business;

import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.QuarantineLookup;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0048: the record panel. A quarantined document is "Temporarily unavailable" to everyone who may not review it:
 * its reference only, never its level, file name or why, and it is not counted in the locked documents either. A
 * reviewer who can open its level sees it with the others, marked quarantined.
 */
class RecordQuarantineReadTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SUB = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OPEN_DOC = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID QUARANTINED_STD = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID QUARANTINED_HIGH = UUID.fromString("00000000-0000-0000-0000-0000000000d3");
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
    private final BusinessRecordRepository records = mock(BusinessRecordRepository.class);
    private final QuarantineLookup lookup = new QuarantineLookup() {
        @Override public boolean isQuarantined(UUID tenantId, UUID documentId) { return quarantined(tenantId, List.of(documentId)).size() == 1; }
        @Override public Set<UUID> quarantined(UUID tenantId, Collection<UUID> ids) {
            return ids.stream().filter(id -> id.equals(QUARANTINED_STD) || id.equals(QUARANTINED_HIGH)).collect(java.util.stream.Collectors.toSet());
        }
    };
    private final BusinessExperienceService service = new BusinessExperienceService(tenants, sources,
            mock(MetadataFieldDefinitionRepository.class), records, mock(AuditEventPort.class), lookup, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        IngestionSource source = mock(IngestionSource.class);
        when(source.sourceId()).thenReturn(SOURCE);
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(source));
        TestRecordRefs.group(records);
        when(records.findSubmissionIdsOfRecord(eq(TENANT), eq(SOURCE), any())).thenReturn(List.of(SUB));
        when(records.findSubmissionsByIds(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new Submission(SUB, TENANT, SOURCE, "s1", 3, "sha", SubmissionStatus.COMPLETE, NOW.minusSeconds(600), NOW, null)));
        when(records.findMetadataSnapshots(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new MetadataSnapshot(SUB, "contractNumber", "Contract Number", "300000000076", "300000000076", true)));
        when(records.findAvailableDocuments(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new AvailableDocumentSnapshot(OPEN_DOC, SUB, "a", "contract.pdf", NOW, NOW, null, 1, false, SensitivityLevel.STANDARD),
                new AvailableDocumentSnapshot(QUARANTINED_STD, SUB, "b", "letter.pdf", NOW, NOW, null, 1, false, SensitivityLevel.STANDARD),
                new AvailableDocumentSnapshot(QUARANTINED_HIGH, SUB, "c", "medical.pdf", NOW, NOW, null, 1, false, SensitivityLevel.HIGHLY_CONFIDENTIAL)));
        when(records.findRecordSensitivityLevel(any(), any(), any())).thenReturn(Optional.empty());
    }

    private BusinessExperienceView.RecordView open(Clearance clearance) {
        String ref = TestRecordRefs.of(SOURCE, "contractNumber", "300000000076");
        return service.record(TENANT, ref, "maya", "corr", clearance);
    }

    private static List<UUID> unavailable(BusinessExperienceView.RecordView view) {
        return view.unavailableDocuments().stream().map(BusinessExperienceView.UnavailableDocument::documentId).sorted().toList();
    }

    @Test
    void aBusinessUserSeesBothQuarantinedDocumentsAsUnavailableAndNoLevel() {
        var view = open(new Clearance(false, false, List.of("BUSINESS_USER"), false));
        assertEquals(List.of(OPEN_DOC), view.documents().stream().map(BusinessExperienceView.DocumentSummary::documentId).toList());
        assertEquals(List.of(QUARANTINED_STD, QUARANTINED_HIGH), unavailable(view));
        assertTrue(view.lockedDocuments().isEmpty(), "the Highly Confidential one is unavailable, not locked with its level");
        assertNull(view.restrictedLevel(), "no level is given away");
        assertFalse(view.unavailableDocuments().toString().contains("medical"), "no file name");
    }

    @Test
    void aStewardReviewsTheLevelTheyCanOpenAndIsToldTheOtherIsUnavailable() {
        var view = open(new Clearance(true, false, List.of("DOCUMENT_STEWARD"), true));
        var reviewed = view.documents().stream().filter(d -> d.documentId().equals(QUARANTINED_STD)).findFirst().orElseThrow();
        assertTrue(reviewed.quarantined(), "marked quarantined for the reviewer");
        assertFalse(view.documents().stream().filter(d -> d.documentId().equals(OPEN_DOC)).findFirst().orElseThrow().quarantined());
        assertEquals(List.of(QUARANTINED_HIGH), unavailable(view));
        assertTrue(view.lockedDocuments().isEmpty());
    }

    @Test
    void aRestrictedStewardReviewsBoth() {
        var view = open(new Clearance(true, true, List.of("RESTRICTED_STEWARD"), true));
        assertEquals(3, view.documents().size());
        assertTrue(view.unavailableDocuments().isEmpty());
        assertEquals(2, view.documents().stream().filter(BusinessExperienceView.DocumentSummary::quarantined).count());
    }
}
