package com.sdv.platform.web.ingestion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sdv.platform.application.document.DocumentIntakeService;
import com.sdv.platform.application.document.PdfTooLargeException;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.UnidentifiedIntakeAttemptRepository;
import com.sdv.platform.application.port.UnidentifiedIntakeAttemptRepository.Attempt;
import com.sdv.platform.application.submission.SubmissionService;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.security.auth.SignInAnswer;
import com.sdv.platform.web.config.CorrelationIdFilter;
import com.sdv.platform.web.foundation.FoundationExceptionHandler;
import com.sdv.platform.web.foundation.SdvErrorController;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 0032: every refusal of a sending system's delivery or upload is recorded (INTAKE_REFUSED) with the code the
 * sender got, so the Daily intake report counts it, also before any submission exists. Task 0039: recorded in one
 * place, Vero's error handler and error page, so it takes in the answers Spring gives before Vero's code runs and the
 * 5xx failures (INTAKE_FAILED), and an attempt with no known sending system goes to IT's Problems
 * (unidentified intake attempts) without anything from a token. The answer is unchanged in every case.
 */
class IntakeRefusalsTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SUBMISSION = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJzZWNyZXQtc3ViamVjdCJ9.c2lnbmF0dXJl";

    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
    private final UnidentifiedIntakeAttemptRepository unidentified = mock(UnidentifiedIntakeAttemptRepository.class);
    private final SubmissionService submissions = mock(SubmissionService.class);
    private final DocumentIntakeService intake = mock(DocumentIntakeService.class);
    private final IntakeRefusals refusals =
            new IntakeRefusals(audit, sources, unidentified, Clock.fixed(NOW, ZoneOffset.UTC));
    private final MockMvc mvc;

    IntakeRefusalsTest() {
        SecurityContextFacade security = new SecurityContextFacade();
        mvc = MockMvcBuilders.standaloneSetup(new SubmissionController(security, submissions),
                        new DocumentIntakeController(security, intake))
                .setControllerAdvice(new FoundationExceptionHandler(refusals))
                .addFilters(new CorrelationIdFilter()).build();
        signedInAs(new IngestionSourcePrincipal(TENANT, SOURCE, "loans-producer"));
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(mock(IngestionSource.class)));
    }

    private static void signedInAs(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private AuditEvent recorded() {
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        return event.getValue();
    }

    private Attempt attempt() {
        ArgumentCaptor<Attempt> attempt = ArgumentCaptor.forClass(Attempt.class);
        verify(unidentified).record(attempt.capture());
        return attempt.getValue();
    }

    private void upload(Throwable thrown) throws Exception {
        reset(intake);
        when(intake.receive(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any()))
                .thenThrow(thrown);
    }

    /** Task 0036: the reference in the error body is the ID on the audit event the refusal wrote. */
    @Test
    void theBodysReferenceIsTheAuditEventsCorrelationId() throws Exception {
        upload(new CodedRefusal.BadRequest(SdvErrorCode.SEN_001, "unknown level"));
        String body = mvc.perform(post("/api/v1/ingestion/submissions/" + SUBMISSION + "/documents")
                        .contentType("application/pdf").content("%PDF".getBytes(StandardCharsets.US_ASCII))
                        .header("X-SDV-Filename", "a.pdf"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        String reference = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("reference").asText();
        assertFalse(reference.isBlank());
        assertEquals(reference, recorded().correlationId());
    }

    @Test
    void aRefusedDeliveryIsRecordedWithItsRequestIdAndCodeAlthoughNoSubmissionExists() throws Exception {
        when(submissions.create(any(), any(), any(), any(), any()))
                .thenThrow(new CodedRefusal.BadRequest(SdvErrorCode.SEN_002, "recordSensitivityLevel must be one of …"));
        mvc.perform(post("/api/v1/ingestion/submissions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceSubmissionId\":\"req-7c1e\",\"expectedDocumentCount\":2,\"metadata\":{\"contractNumber\":\"300000000077\"},\"recordSensitivityLevel\":\"secret\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-SEN-002"));

        AuditEvent e = recorded();
        assertEquals("INTAKE_REFUSED", e.action());
        assertEquals(AuditOutcome.DENIED, e.outcome());
        assertEquals("SOURCE", e.actorType());
        assertEquals("loans-producer", e.actorId());
        assertEquals("INGESTION_SOURCE", e.targetType());
        assertEquals(SOURCE.toString(), e.targetId());
        assertEquals(NOW, e.occurredAt());
        assertEquals("{\"stage\":\"SUBMISSION\",\"sourceId\":\"" + SOURCE + "\",\"submissionId\":null,\"requestId\":\"req-7c1e\","
                + "\"documentRequestId\":null,\"status\":400,\"code\":\"SDV-SEN-002\"}", e.safeDetailsJson());
        assertFalse(e.safeDetailsJson().contains("300000000077"), "no metadata");
        verifyNoInteractions(unidentified);
    }

    @Test
    void aRefusedUploadIsRecordedWithTheSubmissionAndItsOwnIdButNoFileName() throws Exception {
        upload(new CodedRefusal.BadRequest(SdvErrorCode.SEN_001, "unknown level"));
        mvc.perform(post("/api/v1/ingestion/submissions/" + SUBMISSION + "/documents").contentType("application/pdf")
                        .content("%PDF-1.7".getBytes(StandardCharsets.US_ASCII))
                        .header("X-SDV-Filename", "jordan-ellis-settlement.pdf")
                        .header("X-SDV-Source-Document-Id", "doc-3")
                        .header("X-SDV-Sensitivity-Level", "secret"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-SEN-001"));

        AuditEvent e = recorded();
        assertEquals("INTAKE_REFUSED", e.action());
        assertEquals("SUBMISSION", e.targetType());
        assertEquals(SUBMISSION.toString(), e.targetId());
        assertEquals("{\"stage\":\"DOCUMENT\",\"sourceId\":\"" + SOURCE + "\",\"submissionId\":\"" + SUBMISSION + "\","
                + "\"requestId\":null,\"documentRequestId\":\"doc-3\",\"status\":400,\"code\":\"SDV-SEN-001\"}", e.safeDetailsJson());
        assertFalse(e.safeDetailsJson().contains(".pdf"));
    }

    @Test
    void everyRefusalIsRecordedWithTheCodeTheSenderGot() throws Exception {
        record Case(Throwable thrown, int status, String code) { }
        for (Case c : List.of(
                new Case(new PdfTooLargeException(1024), 413, "SDV-INT-014"),
                new Case(new CodedRefusal.BadRequest(SdvErrorCode.INT_013, "Only application/pdf"), 400, "SDV-INT-013"),
                new Case(new CodedRefusal.NotFound(SdvErrorCode.INT_004, "No submission"), 404, "SDV-INT-004"),
                new Case(new CodedRefusal.Conflict(SdvErrorCode.INT_016, "already full"), 409, "SDV-INT-016"),
                new Case(new IllegalStateException("an uncoded conflict"), 409, "CONFLICT"))) {
            clearInvocations(audit);
            upload(c.thrown());
            mvc.perform(post("/api/v1/ingestion/submissions/" + SUBMISSION + "/documents").contentType("application/pdf")
                            .content("%PDF".getBytes(StandardCharsets.US_ASCII)).header("X-SDV-Filename", "a.pdf")
                            .header("Idempotency-Key", "  key-1\u0007  "))
                    .andExpect(status().is(c.status())).andExpect(jsonPath("$.code").value(c.code()));
            AuditEvent e = recorded();
            assertEquals("INTAKE_REFUSED", e.action());
            assertTrue(e.safeDetailsJson().contains("\"code\":\"" + c.code() + "\""), e.safeDetailsJson());
            assertTrue(e.safeDetailsJson().contains("\"status\":" + c.status()), e.safeDetailsJson());
            assertTrue(e.safeDetailsJson().contains("\"documentRequestId\":\"key-1\""), "trimmed, no control characters");
        }

        // A record level sent on an upload is refused before the service (task 0024) and recorded too.
        clearInvocations(audit);
        mvc.perform(post("/api/v1/ingestion/submissions/" + SUBMISSION + "/documents").contentType("application/pdf")
                        .content("%PDF".getBytes(StandardCharsets.US_ASCII)).header("X-SDV-Filename", "a.pdf")
                        .header(RecordSensitivityGuard.HEADER, "CONFIDENTIAL"))
                .andExpect(status().isBadRequest());
        assertTrue(recorded().safeDetailsJson().contains("\"code\":\"SDV-SEN-005\""));
    }

    /** Task 0039: refused by Spring before Vero's code ran (SDV-SYS-015), and still counted for the sending system. */
    @Test
    void answersSpringGivesBeforeVerosCodeRunsAreRecordedForTheSendingSystem() throws Exception {
        // JSON that doesn't parse.
        mvc.perform(post("/api/v1/ingestion/submissions").contentType(MediaType.APPLICATION_JSON).content("{\"sourceSubmissionId\":"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-SYS-015"));
        AuditEvent malformed = recorded();
        assertEquals("INTAKE_REFUSED", malformed.action());
        assertTrue(malformed.safeDetailsJson().contains("\"stage\":\"SUBMISSION\""));
        assertTrue(malformed.safeDetailsJson().contains("\"code\":\"SDV-SYS-015\""));

        // A missing X-SDV-Filename.
        clearInvocations(audit);
        mvc.perform(post("/api/v1/ingestion/submissions/" + SUBMISSION + "/documents").contentType("application/pdf")
                        .content("%PDF".getBytes(StandardCharsets.US_ASCII)).header("X-SDV-Source-Document-Id", "doc-9"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-SYS-015"))
                .andExpect(jsonPath("$.message").value("Vero couldn't read this request: the X-SDV-Filename header is missing."));
        AuditEvent noName = recorded();
        assertEquals("{\"stage\":\"DOCUMENT\",\"sourceId\":\"" + SOURCE + "\",\"submissionId\":\"" + SUBMISSION + "\","
                + "\"requestId\":null,\"documentRequestId\":\"doc-9\",\"status\":400,\"code\":\"SDV-SYS-015\"}", noName.safeDetailsJson());

        // A content type the address doesn't take: 415 keeps its status.
        clearInvocations(audit);
        mvc.perform(post("/api/v1/ingestion/submissions/" + SUBMISSION + "/documents").contentType(MediaType.TEXT_PLAIN)
                        .content("hello").header("X-SDV-Filename", "a.pdf"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("SDV-SYS-015"));
        assertTrue(recorded().safeDetailsJson().contains("\"status\":415"));
        verifyNoInteractions(intake, submissions, unidentified);
    }

    /** Task 0039: a failure on Vero's side (5xx) is recorded as failed, with its code: it used to be recorded nowhere. */
    @Test
    void aServerFailureIsRecordedAsFailedWithItsCode() throws Exception {
        upload(new java.io.UncheckedIOException(new java.io.IOException("disk")));
        mvc.perform(post("/api/v1/ingestion/submissions/" + SUBMISSION + "/documents")
                        .contentType("application/pdf").content("%PDF".getBytes(StandardCharsets.US_ASCII)).header("X-SDV-Filename", "a.pdf"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SDV-SYS-013"));
        AuditEvent e = recorded();
        assertEquals("INTAKE_FAILED", e.action());
        assertEquals(AuditOutcome.FAILED, e.outcome());
        assertTrue(e.safeDetailsJson().contains("\"status\":500"));
        assertTrue(e.safeDetailsJson().contains("\"code\":\"SDV-SYS-013\""));
        assertFalse(e.safeDetailsJson().contains("disk"), "never the cause's text");

        clearInvocations(audit);
        upload(new org.springframework.dao.DataAccessResourceFailureException("connection refused"));
        mvc.perform(post("/api/v1/ingestion/submissions/" + SUBMISSION + "/documents")
                        .contentType("application/pdf").content("%PDF".getBytes(StandardCharsets.US_ASCII)).header("X-SDV-Filename", "a.pdf"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SDV-SYS-014"));
        assertEquals("INTAKE_FAILED", recorded().action());
    }

    @Test
    void aStatusQueryDeliversNothingAndIsNotCounted() throws Exception {
        when(submissions.get(any(), any(), any())).thenThrow(new CodedRefusal.NotFound(SdvErrorCode.INT_004, "No submission"));
        mvc.perform(get("/api/v1/ingestion/submissions/" + SUBMISSION)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SDV-INT-004"));
        verifyNoInteractions(audit, unidentified);
    }

    /** Task 0039: a sending system the tenant doesn't have: no known system, so for IT, with the tenant. */
    @Test
    void aSendingSystemTheTenantDoesntHaveGoesToTenantIt() throws Exception {
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.empty());
        when(submissions.create(any(), any(), any(), any(), any())).thenThrow(new CodedRefusal.Forbidden(SdvErrorCode.INT_005,
                "This sending system is paused or not set up in Vero. Nothing was stored.", "sourceId=" + SOURCE));
        mvc.perform(post("/api/v1/ingestion/submissions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceSubmissionId\":\"req-1\",\"expectedDocumentCount\":1}").with(r -> { r.setRemoteAddr("10.1.2.3"); return r; }))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SDV-INT-005"));
        Attempt a = attempt();
        assertEquals(TENANT, a.tenantId());
        assertEquals("POST", a.method());
        assertEquals("/api/v1/ingestion/submissions", a.address());
        assertEquals(403, a.status());
        assertEquals("SDV-INT-005", a.code());
        assertEquals("10.1.2.3", a.callerAddress());
        assertEquals(NOW, a.occurredAt());
        verifyNoInteractions(audit);
    }

    /**
     * Task 0039 (closes the gap from report 0033): a failed sign-in on an intake address is answered by Vero's error
     * page; it has no known sending system, so it goes to IT: time, address, code, reference, no token content.
     */
    @Test
    void aFailedSignInOnAnIntakeAddressGoesToTenantItWithNoTokenContent() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/api/v1/ingestion/submissions/" + SUBMISSION + "/documents");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.addHeader("X-SDV-Filename", "jordan-ellis-settlement.pdf");
        request.setRemoteAddr("203.0.113.9");
        request.setAttribute(CorrelationIdFilter.REFERENCE_ATTRIBUTE, "ref-0039");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 401);
        SignInAnswer.of(401, SdvErrorCode.AUT_003, SignInAnswer.SIGN_IN_AGAIN, "token not accepted: signature").attachTo(request);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new SdvErrorController(refusals).error(request, response);

        assertEquals(401, response.getStatus());
        Attempt a = attempt();
        assertNull(a.tenantId(), "no verified tenant");
        assertEquals("/api/v1/ingestion/submissions/{id}/documents", a.address(), "IDs blanked");
        assertEquals("POST", a.method());
        assertEquals(401, a.status());
        assertEquals("SDV-AUT-003", a.code());
        assertEquals("ref-0039", a.reference());
        assertEquals("203.0.113.9", a.callerAddress());
        String all = a.toString();
        for (String secret : List.of(TOKEN, "eyJ", "secret-subject", "signature", ".pdf", "jordan")) {
            assertFalse(all.contains(secret), "the attempt holds " + secret);
        }
        verifyNoInteractions(audit);
    }

    /** The error page is also reached as /error with the failed address in the request (as Tomcat forwards it). */
    @Test
    void theErrorPageUsesTheAddressThatFailedAndOtherAddressesAreNotRecorded() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest forwarded = new MockHttpServletRequest("GET", "/error");
        forwarded.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/ingestion/submissions");
        forwarded.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 401);
        new SdvErrorController(refusals).error(forwarded, new MockHttpServletResponse());
        assertEquals("SDV-AUT-001", attempt().code());

        clearInvocations(unidentified);
        MockHttpServletRequest business = new MockHttpServletRequest("GET", "/api/v1/business/home");
        business.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 401);
        new SdvErrorController(refusals).error(business, new MockHttpServletResponse());
        verifyNoInteractions(unidentified, audit);
    }

    @Test
    void aRefusalIsAnsweredAlsoWhenItCannotBeRecorded() throws Exception {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger log = (Logger) LoggerFactory.getLogger(IntakeRefusals.class);
        log.addAppender(logs);
        try {
            doThrow(new IllegalStateException("audit store down")).when(audit).append(any());
            when(submissions.create(any(), any(), any(), any(), any()))
                    .thenThrow(new CodedRefusal.BadRequest(SdvErrorCode.SEN_006, "set on each document upload"));
            mvc.perform(post("/api/v1/ingestion/submissions").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sourceSubmissionId\":\"req-1\",\"expectedDocumentCount\":1}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-SEN-006"));
            assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("code=SDV-SEN-006") && e.getFormattedMessage().contains("not recorded")));
        } finally {
            log.detachAppender(logs);
        }
    }

    @Test
    void longIdsAreCutTo200CharactersAndAddressesHaveNoIds() {
        assertEquals(200, IntakeRefusals.id("x".repeat(500)).length());
        assertNull(IntakeRefusals.id("   "));
        assertNull(IntakeRefusals.id(null));
        assertEquals("/api/v1/ingestion/submissions/{id}/documents/{id}/retry-finalization",
                IntakeRefusals.address("/api/v1/ingestion/submissions/" + SUBMISSION + "/documents/" + UUID.randomUUID() + "/retry-finalization"));
        // Task 0039 part 2: any part that isn't a word of Vero's intake addresses is blanked (it was cut at 200
        // characters before), so made-up addresses can't make a new row per request.
        assertEquals("/api/v1/ingestion/{id}", IntakeRefusals.address("/api/v1/ingestion/" + "a".repeat(400)));
        assertEquals("/api/v1/ingestion/submissions/{id}", IntakeRefusals.address("/api/v1/ingestion/submissions/not-a-uuid-" + UUID.randomUUID()));
        assertEquals("/api/v1/ingestion/{id}/{id}/{id}/{id}/{id}/{id}/...", IntakeRefusals.address("/api/v1/ingestion/a/b/c/d/e/f/g/h/i"));
        assertEquals("/api/v1/ingestion/submissions/", IntakeRefusals.address("/api/v1/ingestion/submissions/"));
    }

    /** Task 0039 part 2: a made-up method is OTHER; Vero's own are kept. */
    @Test
    void methodsAreHttpsOwnOrOther() {
        assertEquals("POST", IntakeRefusals.method("POST"));
        assertEquals("GET", IntakeRefusals.method("get"));
        assertEquals("OTHER", IntakeRefusals.method("FLOOD" + UUID.randomUUID()));
        assertEquals("OTHER", IntakeRefusals.method(null));
    }

    /**
     * Task 0039 part 2 (owner answer 3): behind a trusted proxy the caller's address comes from X-Forwarded-For;
     * without that setting the header is ignored (the default).
     */
    @Test
    void theCallerAddressComesFromXForwardedForOnlyBehindATrustedProxy() throws Exception {
        SecurityContextHolder.clearContext();
        for (boolean trusted : new boolean[]{false, true}) {
            clearInvocations(unidentified);
            IntakeRefusals recorder = new IntakeRefusals(audit, sources, unidentified, Clock.fixed(NOW, ZoneOffset.UTC),
                    trusted ? new com.sdv.platform.web.foundation.CallerAddress("10.0.0.5") : com.sdv.platform.web.foundation.CallerAddress.DIRECT);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ingestion/submissions");
            request.setRemoteAddr("10.0.0.5");
            request.addHeader("X-Forwarded-For", "198.51.100.7");
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 401);
            new SdvErrorController(recorder).error(request, new MockHttpServletResponse());
            assertEquals(trusted ? "198.51.100.7" : "10.0.0.5", attempt().callerAddress(), "trusted proxy: " + trusted);
            assertEquals(1, attempt().count());
            assertEquals(NOW, attempt().lastSeenAt());
        }
    }
}
