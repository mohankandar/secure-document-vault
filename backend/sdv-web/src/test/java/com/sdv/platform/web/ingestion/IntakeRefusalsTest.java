package com.sdv.platform.web.ingestion;

import com.sdv.platform.application.document.DocumentIntakeService;
import com.sdv.platform.application.document.PdfTooLargeException;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.submission.SubmissionService;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.web.foundation.FoundationExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 0032: every refusal of a sending system's delivery or upload is recorded (INTAKE_REFUSED) with the code the
 * sender got, so the Daily intake report counts it, also before any submission exists. The answer is unchanged.
 * Nothing but IDs and the code is recorded; failures (5xx) are not refusals and are not recorded.
 */
class IntakeRefusalsTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SUBMISSION = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");

    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final SubmissionService submissions = mock(SubmissionService.class);
    private final DocumentIntakeService intake = mock(DocumentIntakeService.class);
    private final MockMvc mvc;

    IntakeRefusalsTest() {
        SecurityContextFacade security = mock(SecurityContextFacade.class);
        when(security.requireIngestionSourcePrincipal()).thenReturn(new IngestionSourcePrincipal(TENANT, SOURCE, "loans-producer"));
        IntakeRefusals refusals = new IntakeRefusals(audit, Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new SubmissionController(security, submissions, refusals),
                        new DocumentIntakeController(security, intake, refusals))
                .setControllerAdvice(new FoundationExceptionHandler()).build();
    }

    private AuditEvent recorded() {
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        return event.getValue();
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
                + "\"documentRequestId\":null,\"code\":\"SDV-SEN-002\"}", e.safeDetailsJson());
        assertFalse(e.safeDetailsJson().contains("300000000077"), "no metadata");
    }

    @Test
    void aRefusedUploadIsRecordedWithTheSubmissionAndItsOwnIdButNoFileName() throws Exception {
        when(intake.receive(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any()))
                .thenThrow(new CodedRefusal.BadRequest(SdvErrorCode.SEN_001, "unknown level"));
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
                + "\"requestId\":null,\"documentRequestId\":\"doc-3\",\"code\":\"SDV-SEN-001\"}", e.safeDetailsJson());
        assertFalse(e.safeDetailsJson().contains(".pdf"));
    }

    @Test
    void uncodedRefusalsAreRecordedWithTheCodeTheSenderGotAndFailuresAreNot() throws Exception {
        record Case(Throwable thrown, int status, String code) { }
        for (Case c : List.of(
                new Case(new PdfTooLargeException("too big"), 413, "PDF_TOO_LARGE"),
                new Case(new IllegalArgumentException("Only application/pdf"), 400, "BAD_REQUEST"),
                new Case(new java.util.NoSuchElementException("Submission not found."), 404, "NOT_FOUND"),
                new Case(new IllegalStateException("already contains its declared number"), 409, "CONFLICT"))) {
            clearInvocations(audit);
            reset(intake);
            when(intake.receive(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any()))
                    .thenThrow(c.thrown());
            mvc.perform(post("/api/v1/ingestion/submissions/" + SUBMISSION + "/documents").contentType("application/pdf")
                            .content("%PDF".getBytes(StandardCharsets.US_ASCII)).header("X-SDV-Filename", "a.pdf")
                            .header("Idempotency-Key", "  key-1\u0007  "))
                    .andExpect(status().is(c.status())).andExpect(jsonPath("$.code").value(c.code()));
            AuditEvent e = recorded();
            assertTrue(e.safeDetailsJson().contains("\"code\":\"" + c.code() + "\""), e.safeDetailsJson());
            assertTrue(e.safeDetailsJson().contains("\"documentRequestId\":\"key-1\""), "trimmed, no control characters");
        }

        // A record level sent on an upload is refused before the service (task 0024) and recorded too.
        clearInvocations(audit);
        mvc.perform(post("/api/v1/ingestion/submissions/" + SUBMISSION + "/documents").contentType("application/pdf")
                        .content("%PDF".getBytes(StandardCharsets.US_ASCII)).header("X-SDV-Filename", "a.pdf")
                        .header(RecordSensitivityGuard.HEADER, "CONFIDENTIAL"))
                .andExpect(status().isBadRequest());
        assertTrue(recorded().safeDetailsJson().contains("\"code\":\"SDV-SEN-"));

        // A server failure is not a refusal.
        clearInvocations(audit);
        reset(intake);
        when(intake.receive(any(), any(), any(), any(), any(), any(), any(), any(), anyLong(), any(), any(), any()))
                .thenThrow(new java.io.UncheckedIOException(new java.io.IOException("disk")));
        assertThrows(Exception.class, () -> mvc.perform(post("/api/v1/ingestion/submissions/" + SUBMISSION + "/documents")
                .contentType("application/pdf").content("%PDF".getBytes(StandardCharsets.US_ASCII)).header("X-SDV-Filename", "a.pdf")));
        verifyNoInteractions(audit);
    }

    @Test
    void aRefusalIsAnsweredAlsoWhenItCannotBeRecorded() throws Exception {
        doThrow(new IllegalStateException("audit store down")).when(audit).append(any());
        when(submissions.create(any(), any(), any(), any(), any()))
                .thenThrow(new CodedRefusal.BadRequest(SdvErrorCode.SEN_006, "set on each document upload"));
        mvc.perform(post("/api/v1/ingestion/submissions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceSubmissionId\":\"req-1\",\"expectedDocumentCount\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-SEN-006"));
    }

    @Test
    void longIdsAreCutTo200Characters() {
        assertEquals(200, IntakeRefusals.id("x".repeat(500)).length());
        assertNull(IntakeRefusals.id("   "));
        assertNull(IntakeRefusals.id(null));
    }
}
