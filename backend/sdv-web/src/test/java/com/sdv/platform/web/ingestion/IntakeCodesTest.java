package com.sdv.platform.web.ingestion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.business.RecordReferences;
import com.sdv.platform.application.document.DocumentFinalizationService;
import com.sdv.platform.application.document.DocumentIntakeService;
import com.sdv.platform.application.port.AccessRequestRepository;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.RecordSensitivityRepository;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.application.port.SubmissionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.storage.StoragePlacementService;
import com.sdv.platform.application.submission.SubmissionService;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.source.IngestionSourceAuthType;
import com.sdv.platform.domain.source.IngestionSourceStatus;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import com.sdv.platform.domain.submission.ExpectedDocumentState;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionExpectedDocument;
import com.sdv.platform.domain.submission.SubmissionStatus;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantGuardrails;
import com.sdv.platform.domain.tenant.TenantStatus;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.web.foundation.FoundationExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 0039: SDV-INT-004 to SDV-INT-018, each through the real intake services and Vero's error handler: the status,
 * the code, the fixed sentence (where the catalog gives one), the reference, and one log line with the exact cause at
 * the level the catalog sets (INFO for a refusal, WARN for INT-005, ERROR for INT-010). No value sent is in an answer
 * or a log line.
 */
class IntakeCodesTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SUBMISSION = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DOCUMENT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final byte[] PDF = "%PDF-1.7\nhello\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final String UPLOAD = "/api/v1/ingestion/submissions/" + SUBMISSION + "/documents";
    /** A value that must never be repeated in an answer or a log line. */
    private static final String SENT_VALUE = "value-9d2e-do-not-echo";

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final DocumentIntakeRepository documents = mock(DocumentIntakeRepository.class);
    private final MetadataFieldDefinitionRepository definitions = mock(MetadataFieldDefinitionRepository.class);
    private final StoragePlacementService placement = mock(StoragePlacementService.class);
    private final StorageProvider storage = mock(StorageProvider.class);
    private final StreamingCryptoPort crypto = mock(StreamingCryptoPort.class);
    private final RecordReferences records = mock(RecordReferences.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextFacade security = mock(SecurityContextFacade.class);
        when(security.requireIngestionSourcePrincipal()).thenReturn(new IngestionSourcePrincipal(TENANT, SOURCE, "producer"));
        DocumentIntakeService intake = new DocumentIntakeService(tenants, sources, submissions, documents, placement,
                List.of(storage), crypto, mock(DocumentFinalizationService.class), mock(AuditEventPort.class), records,
                mock(AccessRequestRepository.class), mock(PlatformTransactionManager.class), TestPdfs.UNCHECKED);
        SubmissionService submissionService = new SubmissionService(tenants, sources, definitions, submissions, documents,
                mock(RecordSensitivityRepository.class), mock(AuditEventPort.class), com.sdv.platform.application.port.RecordValuesPort.NONE, null);
        mvc = MockMvcBuilders.standaloneSetup(new DocumentIntakeController(security, intake),
                        new SubmissionController(security, submissionService))
                .setControllerAdvice(new FoundationExceptionHandler())
                .addFilters(new com.sdv.platform.web.config.CorrelationIdFilter()).build();

        tenant(TenantGuardrails.defaults());
        source(IngestionSourceStatus.ACTIVE);
        Submission submission = new Submission(SUBMISSION, TENANT, SOURCE, "sub-1", 2, "sha", SubmissionStatus.OPEN, NOW, NOW, null);
        when(submissions.findById(TENANT, SOURCE, SUBMISSION)).thenReturn(Optional.of(submission));
        when(documents.lockSubmission(TENANT, SOURCE, SUBMISSION)).thenReturn(submission);
        when(records.ofSubmission(TENANT, SOURCE, SUBMISSION)).thenReturn(new RecordReferences.ResolvedRecord(
                SOURCE, SUBMISSION, List.of(SUBMISSION)));
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
        when(crypto.encrypt(org.mockito.ArgumentMatchers.argThat(b -> b != null && b.entityId().equals(TENANT) && b.sealedId() == null), any(), any())).thenAnswer(call -> {
            byte[] plain = call.getArgument(1, InputStream.class).readAllBytes();
            call.getArgument(2, OutputStream.class).write(plain);
            return new StreamingCryptoPort.CryptoEnvelope("p", 1, "k", "ref", "v1", plain.length, "c");
        });

        logs.start();
        ((Logger) LoggerFactory.getLogger(com.sdv.platform.web.foundation.ErrorReplies.class)).addAppender(logs);
    }

    @AfterEach
    void nothingSentIsRepeated() {
        ((Logger) LoggerFactory.getLogger(com.sdv.platform.web.foundation.ErrorReplies.class)).detachAppender(logs);
        for (ILoggingEvent e : logs.list) assertFalse(e.getFormattedMessage().contains(SENT_VALUE), e.getFormattedMessage());
    }

    private void tenant(TenantGuardrails guardrails) {
        when(tenants.findById(TENANT)).thenReturn(Optional.of(
                new Tenant(TENANT, "acme", "Acme", TenantStatus.ACTIVE, guardrails, NOW, NOW)));
    }

    private void source(IngestionSourceStatus status) {
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(new IngestionSource(SOURCE, TENANT, "loans", "Loans",
                status, IngestionSourceAuthType.values()[0], null, null, NOW, NOW)));
    }

    private static MetadataFieldDefinition field(String key, MetadataFieldType type, boolean required, String rules) {
        return new MetadataFieldDefinition(UUID.randomUUID(), TENANT, SOURCE, key, 1, key, type, required, false, false,
                MetadataNormalization.NONE, MetadataSearchMode.EXACT, rules, true, NOW, null, NOW);
    }

    private static MockHttpServletRequestBuilder upload(byte[] body) {
        return post(UPLOAD).contentType("application/pdf").content(body).header("X-SDV-Filename", "a.pdf");
    }

    private static MockHttpServletRequestBuilder create(String body) {
        return post("/api/v1/ingestion/submissions").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** The answer: status, code and (when given) message; one log line at {@code level} with the code and cause. */
    private JsonNode expect(RequestBuilder request, int status, String code, String message, Level level, String cause)
            throws Exception {
        logs.list.clear();
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        JsonNode body = json.readTree(response.getContentAsString());
        assertEquals(code, body.get("code").asString(), body.toString());
        if (message != null) assertEquals(message, body.get("message").asString());
        assertFalse(body.get("reference").asString().isBlank());
        assertFalse(body.toString().contains(SENT_VALUE), "a value sent is in the answer");
        List<ILoggingEvent> lines = logs.list.stream().filter(e -> e.getFormattedMessage().contains("code=" + code)).toList();
        assertEquals(1, lines.size(), "one log line for " + code + ": " + logs.list);
        assertEquals(level, lines.getFirst().getLevel(), lines.getFirst().getFormattedMessage());
        String line = lines.getFirst().getFormattedMessage();
        assertTrue(line.contains("reference=" + body.get("reference").asString()), line);
        if (cause != null) assertTrue(line.contains(cause), "the log line should name " + cause + ": " + line);
        return body;
    }

    @Test
    void int004NoSuchSubmission() throws Exception {
        UUID other = UUID.randomUUID();
        String sentence = "No submission of this sending system has this ID. Create the submission first, or check the ID.";
        expect(post("/api/v1/ingestion/submissions/" + other + "/documents").contentType("application/pdf").content(PDF)
                .header("X-SDV-Filename", "a.pdf").header("X-SDV-Source-Document-Id", "doc-1"),
                404, "SDV-INT-004", sentence, Level.INFO, "submissionId=" + other);
        expect(get("/api/v1/ingestion/submissions/" + other), 404, "SDV-INT-004", sentence, Level.INFO, null);
        expect(get("/api/v1/ingestion/submissions/" + other + "/documents"), 404, "SDV-INT-004", sentence, Level.INFO, null);
        expect(get("/api/v1/ingestion/submissions").param("sourceSubmissionId", "nope"), 404, "SDV-INT-004", sentence, Level.INFO, null);
    }

    @Test
    void int005UnknownAndPausedSendingSystemGetOneAnswer() throws Exception {
        String sentence = "This sending system is paused or not set up in Vero. Nothing was stored.";
        source(IngestionSourceStatus.DISABLED);
        JsonNode paused = expect(upload(PDF).header("X-SDV-Source-Document-Id", "doc-1"), 403, "SDV-INT-005", sentence,
                Level.WARN, "sourceId=" + SOURCE + " status=DISABLED");
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.empty());
        JsonNode unknown = expect(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1}"), 403, "SDV-INT-005",
                sentence, Level.WARN, "not a sending system of tenant=" + TENANT);
        assertEquals(paused.get("message"), unknown.get("message"));
    }

    @Test
    void int006SubmissionRequestIncomplete() throws Exception {
        expect(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":0}"), 400, "SDV-INT-006",
                "expectedDocumentCount must be at least 1.", Level.INFO, "field=expectedDocumentCount");
        expect(create("{\"expectedDocumentCount\":1}"), 400, "SDV-INT-006", "sourceSubmissionId is required.",
                Level.INFO, "field=sourceSubmissionId missing");
        expect(create("{\"sourceSubmissionId\":\"" + "x".repeat(201) + "\",\"expectedDocumentCount\":1}"), 400,
                "SDV-INT-006", "sourceSubmissionId exceeds 200 characters.", Level.INFO, "too long");
    }

    @Test
    void int007DocumentListNotValid() throws Exception {
        expect(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1,\"expectedDocuments\":[{\"sourceDocumentId\":\"a\"},{\"sourceDocumentId\":\"b\"}]}"),
                400, "SDV-INT-007", "Expected manifest entries cannot exceed expectedDocumentCount.", Level.INFO, "more listed");
        expect(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":2,\"expectedDocuments\":[{\"sourceDocumentId\":\"a\"},{\"sourceDocumentId\":\"a\"}]}"),
                400, "SDV-INT-007", null, Level.INFO, "a listed ID twice");
        expect(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":101}"), 400, "SDV-INT-007",
                "expectedDocumentCount exceeds the configured submission limit of 100.", Level.INFO, "limit=100");
    }

    @Test
    void int008MetadataFieldUnknownOrMissing() throws Exception {
        when(definitions.findCurrent(TENANT, SOURCE)).thenReturn(List.of(field("contractNumber", MetadataFieldType.TEXT, true, "{}")));
        expect(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1,\"metadata\":{\"contractNumber\":\"1\",\"colour\":\"" + SENT_VALUE + "\"}}"),
                400, "SDV-INT-008", "Metadata field 'colour' is not configured for this ingestion source.", Level.INFO, "field=colour unknown");
        expect(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1,\"metadata\":{}}"),
                400, "SDV-INT-008", "Required metadata field 'contractNumber' is missing.", Level.INFO, "field=contractNumber missing");
        expect(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1,\"metadata\":{\"contractNumber\":{\"a\":1}}}"),
                400, "SDV-INT-008", "Metadata field 'contractNumber' must be a scalar JSON value.", Level.INFO, "not a scalar");
    }

    @Test
    void int009MetadataValueNotValidNamesTheFieldAndTheRuleNeverTheValue() throws Exception {
        when(definitions.findCurrent(TENANT, SOURCE)).thenReturn(List.of(
                field("amount", MetadataFieldType.INTEGER, false, "{}"),
                field("code", MetadataFieldType.TEXT, false, "{\"maxLength\":3}")));
        expect(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1,\"metadata\":{\"amount\":\"" + SENT_VALUE + "\"}}"),
                400, "SDV-INT-009", "Metadata field 'amount' is not a valid INTEGER value.", Level.INFO, "field=amount is not a valid INTEGER value");
        expect(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1,\"metadata\":{\"code\":\"" + SENT_VALUE + "\"}}"),
                400, "SDV-INT-009", "Metadata field 'code' exceeds its configured maximum length.", Level.INFO,
                "field=code exceeds its configured maximum length");
    }

    @Test
    void int010ABrokenFieldRuleIsVerosSetupAnswered500AtError() throws Exception {
        when(definitions.findCurrent(TENANT, SOURCE)).thenReturn(List.of(field("code", MetadataFieldType.TEXT, false, "{\"pattern\":\"[\"}")));
        expect(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1,\"metadata\":{\"code\":\"" + SENT_VALUE + "\"}}"),
                500, "SDV-INT-010", "Vero's setup for field 'code' is broken, so this submission can't be checked. Nothing was "
                        + "stored. Your IT team has been told.", Level.ERROR, "field=code rule: Metadata field 'code' has an invalid configured pattern.");
    }

    @Test
    void int011SubmissionRetryDiffersFromTheFirst() throws Exception {
        when(submissions.findBySourceIdentity(TENANT, SOURCE, "s-1")).thenReturn(Optional.of(
                new Submission(SUBMISSION, TENANT, SOURCE, "s-1", 1, "another-fingerprint", SubmissionStatus.OPEN, NOW, NOW, null)));
        expect(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1}"), 409, "SDV-INT-011",
                "sourceSubmissionId already exists with a different submission create request.", Level.INFO, "submissionId=" + SUBMISSION);
    }

    @Test
    void int012UploadHasNoIdentity() throws Exception {
        expect(upload(PDF), 400, "SDV-INT-012", "Idempotency-Key is required when sourceDocumentId is not supplied.",
                Level.INFO, "no document ID");
        when(submissions.findExpectedDocuments(TENANT, SUBMISSION)).thenReturn(List.of(
                expected("doc-1"), expected("doc-2")));
        expect(upload(PDF).header("X-SDV-Source-Document-Id", "doc-9"), 400, "SDV-INT-012",
                "sourceDocumentId is not part of the submission's declared complete manifest.", Level.INFO, "not in the complete list");
    }

    private static SubmissionExpectedDocument expected(String id) {
        return new SubmissionExpectedDocument(UUID.randomUUID(), TENANT, SUBMISSION, id, ExpectedDocumentState.EXPECTED, null, NOW, null);
    }

    @Test
    void int013UploadHeadersNotValidNameTheHeader() throws Exception {
        expect(post(UPLOAD).contentType("application/pdf").content(PDF).header("X-SDV-Filename", "../" + SENT_VALUE + ".pdf")
                        .header("X-SDV-Source-Document-Id", "doc-1"),
                400, "SDV-INT-013", "Filename must be a simple display filename, not a filesystem path.", Level.INFO, "header=X-SDV-Filename");
        expect(upload(PDF).header("X-SDV-Source-Document-Id", "d".repeat(201)), 400, "SDV-INT-013",
                "X-SDV-Source-Document-Id exceeds 200 characters.", Level.INFO, "header=X-SDV-Source-Document-Id");
    }

    @Test
    void int014PdfTooLargeGivesTheLimit() throws Exception {
        tenant(new TenantGuardrails(10, 2_000, 100, 1_000_000, 24, 90, 95, null));
        expect(upload(PDF).header("X-SDV-Source-Document-Id", "doc-1"), 413, "SDV-INT-014",
                "PDF exceeds the configured maximum of 10 bytes.", Level.INFO, "limitBytes=10");
    }

    @Test
    void int015NotAPdf() throws Exception {
        expect(upload(("hello " + SENT_VALUE).getBytes(StandardCharsets.US_ASCII)).header("X-SDV-Source-Document-Id", "doc-1"),
                400, "SDV-INT-015", "Uploaded content does not have a valid PDF header.", Level.INFO, null);
    }

    @Test
    void int016SubmissionAlreadyFull() throws Exception {
        when(documents.countReceived(TENANT, SUBMISSION)).thenReturn(2L);
        expect(upload(PDF).header("X-SDV-Source-Document-Id", "doc-3"), 409, "SDV-INT-016",
                "Submission already contains its declared expected number of documents.", Level.INFO, "expected=2");
        when(documents.countReceived(TENANT, SUBMISSION)).thenReturn(0L);
        tenant(new TenantGuardrails(1_000_000, 2_000, 100, 5, 24, 90, 95, null));
        expect(upload(PDF).header("X-SDV-Source-Document-Id", "doc-3"), 400, "SDV-INT-016",
                "Document would exceed the configured aggregate submission size limit.", Level.INFO, "limitBytes=5");
    }

    @Test
    void int017DocumentRetryDiffersFromTheFirst() throws Exception {
        when(documents.findBySourceDocumentId(TENANT, SOURCE, SUBMISSION, "doc-1")).thenReturn(Optional.of(
                document("other-name.pdf", DocumentStatus.AVAILABLE)));
        expect(upload(PDF).header("X-SDV-Source-Document-Id", "doc-1"), 409, "SDV-INT-017",
                "The document retry identity already exists with different content or filename.", Level.INFO,
                "sourceDocumentId=doc-1 documentId=" + DOCUMENT + " file name differs");
    }

    @Test
    void int018DocumentCantBeFinishedAgain() throws Exception {
        String retry = UPLOAD + "/" + DOCUMENT + "/retry-finalization";
        expect(post(retry), 404, "SDV-INT-018", "No document of this submission has this ID, so it can't be finished again.",
                Level.INFO, "documentId=" + DOCUMENT);
        when(documents.findDocumentById(TENANT, SOURCE, SUBMISSION, DOCUMENT)).thenReturn(Optional.of(
                document("a.pdf", DocumentStatus.PROCESSING)));
        expect(post(retry), 409, "SDV-INT-018", "This document can't be finished again in its current state (PROCESSING).",
                Level.INFO, "status=PROCESSING");
    }

    private static Document document(String filename, DocumentStatus status) {
        return new Document(DOCUMENT, TENANT, SUBMISSION, SOURCE, "doc-1", null, filename, "application/pdf",
                "0".repeat(64), (long) PDF.length, status, null, NOW, null, null, 0, null, null, SensitivityLevel.STANDARD);
    }
}
