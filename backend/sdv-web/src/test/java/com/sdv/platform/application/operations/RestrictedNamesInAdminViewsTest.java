package com.sdv.platform.application.operations;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import com.sdv.platform.persistence.operations.JdbcOperationalRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0027, carry-over 0.4 (separation rule 4): the audit list, its CSV export and Problems, read by the IT
 * Admin and Platform Ops, never show a file name, at any level. A document is named instead by the record's primary
 * identifier, the document ID, its effective level, its received date and its page count. (Task 0026 hid only the
 * names of documents the reader couldn't open; the owner replaced that rule on 2026-11-20.) Counts are unchanged.
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
    private static final Instant RECEIVED = Instant.parse("2026-11-14T09:30:00Z");
    private static final ObjectMapper JSON = com.sdv.platform.application.json.VeroJson.mapper();

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

    private static AuditQueryPort.Row row(UUID document, DocumentAccessGuard.DocumentLevelRef ref, Integer pages) {
        return new AuditQueryPort.Row(new AuditEvent(UUID.randomUUID(), TENANT, "USER", "maya", "DOCUMENT_VIEW_OPENED",
                "DOCUMENT", document.toString(), Instant.parse("2026-11-20T10:00:00Z"), null, AuditOutcome.SUCCESS, "{}",
                "Maya Okafor"), "300000000001", ref, ref == null ? null : RECEIVED, pages);
    }

    private List<AuditQueryPort.Row> rows() {
        return List.of(
                row(STANDARD, ref(STANDARD, PLAIN_SUB, SensitivityLevel.STANDARD), 9),
                row(CONFIDENTIAL, ref(CONFIDENTIAL, PLAIN_SUB, SensitivityLevel.CONFIDENTIAL), 3),
                row(RAISED, ref(RAISED, RAISED_SUB, SensitivityLevel.STANDARD), 22),
                row(NO_LEVEL, null, null));
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        for (Iterator<String> it = node.propertyNames().iterator(); it.hasNext(); ) keys.add(it.next());
        return keys;
    }

    /** No field with "file" in its name anywhere in the JSON (filename, originalFilename, fileName ...). */
    private static void assertNoFileField(JsonNode node, String where) {
        if (node.isObject()) {
            node.propertyNames().iterator().forEachRemaining(name -> assertFalse(name.toLowerCase().contains("file"), where + " has " + name));
            node.values().forEach(child -> assertNoFileField(child, where));
        } else if (node.isArray()) {
            node.values().forEach(child -> assertNoFileField(child, where));
        }
    }

    @Test
    void theAuditListAndItsExportDescribeTheDocumentAndNeverNameAFile() throws Exception {
        AuditQueryPort query = mock(AuditQueryPort.class);
        when(query.findRows(eq(TENANT), any(), anyInt())).thenReturn(rows());
        AuditQueryService service = new AuditQueryService(query, mock(AuditEventPort.class), guard);

        var page = service.search(TENANT, "ops", null, "c");
        assertEquals(4, page.events().size(), "every event is still listed");
        var standard = page.events().get(0);
        assertEquals("300000000001", standard.recordNumber(), "the record's primary identifier");
        assertEquals(STANDARD, standard.documentId());
        assertEquals(SensitivityLevel.STANDARD, standard.sensitivityLevel());
        assertEquals(RECEIVED, standard.documentReceivedAt());
        assertEquals(9, standard.documentPageCount());
        assertEquals(SensitivityLevel.CONFIDENTIAL, page.events().get(1).sensitivityLevel(), "its own level");
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, page.events().get(2).sensitivityLevel(), "through its record");
        assertNull(page.events().get(3).documentId(), "no stored document: none named");
        assertNull(page.events().get(3).sensitivityLevel());

        JsonNode json = JSON.readTree(JSON.writeValueAsString(page));
        assertNoFileField(json, "the audit list");
        assertEquals(new TreeSet<>(List.of("auditEventId", "actorType", "actorId", "action", "targetType", "targetId",
                        "occurredAt", "correlationId", "outcome", "safeDetailsJson", "actorDisplayName", "recordNumber",
                        "documentId", "sensitivityLevel", "documentReceivedAt", "documentPageCount")),
                keys(json.at("/events/0")), "exactly these fields; nothing else about the document");

        String csv = new String(service.export(TENANT, "ops", new AuditQueryService.ExportRequest(null, null, null), "c").csv(),
                StandardCharsets.UTF_8);
        List<String> lines = List.of(csv.substring(1).split("\r\n"));
        assertEquals("When (UTC),What happened,Person,Record,Result,Action code,Document ID,Level,Received (UTC),Pages", lines.get(0));
        assertTrue(lines.get(1).endsWith(",\"" + STANDARD + "\",\"STANDARD\",\"2026-11-14T09:30:00Z\",\"9\""), lines.get(1));
        assertTrue(lines.get(3).endsWith(",\"" + RAISED + "\",\"HIGHLY_CONFIDENTIAL\",\"2026-11-14T09:30:00Z\",\"22\""), lines.get(3));
        assertTrue(lines.get(1).contains("Maya Okafor opened a document"), lines.get(1));
        assertFalse(csv.toLowerCase().contains(".pdf"), csv);
    }

    @Test
    void theProblemListsDescribeTheDocumentAndNeverNameAFile() throws Exception {
        OperationalRepository ops = mock(OperationalRepository.class);
        when(ops.databaseAvailable()).thenReturn(true);
        when(ops.countProcessingFailures(TENANT)).thenReturn(2L);
        when(ops.findProcessingFailures(eq(TENANT), anyInt())).thenReturn(List.of(
                new OperationalRepository.ProcessingFailure(STANDARD, PLAIN_SUB, SOURCE, "E", 1, RECEIVED, 9, SensitivityLevel.STANDARD),
                new OperationalRepository.ProcessingFailure(CONFIDENTIAL, PLAIN_SUB, SOURCE, "E", 1, RECEIVED, null, SensitivityLevel.CONFIDENTIAL)));
        when(ops.countStuckDocuments(eq(TENANT), any(), any())).thenReturn(1L);
        when(ops.findStuckDocuments(eq(TENANT), any(), any(), anyInt())).thenReturn(List.of(
                new OperationalRepository.StuckDocument(RAISED, RAISED_SUB, SOURCE, "PROCESSING", Instant.now(), null,
                        RECEIVED, 22, SensitivityLevel.STANDARD)));
        TenantRepository tenants = mock(TenantRepository.class);
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        OperationsService service = new OperationsService(ops, mock(StorageVolumeAdminService.class),
                mock(KeyServiceHealthProbe.class), tenants, mock(ProblemRecordResolver.class), guard);

        var summary = service.summary(TENANT);
        assertEquals(2L, summary.processingFailureCount());
        assertEquals(2, summary.processingFailures().size());
        assertEquals(SensitivityLevel.STANDARD, summary.processingFailures().get(0).sensitivityLevel());
        assertEquals(9, summary.processingFailures().get(0).pageCount());
        assertEquals(RECEIVED, summary.processingFailures().get(0).ingestedAt());
        assertEquals(SensitivityLevel.CONFIDENTIAL, summary.processingFailures().get(1).sensitivityLevel());
        assertEquals("E", summary.processingFailures().get(1).errorCode(), "the facts of the problem stay");
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, summary.stuckDocuments().getFirst().sensitivityLevel(),
                "the effective level: Highly Confidential through its record");

        JsonNode json = JSON.readTree(JSON.writeValueAsString(summary));
        assertNoFileField(json, "the problems summary");
        assertEquals(new TreeSet<>(List.of("documentId", "submissionId", "sourceId", "errorCode", "processingAttemptCount",
                "ingestedAt", "pageCount", "sensitivityLevel")), keys(json.at("/processingFailures/0")));
        assertEquals(new TreeSet<>(List.of("documentId", "submissionId", "sourceId", "status", "stateSince",
                "processingErrorCode", "ingestedAt", "pageCount", "sensitivityLevel")), keys(json.at("/stuckDocuments/0")));

        // If the levels can't be read, the level is left empty, never guessed; the problems stay.
        when(records.findMetadataSnapshots(any(), any(), anyList())).thenThrow(new IllegalStateException("db down"));
        var degraded = service.summary(TENANT);
        assertEquals(2, degraded.processingFailures().size());
        assertNull(degraded.processingFailures().get(0).sensitivityLevel());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theQueriesBehindTheseViewsNeverReadAFileName() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        JdbcOperationalRepository repository = new JdbcOperationalRepository(jdbc);
        repository.findRows(TENANT, new AuditQueryPort.Filter(null, null, null, null, null, null, null, null, null, null,
                null, "300000000001"), 51);
        repository.findProcessingFailures(TENANT, 100);
        repository.findStuckDocuments(TENANT, Instant.now(), Instant.now(), 100);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        for (String s : sql.getAllValues()) {
            assertFalse(s.contains("original_filename"), s);
            assertTrue(s.contains("page_count"), s);
        }
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
