package com.sdv.platform.web.ingestion;

import com.sdv.platform.application.business.RecordKey;
import com.sdv.platform.application.business.RecordReferences;
import com.sdv.platform.application.document.DocumentFinalizationService;
import com.sdv.platform.application.document.DocumentIntakeService;
import com.sdv.platform.application.port.AccessRequestRepository;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.DocumentIntakeRepository.DuplicateReceipt;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.application.port.SubmissionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.storage.StoragePlacementService;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.source.IngestionSourceAuthType;
import com.sdv.platform.domain.source.IngestionSourceStatus;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionStatus;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantGuardrails;
import com.sdv.platform.domain.tenant.TenantStatus;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.web.foundation.FoundationExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 0031: the three cases of "the same document arrives again", through the upload endpoint.
 * <ol>
 *   <li>A retry of the same delivery (same sourceDocumentId or idempotency key): "already received", unchanged.</li>
 *   <li>The same file in a new delivery for the same record from the same sending system: no second copy. 200 with
 *       the existing document, {@code DUPLICATE_ALREADY_STORED}, {@code duplicate: true}; recorded and audited; its
 *       manifest slot filled; a higher level raises the existing document, a lower or equal one is ignored.</li>
 *   <li>Another file (another checksum), or the same file under another record: stored as a new document.</li>
 * </ol>
 * Which documents count as "the same file for the same record" (tenant, sending system, the record's submissions,
 * not deleted) is the SQL's job; {@code DuplicateIntakePostgresTest} runs it on PostgreSQL.
 */
class DocumentDuplicateIntakeTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SUBMISSION = UUID.fromString("33333333-3333-3333-3333-333333333333");
    /** The earlier submission of the same record, holding the original. */
    private static final UUID EARLIER_SUBMISSION = UUID.fromString("33333333-3333-3333-3333-333333333300");
    private static final UUID ORIGINAL = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final byte[] PDF = "%PDF-1.7\nhello\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final String SHA = sha(PDF);
    private static final String RECORD_KEY = RecordKey.ofIdentifiers(List.of(new RecordKey.Part("contractNumber", "300000000001")));
    private static final String URL = "/api/v1/ingestion/submissions/" + SUBMISSION + "/documents";

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final DocumentIntakeRepository documents = mock(DocumentIntakeRepository.class);
    private final StoragePlacementService placement = mock(StoragePlacementService.class);
    private final StorageProvider storage = mock(StorageProvider.class);
    private final StreamingCryptoPort crypto = mock(StreamingCryptoPort.class);
    private final DocumentFinalizationService finalization = mock(DocumentFinalizationService.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final RecordReferences records = mock(RecordReferences.class);
    private final AccessRequestRepository levels = mock(AccessRequestRepository.class);
    private StorageVolume volume;
    private MockMvc mvc;

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Document original(SensitivityLevel level, DocumentStatus status) {
        return new Document(ORIGINAL, TENANT, EARLIER_SUBMISSION, SOURCE, "first-delivery", null, "contract.pdf",
                "application/pdf", SHA, (long) PDF.length, status, null, NOW.minusSeconds(86_400), null,
                NOW.minusSeconds(86_000), 1, null, null, level);
    }

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextFacade security = mock(SecurityContextFacade.class);
        when(security.requireIngestionSourcePrincipal()).thenReturn(new IngestionSourcePrincipal(TENANT, SOURCE, "producer"));
        DocumentIntakeService intake = new DocumentIntakeService(tenants, sources, submissions, documents, placement,
                List.of(storage), crypto, finalization, audit, records, levels, mock(PlatformTransactionManager.class), TestPdfs.UNCHECKED);
        mvc = MockMvcBuilders.standaloneSetup(new DocumentIntakeController(security, intake))
                .setControllerAdvice(new FoundationExceptionHandler())
                .build();

        when(tenants.findById(TENANT)).thenReturn(Optional.of(
                new Tenant(TENANT, "acme", "Acme", TenantStatus.ACTIVE, TenantGuardrails.defaults(), NOW, NOW)));
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(new IngestionSource(SOURCE, TENANT, "loans", "Loans",
                IngestionSourceStatus.ACTIVE, IngestionSourceAuthType.values()[0], null, null, NOW, NOW)));
        Submission submission = new Submission(SUBMISSION, TENANT, SOURCE, "sub-2", 4, "sha", SubmissionStatus.OPEN, NOW, NOW, null);
        when(submissions.findById(TENANT, SOURCE, SUBMISSION)).thenReturn(Optional.of(submission));
        when(documents.lockSubmission(TENANT, SOURCE, SUBMISSION)).thenReturn(submission);
        when(records.ofSubmission(TENANT, SOURCE, SUBMISSION)).thenReturn(
                new RecordReferences.ResolvedRecord(SOURCE, RECORD_KEY, List.of(EARLIER_SUBMISSION, SUBMISSION)));

        volume = new StorageVolume(UUID.randomUUID(), TENANT, "LOCAL_FILESYSTEM", "primary-a", "/tmp",
                StorageVolumeState.values()[0], 1, 80, 95, NOW, NOW);
        StoragePlacementService.PlacementDecision decision = mock(StoragePlacementService.PlacementDecision.class);
        when(decision.volume()).thenReturn(volume);
        when(placement.select(eq(TENANT), anyLong())).thenReturn(decision);
        when(storage.providerId()).thenReturn("LOCAL_FILESYSTEM");
        when(storage.store(eq(TENANT), eq(volume), any())).thenAnswer(call -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            call.getArgument(2, StorageProvider.ContentWriter.class).write(out);
            return new StorageProvider.StoredObject("tmp-object", out.size());
        });
        when(crypto.encrypt(eq(TENANT), any(), any())).thenAnswer(call -> {
            byte[] plain = call.getArgument(1, InputStream.class).readAllBytes();
            call.getArgument(2, OutputStream.class).write(plain);
            return new StreamingCryptoPort.CryptoEnvelope("p", 1, "k", "ref", "v1", plain.length, "c");
        });
        when(finalization.finalizeDocument(eq(TENANT), eq(SOURCE), eq(SUBMISSION), any(), any(), any()))
                .thenAnswer(call -> {
                    ArgumentCaptor<Document> stored = ArgumentCaptor.forClass(Document.class);
                    verify(documents, atLeastOnce()).insertDocument(stored.capture());
                    return stored.getValue();
                });
    }

    /** The original exists for this record, with this own level. */
    private void originalExists(SensitivityLevel level) {
        when(documents.findDuplicateOf(TENANT, SOURCE, List.of(EARLIER_SUBMISSION, SUBMISSION), SHA))
                .thenReturn(Optional.of(original(level, DocumentStatus.AVAILABLE)));
        when(levels.lockDocument(TENANT, ORIGINAL)).thenReturn(Optional.of(
                new AccessRequestRepository.LockedDocument(ORIGINAL, "AVAILABLE", level)));
        when(documents.findDocumentByIdForTenant(TENANT, ORIGINAL)).thenReturn(Optional.of(original(level, DocumentStatus.AVAILABLE)));
    }

    private ResultActions upload(String sourceDocumentId, String idempotencyKey, String level) throws Exception {
        MockHttpServletRequestBuilder request = post(URL).contentType("application/pdf").content(PDF)
                .header("X-SDV-Filename", "scan-again.pdf");
        if (sourceDocumentId != null) request = request.header("X-SDV-Source-Document-Id", sourceDocumentId);
        if (idempotencyKey != null) request = request.header("Idempotency-Key", idempotencyKey);
        if (level != null) request = request.header("X-SDV-Sensitivity-Level", level);
        return mvc.perform(request);
    }

    private List<AuditEvent> events() {
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, atLeast(0)).append(events.capture());
        return events.getAllValues();
    }

    private AuditEvent only(String action) {
        List<AuditEvent> matching = events().stream().filter(e -> e.action().equals(action)).toList();
        assertEquals(1, matching.size(), action + " in " + events().stream().map(AuditEvent::action).toList());
        return matching.getFirst();
    }

    private DuplicateReceipt recordedDuplicate() {
        ArgumentCaptor<DuplicateReceipt> receipt = ArgumentCaptor.forClass(DuplicateReceipt.class);
        verify(documents).insertDuplicateReceipt(receipt.capture());
        return receipt.getValue();
    }

    private void nothingStored() {
        verify(documents, never()).insertDocument(any());
        verify(documents, never()).insertStorageRef(any());
        verify(documents, never()).insertCryptoMetadata(any());
        verifyNoInteractions(finalization);
    }

    // ------------------------------------------------------------------------------------------------ case 1

    @Test
    void case1ARetryOfTheSameDeliveryIsAnsweredAsBeforeAndIsNoDuplicate() throws Exception {
        Document stored = new Document(UUID.randomUUID(), TENANT, SUBMISSION, SOURCE, "doc-1", null, "scan-again.pdf",
                "application/pdf", SHA, (long) PDF.length, DocumentStatus.AVAILABLE, null, NOW, null, NOW, 1, null, null,
                SensitivityLevel.STANDARD);
        when(documents.findBySourceDocumentId(TENANT, SOURCE, SUBMISSION, "doc-1")).thenReturn(Optional.of(stored));
        // Even with another copy of the content on the record, a retry is a retry.
        originalExists(SensitivityLevel.STANDARD);

        upload("doc-1", null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(stored.documentId().toString()))
                .andExpect(jsonPath("$.receiptAcknowledgement").value("RECEIVED"))
                .andExpect(jsonPath("$.idempotentReplay").value(true))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.exactDuplicateContent").value(false));
        verifyNoInteractions(storage, crypto, audit);
        verify(documents, never()).insertDuplicateReceipt(any());
    }

    // ------------------------------------------------------------------------------------------------ case 2

    @Test
    void case2TheSameFileInANewDeliveryStoresNothingAndAnswers200WithTheExistingDocument() throws Exception {
        originalExists(SensitivityLevel.CONFIDENTIAL);

        upload("doc-2", null, "CONFIDENTIAL").andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(ORIGINAL.toString()))
                .andExpect(jsonPath("$.sourceDocumentId").value("doc-2"))
                .andExpect(jsonPath("$.receiptAcknowledgement").value("DUPLICATE_ALREADY_STORED"))
                .andExpect(jsonPath("$.currentStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.exactDuplicateContent").value(true))
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andExpect(jsonPath("$.contentSha256").value(SHA))
                .andExpect(jsonPath("$.sensitivityLevel").value("CONFIDENTIAL"));

        nothingStored();
        // The encrypted bytes written while the checksum was worked out are removed before the answer.
        verify(storage).delete(TENANT, volume, "tmp-object");
        // The lookup is limited to this tenant, this sending system and this record's submissions, under the lock.
        verify(documents).lockDuplicateCheck(TENANT, SOURCE, RECORD_KEY, SHA);
        verify(documents).findDuplicateOf(TENANT, SOURCE, List.of(EARLIER_SUBMISSION, SUBMISSION), SHA);

        DuplicateReceipt receipt = recordedDuplicate();
        assertEquals(TENANT, receipt.tenantId());
        assertEquals(SOURCE, receipt.sourceId());
        assertEquals(SUBMISSION, receipt.submissionId());
        assertEquals("doc-2", receipt.sourceDocumentId());
        assertNull(receipt.producerIdempotencyKey());
        assertEquals(ORIGINAL, receipt.existingDocumentId());
        assertEquals(SHA, receipt.contentSha256());
        assertEquals(PDF.length, receipt.contentSizeBytes());
        assertEquals(SensitivityLevel.CONFIDENTIAL, receipt.sentLevel());
        assertEquals(DuplicateReceipt.LevelOutcome.KEPT, receipt.levelOutcome());

        AuditEvent event = only("DOCUMENT_DUPLICATE_RECEIVED");
        assertEquals("SOURCE", event.actorType());
        assertEquals("producer", event.actorId());
        assertEquals("DOCUMENT", event.targetType());
        assertEquals(ORIGINAL.toString(), event.targetId());
        String d = event.safeDetailsJson();
        for (String part : List.of("\"requestId\":\"doc-2\"", "\"sourceId\":\"" + SOURCE + "\"",
                "\"submissionId\":\"" + SUBMISSION + "\"", "\"recordKeySha256\":\"" + RecordKey.sha256(RECORD_KEY) + "\"",
                "\"contentSha256\":\"" + SHA + "\"", "\"existingDocumentId\":\"" + ORIGINAL + "\"",
                "\"duplicateId\":\"" + receipt.duplicateId() + "\"", "\"levelOutcome\":\"KEPT\"")) {
            assertTrue(d.contains(part), part + " in " + d);
        }
        // Nothing a person reads as a name: no file name (neither the original's nor the delivery's) and no identifier.
        assertFalse(d.contains("scan-again") || d.contains("contract.pdf") || d.contains("300000000001"), d);
        assertTrue(events().stream().noneMatch(e -> e.action().equals("DOCUMENT_RECEIVED")));
    }

    @Test
    void case2FillsTheExpectedManifestSlotAndCountsTowardsTheSubmission() throws Exception {
        originalExists(SensitivityLevel.STANDARD);

        upload("doc-2", null, null).andExpect(status().isOk());

        // The slot is marked received without a document of its own: the duplicate row names the document it points at.
        verify(documents).markExpectedDocumentReceived(eq(TENANT), eq(SUBMISSION), eq("doc-2"), isNull(), any());
        verify(documents).updateSubmissionAfterReceipt(eq(TENANT), eq(SOURCE), eq(SUBMISSION), eq(4), any());
        // The place is checked like a document's: the received count includes duplicates.
        verify(documents).countReceived(TENANT, SUBMISSION);
    }

    @Test
    void case2WithoutASourceDocumentIdIsNamedByItsIdempotencyKeyAndFillsNoSlot() throws Exception {
        originalExists(SensitivityLevel.STANDARD);

        upload(null, "key-7", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptAcknowledgement").value("DUPLICATE_ALREADY_STORED"));

        DuplicateReceipt receipt = recordedDuplicate();
        assertNull(receipt.sourceDocumentId());
        assertEquals("key-7", receipt.producerIdempotencyKey());
        verify(documents, never()).markExpectedDocumentReceived(any(), any(), any(), any(), any());
        assertTrue(only("DOCUMENT_DUPLICATE_RECEIVED").safeDetailsJson().contains("\"requestId\":\"key-7\""));
    }

    @Test
    void case2IsRefusedLikeADocumentWhenTheSubmissionHasNoPlaceLeft() throws Exception {
        originalExists(SensitivityLevel.STANDARD);
        when(documents.countReceived(TENANT, SUBMISSION)).thenReturn(4L);

        upload("doc-5", null, null).andExpect(status().is4xxClientError());

        verify(documents, never()).insertDuplicateReceipt(any());
        nothingStored();
        verify(storage).delete(TENANT, volume, "tmp-object");
    }

    // ---------------------------------------------------------------------------------------------- sensitivity

    @Test
    void aHigherLevelOnTheDuplicateRaisesTheExistingDocumentAsASourceRaise() throws Exception {
        originalExists(SensitivityLevel.CONFIDENTIAL);

        upload("doc-2", null, "HIGHLY_CONFIDENTIAL").andExpect(status().isOk())
                .andExpect(jsonPath("$.sensitivityLevel").value("HIGHLY_CONFIDENTIAL"));

        verify(levels).updateDocumentLevel(TENANT, ORIGINAL, SensitivityLevel.HIGHLY_CONFIDENTIAL);
        ArgumentCaptor<AccessRequestRepository.LevelChange> change = ArgumentCaptor.forClass(AccessRequestRepository.LevelChange.class);
        verify(levels).insertLevelChange(change.capture());
        assertEquals(AccessRequestRepository.LevelChange.Scope.DOCUMENT, change.getValue().scope());
        assertEquals(ORIGINAL, change.getValue().documentId());
        assertEquals(AccessRequestRepository.LevelChange.Direction.RAISED, change.getValue().direction());
        assertEquals(SensitivityLevel.CONFIDENTIAL, change.getValue().from());
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, change.getValue().to());
        assertEquals("producer", change.getValue().changedBy());
        assertNull(change.getValue().accessRequestId());

        AuditEvent raised = only("DOCUMENT_SENSITIVITY_RAISED");
        assertEquals("SOURCE", raised.actorType());
        assertEquals(ORIGINAL.toString(), raised.targetId());
        assertTrue(raised.safeDetailsJson().contains("\"from\":\"CONFIDENTIAL\"")
                && raised.safeDetailsJson().contains("\"to\":\"HIGHLY_CONFIDENTIAL\"")
                && raised.safeDetailsJson().contains("\"setBy\":\"SOURCE\""), raised.safeDetailsJson());
        assertEquals(DuplicateReceipt.LevelOutcome.RAISED, recordedDuplicate().levelOutcome());
        assertTrue(only("DOCUMENT_DUPLICATE_RECEIVED").safeDetailsJson().contains("\"levelOutcome\":\"RAISED\""));
        nothingStored();
    }

    @Test
    void aLowerOrEqualLevelOnTheDuplicateIsIgnoredAndAudited() throws Exception {
        for (String sent : new String[]{"STANDARD", "CONFIDENTIAL"}) {
            clearInvocations(documents, levels, audit, storage);
            originalExists(SensitivityLevel.CONFIDENTIAL);

            upload("doc-" + sent, null, sent).andExpect(status().isOk())
                    .andExpect(jsonPath("$.sensitivityLevel").value("CONFIDENTIAL"));

            verify(levels, never()).updateDocumentLevel(any(), any(), any());
            verify(levels, never()).insertLevelChange(any());
            assertTrue(events().stream().noneMatch(e -> e.action().equals("DOCUMENT_SENSITIVITY_RAISED")), sent);
            assertEquals(DuplicateReceipt.LevelOutcome.KEPT, recordedDuplicate().levelOutcome(), sent);
            String d = only("DOCUMENT_DUPLICATE_RECEIVED").safeDetailsJson();
            assertTrue(d.contains("\"sentLevel\":\"" + sent + "\"") && d.contains("\"levelOutcome\":\"KEPT\"")
                    && d.contains("\"documentLevel\":\"CONFIDENTIAL\""), d);
        }
    }

    // ----------------------------------------------------------------------------- retries of a duplicate delivery

    @Test
    void aRetryOfADuplicateDeliveryGetsTheSameAnswerAndIsNotCountedAgain() throws Exception {
        DuplicateReceipt earlier = new DuplicateReceipt(UUID.randomUUID(), TENANT, SOURCE, SUBMISSION, "doc-2", null,
                ORIGINAL, SHA, PDF.length, SensitivityLevel.CONFIDENTIAL, DuplicateReceipt.LevelOutcome.KEPT, NOW);
        when(documents.findDuplicateReceipt(TENANT, SUBMISSION, "doc-2", null)).thenReturn(Optional.of(earlier));
        when(documents.findDocumentByIdForTenant(TENANT, ORIGINAL))
                .thenReturn(Optional.of(original(SensitivityLevel.CONFIDENTIAL, DocumentStatus.AVAILABLE)));

        upload("doc-2", null, "CONFIDENTIAL").andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(ORIGINAL.toString()))
                .andExpect(jsonPath("$.receiptAcknowledgement").value("DUPLICATE_ALREADY_STORED"))
                .andExpect(jsonPath("$.idempotentReplay").value(true))
                .andExpect(jsonPath("$.duplicate").value(true));

        // Nothing written anywhere, not even briefly, and no second audit event (the report counts it once).
        verifyNoInteractions(storage, crypto, audit, levels);
        verify(documents, never()).insertDuplicateReceipt(any());

        upload("doc-2", null, "HIGHLY_CONFIDENTIAL").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SDV-SEN-003"));
        mvc.perform(post(URL).contentType("application/pdf").content("%PDF-1.7\nother\n%%EOF".getBytes(StandardCharsets.US_ASCII))
                        .header("X-SDV-Filename", "x.pdf").header("X-SDV-Source-Document-Id", "doc-2")
                        .header("X-SDV-Sensitivity-Level", "CONFIDENTIAL"))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------------------ stored normally (no flag)

    @Test
    void case3AndOtherRecordsAreStoredAsNewDocumentsWithoutAFlag() throws Exception {
        // Nothing with this checksum on this record (another file, or the same file under another record).
        upload("doc-2", null, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptAcknowledgement").value("RECEIVED"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.exactDuplicateContent").value(false));

        verify(documents).insertDocument(any());
        verify(documents, never()).insertDuplicateReceipt(any());
        verify(storage, never()).delete(any(), any(), any());
        only("DOCUMENT_RECEIVED");
    }

    @Test
    void anOriginalThatIsBeingDeletedNoLongerCountsSoTheFileIsStoredAgain() throws Exception {
        // Found, but the retention cleanup took it before its row could be locked.
        when(documents.findDuplicateOf(TENANT, SOURCE, List.of(EARLIER_SUBMISSION, SUBMISSION), SHA))
                .thenReturn(Optional.of(original(SensitivityLevel.STANDARD, DocumentStatus.AVAILABLE)));
        for (String gone : new String[]{"RETENTION_DELETING", "EXPIRED_DELETED"}) {
            clearInvocations(documents, audit, storage);
            when(levels.lockDocument(TENANT, ORIGINAL)).thenReturn(Optional.of(
                    new AccessRequestRepository.LockedDocument(ORIGINAL, gone, SensitivityLevel.STANDARD)));

            upload("doc-" + gone, null, null).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.receiptAcknowledgement").value("RECEIVED"));

            verify(documents).insertDocument(any());
            verify(documents, never()).insertDuplicateReceipt(any());
        }
    }

    // ------------------------------------------------------- task 0032 (0): a corrected document sent again

    /** The record's document after a permanent correction: its checksum now is another; SHA is what it was before. */
    private static final String CORRECTED_SHA = "c".repeat(64);

    private Document corrected(SensitivityLevel level, DocumentStatus status) {
        return new Document(ORIGINAL, TENANT, EARLIER_SUBMISSION, SOURCE, "first-delivery", null, "contract.pdf",
                "application/pdf", CORRECTED_SHA, 900L, status, null, NOW.minusSeconds(86_400), null,
                NOW.minusSeconds(86_000), 1, null, null, level);
    }

    private void correctedExists(SensitivityLevel level, String lockedStatus) {
        when(documents.findCorrectedOriginalOf(TENANT, SOURCE, List.of(EARLIER_SUBMISSION, SUBMISSION), SHA))
                .thenReturn(Optional.of(corrected(level, DocumentStatus.AVAILABLE)));
        when(levels.lockDocument(TENANT, ORIGINAL)).thenReturn(Optional.of(
                new AccessRequestRepository.LockedDocument(ORIGINAL, lockedStatus, level)));
        when(documents.findDocumentByIdForTenant(TENANT, ORIGINAL)).thenReturn(Optional.of(corrected(level, DocumentStatus.AVAILABLE)));
    }

    @Test
    void anUncorrectedCopyOfACorrectedDocumentIsNotStoredAndIsAnsweredWithTheCorrectedDocument() throws Exception {
        correctedExists(SensitivityLevel.CONFIDENTIAL, "AVAILABLE");

        upload("doc-2", null, "CONFIDENTIAL").andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(ORIGINAL.toString()))
                .andExpect(jsonPath("$.sourceDocumentId").value("doc-2"))
                .andExpect(jsonPath("$.receiptAcknowledgement").value("DUPLICATE_ALREADY_STORED"))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                // The corrected version is what Vero keeps: its checksum, not the arrival's.
                .andExpect(jsonPath("$.contentSha256").value(CORRECTED_SHA));

        // The original after correction is not stored: no document, storage or key row, and the bytes are removed.
        nothingStored();
        verify(storage).delete(TENANT, volume, "tmp-object");
        // Same scope as an exact duplicate (tenant, sending system, record's submissions), under the same lock.
        verify(documents).lockDuplicateCheck(TENANT, SOURCE, RECORD_KEY, SHA);
        verify(documents).findCorrectedOriginalOf(TENANT, SOURCE, List.of(EARLIER_SUBMISSION, SUBMISSION), SHA);
        // The corrected document is unchanged (same level: nothing raised).
        verify(levels, never()).updateDocumentLevel(any(), any(), any());

        DuplicateReceipt receipt = recordedDuplicate();
        assertEquals(ORIGINAL, receipt.existingDocumentId());
        assertEquals(SHA, receipt.contentSha256(), "the arrival's checksum, so a retry finds it and the report counts it");
        assertEquals("doc-2", receipt.sourceDocumentId());
        verify(documents).markExpectedDocumentReceived(eq(TENANT), eq(SUBMISSION), eq("doc-2"), isNull(), any(Instant.class));

        AuditEvent event = only("DOCUMENT_UNCORRECTED_COPY_RECEIVED");
        assertEquals("SOURCE", event.actorType());
        assertEquals(ORIGINAL.toString(), event.targetId());
        assertTrue(event.safeDetailsJson().contains("\"contentSha256\":\"" + SHA + "\""), event.safeDetailsJson());
        assertTrue(event.safeDetailsJson().contains("\"correctedContentSha256\":\"" + CORRECTED_SHA + "\""), event.safeDetailsJson());
        assertTrue(event.safeDetailsJson().contains("\"duplicateId\":\"" + receipt.duplicateId() + "\""));
        assertFalse(event.safeDetailsJson().contains(".pdf"), "no file name");
        assertFalse(event.safeDetailsJson().contains("300000000001"), "no contract number");
        assertTrue(events().stream().noneMatch(e -> e.action().equals("DOCUMENT_DUPLICATE_RECEIVED")));
        // Task 0033 (0.1): the stewards' notice is stored, made from this audit event, for this record.
        verify(documents).insertUncorrectedCopyNotice(any(UUID.class), eq(TENANT), eq(SOURCE), eq(SUBMISSION),
                eq(event.auditEventId()), any(Instant.class));
    }

    @Test
    void anExactDuplicateMakesNoStewardNotice() throws Exception {
        // Task 0033 (0.1): only an uncorrected copy is a security event; an ordinary exact duplicate tells no one.
        originalExists(SensitivityLevel.CONFIDENTIAL);
        upload("doc-2", null, "CONFIDENTIAL").andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptAcknowledgement").value("DUPLICATE_ALREADY_STORED"));
        verify(documents, never()).insertUncorrectedCopyNotice(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anUncorrectedCopyWithAHigherLevelRaisesTheCorrectedDocumentLikeAnyDuplicate() throws Exception {
        correctedExists(SensitivityLevel.STANDARD, "AVAILABLE");
        upload("doc-2", null, "HIGHLY_CONFIDENTIAL").andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptAcknowledgement").value("DUPLICATE_ALREADY_STORED"))
                .andExpect(jsonPath("$.sensitivityLevel").value("HIGHLY_CONFIDENTIAL"));
        verify(levels).updateDocumentLevel(TENANT, ORIGINAL, SensitivityLevel.HIGHLY_CONFIDENTIAL);
        only("DOCUMENT_SENSITIVITY_RAISED");
        only("DOCUMENT_UNCORRECTED_COPY_RECEIVED");
        nothingStored();
    }

    @Test
    void anExactDuplicateIsFoundFirstAndAGoneCorrectedDocumentNoLongerCounts() throws Exception {
        // The exact duplicate wins: the corrected lookup isn't needed.
        originalExists(SensitivityLevel.STANDARD);
        upload("doc-2", null, null).andExpect(status().isOk());
        verify(documents, never()).findCorrectedOriginalOf(any(), any(), any(), any());
        only("DOCUMENT_DUPLICATE_RECEIVED");

        // A corrected document deleted, or being deleted, by the cleanup before its row was locked: stored as new.
        reset(documents);
        setUp();
        clearInvocations(audit, storage);
        correctedExists(SensitivityLevel.STANDARD, "RETENTION_DELETING");
        upload("doc-3", null, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptAcknowledgement").value("RECEIVED"));
        verify(documents).insertDocument(any());
        verify(documents, never()).insertDuplicateReceipt(any());
    }
}
