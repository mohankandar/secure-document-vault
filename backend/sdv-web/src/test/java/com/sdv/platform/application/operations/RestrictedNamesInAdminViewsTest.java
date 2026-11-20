package com.sdv.platform.application.operations;

import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.audit.AuditQueryService;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.AuditQueryPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot;
import com.sdv.platform.application.port.KeyServiceHealthProbe;
import com.sdv.platform.application.port.OperationalRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.storage.StorageVolumeAdminService;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0026: the audit list, its CSV export and the problem lists name a document only when the person reading them
 * could open it at its effective level. The admins who read them never hold VIEW_CONFIDENTIAL, so Confidential and
 * Highly Confidential documents, by their own level or their record's, read as "a document". Counts are unchanged.
 */
class RestrictedNamesInAdminViewsTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PLAIN_SUB = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID RAISED_SUB = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID STANDARD = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
    private static final UUID CONFIDENTIAL = UUID.fromString("dddddddd-0000-0000-0000-000000000002");
    private static final UUID RAISED = UUID.fromString("dddddddd-0000-0000-0000-000000000003");
    private static final UUID NO_LEVEL = UUID.fromString("dddddddd-0000-0000-0000-000000000004");

    private final BusinessRecordRepository records = mock(BusinessRecordRepository.class);
    private final DocumentAccessGuard guard = new DocumentAccessGuard(records, mock(AuditEventPort.class));

    RestrictedNamesInAdminViewsTest() {
        // Two records: a plain one, and one whose record level is Highly Confidential.
        when(records.findMetadataSnapshots(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new MetadataSnapshot(PLAIN_SUB, "contractNumber", "Contract", "300000000001", "300000000001", true),
                new MetadataSnapshot(RAISED_SUB, "contractNumber", "Contract", "300000000002", "300000000002", true)));
        String raisedKey = com.sdv.platform.application.business.RecordKey.ofIdentifiers(
                List.of(new com.sdv.platform.application.business.RecordKey.Part("contractNumber", "300000000002")));
        when(records.findRecordSensitivityLevels(eq(TENANT), eq(SOURCE), anyCollection()))
                .thenAnswer(call -> ((java.util.Collection<?>) call.getArgument(2)).contains(raisedKey)
                        ? Map.of(raisedKey, SensitivityLevel.HIGHLY_CONFIDENTIAL) : Map.of());
    }

    private static DocumentAccessGuard.DocumentLevelRef ref(UUID document, UUID submission, SensitivityLevel level) {
        return new DocumentAccessGuard.DocumentLevelRef(document, SOURCE, submission, level);
    }

    private static AuditQueryPort.Row row(UUID document, String file, DocumentAccessGuard.DocumentLevelRef ref) {
        return new AuditQueryPort.Row(new AuditEvent(UUID.randomUUID(), TENANT, "USER", "maya", "DOCUMENT_VIEW_OPENED",
                "DOCUMENT", document.toString(), Instant.parse("2026-11-20T10:00:00Z"), null, AuditOutcome.SUCCESS, "{}",
                "Maya Okafor"), "300000000001", file, ref);
    }

    private List<AuditQueryPort.Row> rows() {
        return List.of(
                row(STANDARD, "loan-agreement.pdf", ref(STANDARD, PLAIN_SUB, SensitivityLevel.STANDARD)),
                row(CONFIDENTIAL, "salary-slip.pdf", ref(CONFIDENTIAL, PLAIN_SUB, SensitivityLevel.CONFIDENTIAL)),
                row(RAISED, "settlement.pdf", ref(RAISED, RAISED_SUB, SensitivityLevel.STANDARD)),
                row(NO_LEVEL, "unknown.pdf", null));
    }

    @Test
    void theAuditListAndItsExportNameOnlyDocumentsTheReaderCouldOpen() {
        AuditQueryPort query = mock(AuditQueryPort.class);
        when(query.findRows(eq(TENANT), any(), anyInt())).thenReturn(rows());
        AuditQueryService service = new AuditQueryService(query, mock(AuditEventPort.class), guard);

        var page = service.search(TENANT, "ops", null, "c", Clearance.STANDARD_ONLY);
        assertEquals(4, page.events().size(), "every event is still listed");
        assertEquals("loan-agreement.pdf", page.events().get(0).filename());
        assertNull(page.events().get(1).filename(), "Confidential by its own level");
        assertNull(page.events().get(2).filename(), "Highly Confidential through its record");
        assertNull(page.events().get(3).filename(), "no document to check: never named");
        assertEquals("300000000001", page.events().get(1).recordNumber(), "the record number stays");

        byte[] csv = service.export(TENANT, "ops", new AuditQueryService.ExportRequest(null, null, null), "c",
                Clearance.STANDARD_ONLY).csv();
        String text = new String(csv, StandardCharsets.UTF_8);
        assertTrue(text.contains("loan-agreement.pdf"));
        for (String hidden : List.of("salary-slip.pdf", "settlement.pdf", "unknown.pdf")) {
            assertFalse(text.contains(hidden), "the export names " + hidden);
        }
        assertTrue(text.contains("Maya Okafor opened a document"));

        var full = service.search(TENANT, "steward", null, "c", Clearance.ALL);
        assertEquals("salary-slip.pdf", full.events().get(1).filename(), "a reader who could open it sees the name");
        assertEquals("settlement.pdf", full.events().get(2).filename());
    }

    @Test
    void theProblemListsNameOnlyDocumentsTheReaderCouldOpen() {
        OperationalRepository ops = mock(OperationalRepository.class);
        when(ops.databaseAvailable()).thenReturn(true);
        when(ops.countProcessingFailures(TENANT)).thenReturn(2L);
        when(ops.findProcessingFailures(eq(TENANT), anyInt())).thenReturn(List.of(
                new OperationalRepository.ProcessingFailure(STANDARD, PLAIN_SUB, SOURCE, "E", 1, Instant.now(),
                        "loan-agreement.pdf", SensitivityLevel.STANDARD),
                new OperationalRepository.ProcessingFailure(CONFIDENTIAL, PLAIN_SUB, SOURCE, "E", 1, Instant.now(),
                        "salary-slip.pdf", SensitivityLevel.CONFIDENTIAL)));
        when(ops.countStuckDocuments(eq(TENANT), any(), any())).thenReturn(1L);
        when(ops.findStuckDocuments(eq(TENANT), any(), any(), anyInt())).thenReturn(List.of(
                new OperationalRepository.StuckDocument(RAISED, RAISED_SUB, SOURCE, "PROCESSING", Instant.now(), null,
                        "settlement.pdf", SensitivityLevel.STANDARD)));
        TenantRepository tenants = mock(TenantRepository.class);
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        OperationsService service = new OperationsService(ops, mock(StorageVolumeAdminService.class),
                mock(KeyServiceHealthProbe.class), tenants, mock(ProblemRecordResolver.class), guard);

        var summary = service.summary(TENANT, Clearance.STANDARD_ONLY);
        assertEquals(2L, summary.processingFailureCount());
        assertEquals(2, summary.processingFailures().size());
        assertEquals("loan-agreement.pdf", summary.processingFailures().get(0).originalFilename());
        assertNull(summary.processingFailures().get(1).originalFilename());
        assertEquals("E", summary.processingFailures().get(1).errorCode(), "the facts of the problem stay");
        assertNull(summary.stuckDocuments().getFirst().originalFilename(), "Highly Confidential through its record");

        // If the levels can't be read, no name is shown at all.
        when(records.findMetadataSnapshots(any(), any(), anyList())).thenThrow(new IllegalStateException("db down"));
        var degraded = service.summary(TENANT, Clearance.STANDARD_ONLY);
        assertNull(degraded.processingFailures().get(0).originalFilename());
    }

    @Test
    void theLevelIsNeverSentToTheProblemScreen() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(
                new OperationalRepository.ProcessingFailure(STANDARD, PLAIN_SUB, SOURCE, "E", 1, Instant.now(),
                        "x.pdf", SensitivityLevel.CONFIDENTIAL));
        assertFalse(json.contains("sensitivityLevel"), json);
    }

    @Test
    void aDocumentsRecordKeyIsTheSameAsSearchGivesItsRecord() {
        List<MetadataSnapshot> metadata = List.of(
                new MetadataSnapshot(PLAIN_SUB, "contractNumber", "Contract", "300-000", "300000", true),
                new MetadataSnapshot(PLAIN_SUB, "branch", "Branch", "North", "north", true),
                new MetadataSnapshot(PLAIN_SUB, "borrower", "Borrower", "Ana", "ana", false),
                new MetadataSnapshot(RAISED_SUB, "contractNumber", "Contract", "1", "1", true));
        assertEquals(com.sdv.platform.application.business.RecordKey.ofIdentifiers(List.of(
                        new com.sdv.platform.application.business.RecordKey.Part("branch", "north"),
                        new com.sdv.platform.application.business.RecordKey.Part("contractNumber", "300000"))),
                com.sdv.platform.application.business.RecordKey.forSubmission(PLAIN_SUB, metadata),
                "identifier values only, of this submission only, in key order");
        UUID alone = UUID.randomUUID();
        assertEquals(com.sdv.platform.application.business.RecordKey.ofSubmission(alone),
                com.sdv.platform.application.business.RecordKey.forSubmission(alone, List.of(
                        new MetadataSnapshot(alone, "contractNumber", "Contract", " ", " ", true))),
                "no identifier value: the submission is its own record");
    }
}
