package com.sdv.platform.web.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.web.foundation.FoundationExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Task 0032: a sending system's refused delivery or upload, recorded so the Daily intake report counts it with its
 * code, also when it was refused before a submission existed. One audit event, INTAKE_REFUSED (outcome DENIED), with
 * only: the stage (SUBMISSION or DOCUMENT), the sending system, the submission (when the upload named one), the
 * delivery's request ID as sent (sourceSubmissionId, when creating), the upload's own ID as sent (sourceDocumentId or
 * Idempotency-Key), and the code the sender got. Never the message, a file name or content.
 * <p>
 * Only refusals (4xx) are recorded; failures (5xx) are not refusals. A refusal that can't be recorded is still
 * answered as it was: the delivery was refused either way.
 */
@Component
public class IntakeRefusals {
    public static final String ACTION = "INTAKE_REFUSED";
    /** Records nothing (for tests that build the controllers by hand). */
    public static final IntakeRefusals NONE = new IntakeRefusals(null, Clock.systemUTC());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ID = 200;

    private final AuditEventPort audit;
    private final Clock clock;

    @Autowired
    public IntakeRefusals(AuditEventPort audit) {
        this(audit, Clock.systemUTC());
    }

    IntakeRefusals(AuditEventPort audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    public void submissionRefused(IngestionSourcePrincipal source, String sourceSubmissionId, Throwable refusal,
                                  String correlationId) {
        record(source, "SUBMISSION", null, sourceSubmissionId, null, refusal, correlationId);
    }

    public void uploadRefused(IngestionSourcePrincipal source, UUID submissionId, String documentRequestId,
                              Throwable refusal, String correlationId) {
        record(source, "DOCUMENT", submissionId, null, documentRequestId, refusal, correlationId);
    }

    private void record(IngestionSourcePrincipal source, String stage, UUID submissionId, String requestId,
                        String documentRequestId, Throwable refusal, String correlationId) {
        String code = FoundationExceptionHandler.refusalCode(refusal);
        if (audit == null || source == null || code == null) return;
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("stage", stage);
            details.put("sourceId", source.sourceId().toString());
            details.put("submissionId", submissionId == null ? null : submissionId.toString());
            details.put("requestId", id(requestId));
            details.put("documentRequestId", id(documentRequestId));
            details.put("code", code);
            boolean upload = submissionId != null;
            audit.append(new AuditEvent(UUID.randomUUID(), source.tenantId(), "SOURCE", source.subject(), ACTION,
                    upload ? "SUBMISSION" : "INGESTION_SOURCE",
                    upload ? submissionId.toString() : source.sourceId().toString(),
                    clock.instant(), correlationId, AuditOutcome.DENIED, JSON.writeValueAsString(details)));
        } catch (Exception ignored) {
            // The refusal is answered either way; it is only missing from the report.
        }
    }

    /** An ID as the sender sent it: trimmed, without control characters, at most 200 characters; null when blank. */
    static String id(String value) {
        if (value == null) return null;
        String v = value.codePoints().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().trim();
        if (v.isEmpty()) return null;
        return v.length() > MAX_ID ? v.substring(0, MAX_ID) : v;
    }
}
