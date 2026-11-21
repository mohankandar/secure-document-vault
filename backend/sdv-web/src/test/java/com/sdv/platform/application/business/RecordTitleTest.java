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

/**
 * Task 0009 Part C: the record title comes from the source's Record title setting, falls back to the first preview
 * field, and is never an identifier. Search, Recently viewed and record detail all carry the same title.
 */
class RecordTitleTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SUB = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    private static BusinessExperienceView.FieldValue id(String key, String value) {
        return new BusinessExperienceView.FieldValue(key, key, value, true);
    }

    private static BusinessExperienceView.FieldValue field(String key, String label, String value) {
        return new BusinessExperienceView.FieldValue(key, label, value, false);
    }

    /** As latestMetadata returns it: sorted by label. */
    private static final List<BusinessExperienceView.FieldValue> PATEL = List.of(
            id("contractNumber", "300000000076"),
            field("entity", "Entity", "Acme Holdings"),
            field("firstName", "First Name", "Anna"),
            field("lastName", "Last Name", "Patel"));

    @Test
    void defaultTitleIsTheFirstPreviewField() {
        assertEquals("Acme Holdings", BusinessExperienceService.recordTitle(PATEL, List.of()));
    }

    @Test
    void chosenFieldsAreJoinedInTheChosenOrder() {
        assertEquals("Anna Patel", BusinessExperienceService.recordTitle(PATEL, List.of("firstName", "lastName")));
        assertEquals("Patel", BusinessExperienceService.recordTitle(PATEL, List.of("lastName")));
    }

    @Test
    void aChosenFieldMissingOnTheRecordFallsBackToTheDefault() {
        assertEquals("Acme Holdings", BusinessExperienceService.recordTitle(PATEL, List.of("firstName", "middleName")));
        var blankFirst = List.of(id("contractNumber", "1"), field("entity", "Entity", "Acme"), field("firstName", "First Name", " "));
        assertEquals("Acme", BusinessExperienceService.recordTitle(blankFirst, List.of("firstName")));
    }

    @Test
    void neverAnIdentifierAlone() {
        assertNull(BusinessExperienceService.recordTitle(List.of(id("contractNumber", "300000000076")), List.of()));
        // A stored setting that now points at an identifier is ignored, not shown.
        assertEquals("Acme Holdings", BusinessExperienceService.recordTitle(PATEL, List.of("contractNumber")));
    }

    // Wiring: record detail and search use the same stored setting.

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
        List<MetadataSnapshot> metadata = List.of(
                new MetadataSnapshot(SUB, "contractNumber", "Contract Number", "300000000076", "300000000076", true),
                new MetadataSnapshot(SUB, "entity", "Entity", "Acme Holdings", "ACME HOLDINGS", false),
                new MetadataSnapshot(SUB, "firstName", "First Name", "Anna", "ANNA", false),
                new MetadataSnapshot(SUB, "lastName", "Last Name", "Patel", "PATEL", false));
        when(records.findMetadataSnapshots(eq(TENANT), eq(SOURCE), anyList())).thenReturn(metadata);
        when(records.findSubmissionIdsByRecordIdentity(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(SUB));
        when(records.findSubmissionsByIds(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new Submission(SUB, TENANT, SOURCE, "src-1", 1, "sha", SubmissionStatus.COMPLETE, NOW.minusSeconds(60), NOW, null)));
        when(records.findAvailableDocuments(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new AvailableDocumentSnapshot(UUID.randomUUID(), SUB, "d1", "a.pdf", NOW, NOW, null, null, false, SensitivityLevel.STANDARD)));
        when(records.findMatchingSubmissions(eq(TENANT), eq(SOURCE), anyList(), any(), any(), any(), any(), anyBoolean(), eq(0), anyInt()))
                .thenReturn(List.of(new Submission(SUB, TENANT, SOURCE, "src-1", 1, "sha", SubmissionStatus.COMPLETE, NOW.minusSeconds(60), NOW, null)));
    }

    @Test
    void searchAndRecordDetailCarryTheChosenTitle() {
        when(definitions.findRecordTitleFields(TENANT, SOURCE)).thenReturn(List.of("firstName", "lastName"));
        var found = service.search(TENANT, SOURCE, java.util.Map.of(), NOW.minusSeconds(3600), null, null, null,
                false, 1, 25, "maya", "corr");
        assertEquals("Anna Patel", found.records().getFirst().title());
        assertEquals("Anna Patel", service.record(TENANT, found.records().getFirst().recordRef(), "maya", "corr").title());
    }

    @Test
    void withoutASettingSearchUsesTheDefaultTitle() {
        when(definitions.findRecordTitleFields(TENANT, SOURCE)).thenReturn(List.of());
        var found = service.search(TENANT, SOURCE, java.util.Map.of(), NOW.minusSeconds(3600), null, null, null,
                false, 1, 25, "maya", "corr");
        assertEquals("Acme Holdings", found.records().getFirst().title());
    }
}
