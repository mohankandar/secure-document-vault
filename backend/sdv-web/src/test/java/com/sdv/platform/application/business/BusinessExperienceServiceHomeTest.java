package com.sdv.platform.application.business;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.AvailableDocumentSnapshot;
import com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot;
import com.sdv.platform.application.port.BusinessRecordRepository.RecentDocumentView;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Home screen backend: Recently viewed (own activity only, nothing past retention) and the incompleteOnly
 * search flag (alone and with a date range). Lives in sdv-web because sdv-application has no Mockito.
 */
class BusinessExperienceServiceHomeTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
    private static final UUID SUB_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SUB_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID DOC_A1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID DOC_A2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID DOC_B1 = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
    private final MetadataFieldDefinitionRepository definitions = mock(MetadataFieldDefinitionRepository.class);
    private final BusinessRecordRepository records = mock(BusinessRecordRepository.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final BusinessExperienceService service = new BusinessExperienceService(
            tenants, sources, definitions, records, audit, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        IngestionSource source = mock(IngestionSource.class);
        when(source.sourceId()).thenReturn(SOURCE);
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(source));
    }

    private static Submission submission(UUID id, SubmissionStatus status) {
        return new Submission(id, TENANT, SOURCE, "src-" + id, 1, "sha", status, NOW.minusSeconds(3600), NOW.minusSeconds(600), null);
    }

    private static List<MetadataSnapshot> contract(UUID submissionId, String number) {
        return List.of(new MetadataSnapshot(submissionId, "contractNumber", "Contract Number", number, number, true),
                new MetadataSnapshot(submissionId, "lastName", "Last Name", "Patel", "Patel", false));
    }

    /** Record A (SUB_A) still has an AVAILABLE document; record B (SUB_B) has none left (past retention). */
    private void twoRecordsOneExpired() {
        when(records.findRecentlyViewedDocuments(eq(TENANT), anyString(), any(), anyInt())).thenReturn(List.of(
                new RecentDocumentView(DOC_A2, SUB_A, SOURCE, NOW.minusSeconds(60)),
                new RecentDocumentView(DOC_B1, SUB_B, SOURCE, NOW.minusSeconds(120)),
                new RecentDocumentView(DOC_A1, SUB_A, SOURCE, NOW.minusSeconds(300))));
        when(records.findMetadataSnapshots(eq(TENANT), eq(SOURCE), anyList())).thenAnswer(inv -> {
            List<UUID> ids = inv.getArgument(2);
            return ids.stream().flatMap(id -> contract(id, id.equals(SUB_A) ? "300000000001" : "300000000002").stream()).toList();
        });
        when(records.findSubmissionIdsByRecordIdentity(eq(TENANT), eq(SOURCE), anyList())).thenAnswer(inv -> {
            List<BusinessRecordRepository.IdentifierCriterion> ids = inv.getArgument(2);
            return List.of(ids.getFirst().normalizedValue().equals("300000000001") ? SUB_A : SUB_B);
        });
        when(records.findSubmissionsByIds(eq(TENANT), eq(SOURCE), anyList())).thenAnswer(inv -> {
            List<UUID> ids = inv.getArgument(2);
            return ids.stream().map(id -> submission(id, SubmissionStatus.COMPLETE)).toList();
        });
        when(records.findAvailableDocuments(eq(TENANT), eq(SOURCE), anyList())).thenAnswer(inv -> {
            List<UUID> ids = inv.getArgument(2);
            return ids.contains(SUB_A)
                    ? List.of(new AvailableDocumentSnapshot(DOC_A1, SUB_A, "d1", "a.pdf", NOW, NOW, null))
                    : List.of();
        });
    }

    @Test
    void recentRecordsReadOnlyTheSignedInPersonsOwnActivity() {
        twoRecordsOneExpired();
        service.recentRecords(TENANT, "maya.okafor", 8, "corr");
        verify(records).findRecentlyViewedDocuments(eq(TENANT), eq("maya.okafor"), eq(NOW.minus(BusinessExperienceService.RECENT_WINDOW)),
                eq(BusinessExperienceService.RECENT_EVENT_SCAN));
        verify(records, never()).findRecentlyViewedDocuments(any(), argThat(actor -> !"maya.okafor".equals(actor)), any(), anyInt());
    }

    @Test
    void recentRecordsDropRecordsWithNothingLeftAndKeepOneEntryPerRecord() {
        twoRecordsOneExpired();
        var result = service.recentRecords(TENANT, "maya.okafor", 8, "corr");
        assertEquals(1, result.records().size(), "record B has no AVAILABLE document left and must not appear");
        var only = result.records().getFirst();
        assertEquals("300000000001", only.record().identifiers().getFirst().value());
        assertEquals(NOW.minusSeconds(60), only.lastOpenedAt(), "the newest open of the record's documents");
        verify(audit).append(argThat(e -> e.action().equals("BUSINESS_RECENT_RECORDS_LISTED") && e.actorId().equals("maya.okafor")));
    }

    @Test
    void recentRecordsRejectAnOutOfRangeLimit() {
        assertThrows(IllegalArgumentException.class, () -> service.recentRecords(TENANT, "maya.okafor", 0, "corr"));
        assertThrows(IllegalArgumentException.class, () -> service.recentRecords(TENANT, "maya.okafor", 21, "corr"));
    }

    @Test
    void incompleteOnlyAloneIsEnoughToSearch() {
        when(records.findMatchingSubmissions(any(), any(), anyList(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(List.of());
        service.search(TENANT, SOURCE, Map.of(), null, null, null, null, true, 1, 25, "maya.okafor", "corr");
        verify(records).findMatchingSubmissions(eq(TENANT), eq(SOURCE), eq(List.of()), isNull(), isNull(), isNull(), isNull(), eq(true), eq(0), anyInt());
    }

    @Test
    void incompleteOnlyWithAReceivedDateRange() {
        Instant from = Instant.parse("2026-10-19T00:00:00Z");
        Instant to = Instant.parse("2026-11-18T23:59:59Z");
        when(records.findMatchingSubmissions(any(), any(), anyList(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(List.of());
        service.search(TENANT, SOURCE, Map.of(), from, to, null, null, true, 1, 25, "maya.okafor", "corr");
        verify(records).findMatchingSubmissions(eq(TENANT), eq(SOURCE), eq(List.of()), eq(from), eq(to), isNull(), isNull(), eq(true), eq(0), anyInt());
    }

    @Test
    void searchWithoutAnyCriterionIsStillRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> service.search(TENANT, SOURCE, Map.of(), null, null, null, null, false, 1, 25, "maya.okafor", "corr"));
    }
}
