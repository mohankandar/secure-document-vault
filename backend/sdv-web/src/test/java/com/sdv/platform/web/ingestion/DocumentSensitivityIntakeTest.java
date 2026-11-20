package com.sdv.platform.web.ingestion;

import com.sdv.platform.application.document.DocumentFinalizationService;
import com.sdv.platform.application.document.DocumentIntakeService;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.application.port.SubmissionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.storage.StoragePlacementService;
import com.sdv.platform.application.submission.SubmissionService;
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
 * Task 0024: the sending system sets each document's sensitivity level on the document upload, the only intake path
 * that creates documents. Missing gives STANDARD; anything else must be an exact name, or the upload is refused (400)
 * before anything is read or stored. Record levels are refused until the owner decides where they live.
 */
class DocumentSensitivityIntakeTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SUBMISSION = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final byte[] PDF = "%PDF-1.7\nhello\n%%EOF".getBytes(StandardCharsets.US_ASCII);
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
    private final SubmissionService submissionService = mock(SubmissionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextFacade security = mock(SecurityContextFacade.class);
        when(security.requireIngestionSourcePrincipal()).thenReturn(new IngestionSourcePrincipal(TENANT, SOURCE, "producer"));
        DocumentIntakeService intake = new DocumentIntakeService(tenants, sources, submissions, documents, placement,
                List.of(storage), crypto, finalization, audit, mock(PlatformTransactionManager.class));
        mvc = MockMvcBuilders.standaloneSetup(
                        new DocumentIntakeController(security, intake),
                        new SubmissionController(security, submissionService))
                .setControllerAdvice(new FoundationExceptionHandler())
                .build();

        when(tenants.findById(TENANT)).thenReturn(Optional.of(
                new Tenant(TENANT, "acme", "Acme", TenantStatus.ACTIVE, TenantGuardrails.defaults(), NOW, NOW)));
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(new IngestionSource(SOURCE, TENANT, "loans", "Loans",
                IngestionSourceStatus.ACTIVE, IngestionSourceAuthType.values()[0], null, null, NOW, NOW)));
        Submission submission = new Submission(SUBMISSION, TENANT, SOURCE, "sub-1", 5, "sha", SubmissionStatus.OPEN, NOW, NOW, null);
        when(submissions.findById(TENANT, SOURCE, SUBMISSION)).thenReturn(Optional.of(submission));
        when(documents.lockSubmission(TENANT, SOURCE, SUBMISSION)).thenReturn(submission);

        StorageVolume volume = new StorageVolume(UUID.randomUUID(), TENANT, "LOCAL_FILESYSTEM", "primary-a", "/tmp",
                StorageVolumeState.values()[0], 1, 80, 95, NOW, NOW);
        StoragePlacementService.PlacementDecision decision = mock(StoragePlacementService.PlacementDecision.class);
        when(decision.volume()).thenReturn(volume);
        when(placement.select(eq(TENANT), anyLong())).thenReturn(decision);
        when(storage.providerId()).thenReturn("LOCAL_FILESYSTEM");
        when(storage.store(eq(TENANT), eq(volume), any())).thenAnswer(call -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            call.getArgument(2, StorageProvider.ContentWriter.class).write(out);
            return new StorageProvider.StoredObject("obj", out.size());
        });
        when(crypto.encrypt(eq(TENANT), any(), any())).thenAnswer(call -> {
            byte[] plain = call.getArgument(1, InputStream.class).readAllBytes();
            call.getArgument(2, OutputStream.class).write(plain);
            return new StreamingCryptoPort.CryptoEnvelope("p", 1, "k", "ref", "v1", plain.length, "c");
        });
        // Finalization hands back what was stored, as the real one does after re-reading the row.
        when(finalization.finalizeDocument(eq(TENANT), eq(SOURCE), eq(SUBMISSION), any(), any(), any()))
                .thenAnswer(call -> storedDocument());
    }

    private Document storedDocument() {
        ArgumentCaptor<Document> stored = ArgumentCaptor.forClass(Document.class);
        verify(documents, atLeastOnce()).insertDocument(stored.capture());
        return stored.getValue();
    }

    private ResultActions upload(String level) throws Exception {
        MockHttpServletRequestBuilder request = post(URL).contentType("application/pdf").content(PDF)
                .header("X-SDV-Source-Document-Id", "doc-1").header("X-SDV-Filename", "a.pdf");
        if (level != null) request = request.header("X-SDV-Sensitivity-Level", level);
        return mvc.perform(request);
    }

    private String auditDetails() {
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertEquals("DOCUMENT_RECEIVED", event.getValue().action());
        return event.getValue().safeDetailsJson();
    }

    @Test
    void aMissingLevelIsStoredAsStandard() throws Exception {
        upload(null).andExpect(status().isCreated()).andExpect(jsonPath("$.sensitivityLevel").value("STANDARD"));
        assertEquals(SensitivityLevel.STANDARD, storedDocument().sensitivityLevel());
        assertTrue(auditDetails().contains("\"sensitivityLevel\":\"STANDARD\""));
    }

    @Test
    void eachValidLevelIsStoredAndAudited() throws Exception {
        for (SensitivityLevel level : SensitivityLevel.values()) {
            clearInvocations(documents, audit);
            upload(level.name()).andExpect(status().isCreated()).andExpect(jsonPath("$.sensitivityLevel").value(level.name()));
            assertEquals(level, storedDocument().sensitivityLevel());
            assertTrue(auditDetails().contains("\"sensitivityLevel\":\"" + level.name() + "\""), level.name());
        }
    }

    @Test
    void unknownWronglyCasedOrBlankLevelsAreRefusedWith400BeforeAnythingIsStored() throws Exception {
        for (String bad : List.of("confidential", "Confidential", "SECRET", "HIGHLY CONFIDENTIAL", " CONFIDENTIAL", "", "1")) {
            upload(bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SDV-SEN-001"))
                    .andExpect(jsonPath("$.message").value(
                            "X-SDV-Sensitivity-Level must be one of STANDARD, CONFIDENTIAL, HIGHLY_CONFIDENTIAL "
                                    + "(exactly, in capitals); leave it out for STANDARD."));
        }
        verifyNoInteractions(storage, crypto, documents, audit, tenants);
    }

    @Test
    void aRetryMustRepeatTheLevelItWasFirstSentWith() throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PDF));
        Document existing = new Document(UUID.randomUUID(), TENANT, SUBMISSION, SOURCE, "doc-1", null, "a.pdf",
                "application/pdf", sha, (long) PDF.length, DocumentStatus.AVAILABLE, null, NOW, null, NOW, 1, null, null,
                SensitivityLevel.CONFIDENTIAL);
        when(documents.findBySourceDocumentId(TENANT, SOURCE, SUBMISSION, "doc-1")).thenReturn(Optional.of(existing));

        upload("CONFIDENTIAL").andExpect(status().isOk()).andExpect(jsonPath("$.idempotentReplay").value(true))
                .andExpect(jsonPath("$.sensitivityLevel").value("CONFIDENTIAL"));
        upload(null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SDV-SEN-003"));
        upload("HIGHLY_CONFIDENTIAL").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SDV-SEN-003"));
        verify(documents, never()).insertDocument(any());
    }

    @Test
    void aRecordLevelIsRefusedOnTheUploadBecauseItBelongsOnTheSubmission() throws Exception {
        mvc.perform(post(URL).contentType("application/pdf").content(PDF)
                        .header("X-SDV-Source-Document-Id", "doc-1").header("X-SDV-Filename", "a.pdf")
                        .header("X-SDV-Record-Sensitivity-Level", "CONFIDENTIAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SDV-SEN-005"))
                .andExpect(jsonPath("$.message").value(RecordSensitivityGuard.MESSAGE));
        verifyNoInteractions(storage, documents, audit);
    }

    @Test
    void theSubmissionRefusesADocumentLevelInsteadOfIgnoringIt() throws Exception {
        String base = "{\"sourceSubmissionId\":\"s1\",\"expectedDocumentCount\":1,\"metadata\":{}";
        mvc.perform(post("/api/v1/ingestion/submissions").contentType("application/json")
                        .content(base + ",\"sensitivityLevel\":\"CONFIDENTIAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SDV-SEN-006"))
                .andExpect(jsonPath("$.message").value("sensitivityLevel is set on each document upload, with the "
                        + "X-SDV-Sensitivity-Level header, not on the submission."));
        verifyNoInteractions(submissionService);
    }

    /** Task 0024, part 2: the submission carries the record level; the service checks it before anything is stored. */
    @Test
    void theSubmissionPassesTheRecordLevelOnAsWritten() throws Exception {
        String base = "{\"sourceSubmissionId\":\"s1\",\"expectedDocumentCount\":1,\"metadata\":{}";
        ArgumentCaptor<com.sdv.platform.application.submission.CreateSubmissionCommand> command =
                ArgumentCaptor.forClass(com.sdv.platform.application.submission.CreateSubmissionCommand.class);
        when(submissionService.create(any(), any(), any(), any(), any())).thenThrow(new IllegalArgumentException("stub"));
        for (String json : List.of("\"CONFIDENTIAL\"", "\"confidential\"", "1", "null", "{}")) {
            clearInvocations(submissionService);
            mvc.perform(post("/api/v1/ingestion/submissions").contentType("application/json")
                    .content(base + ",\"recordSensitivityLevel\":" + json + "}"));
            verify(submissionService).create(eq(TENANT), eq(SOURCE), command.capture(), eq("producer"), any());
        }
        // A JSON string is passed as its text; anything else as written, so the service refuses it like any unknown value.
        assertEquals(List.of("CONFIDENTIAL", "confidential", "1", "null", "{}"), command.getAllValues().stream()
                .map(com.sdv.platform.application.submission.CreateSubmissionCommand::recordSensitivityLevel).toList());

        clearInvocations(submissionService);
        mvc.perform(post("/api/v1/ingestion/submissions").contentType("application/json").content(base + "}"));
        verify(submissionService).create(eq(TENANT), eq(SOURCE), command.capture(), eq("producer"), any());
        assertNull(command.getValue().recordSensitivityLevel(), "left out: no record level");
    }
}
