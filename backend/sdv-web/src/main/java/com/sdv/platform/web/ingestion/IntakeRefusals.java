package com.sdv.platform.web.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.UnidentifiedIntakeAttemptRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.security.auth.ActiveTenantFilter;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.web.foundation.CallerAddress;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 0032: a sending system's refused delivery or upload is recorded, so the Daily intake report counts it with its
 * code, also when it was refused before a submission existed. Task 0039: <b>every</b> error answer on an intake address
 * ({@code /api/v1/ingestion/…}) is recorded here, in one place: Vero's error handler and its /error page call
 * {@link #answered} after writing the answer. That takes in the answers Spring gives before Vero's code runs
 * (malformed JSON, a missing {@code X-SDV-Filename}, a wrong content type), the sign-in refusals, and failures (5xx).
 * <ul>
 *   <li><b>Known sending system</b> (a verified sending-system sign-in whose system is one of the tenant's, active or
 *       paused): a delivery, upload or finishing retry ({@code POST}) gets one audit event: INTAKE_REFUSED (outcome
 *       DENIED) for a 4xx, INTAKE_FAILED (outcome FAILED) for a 5xx, with only the stage, the sending system, the
 *       submission (when the address names one), the delivery's request ID as sent (sourceSubmissionId, when Vero read
 *       it), the upload's own ID as sent (sourceDocumentId or Idempotency-Key), the status and the code. The Daily
 *       intake report counts it under that system. A status query ({@code GET}) delivers nothing and is not counted.</li>
 *   <li><b>No known sending system</b> (no sign-in, a refused token, a person's sign-in, a sending system the tenant
 *       doesn't have): one row in unidentified_intake_attempts, for Tenant IT's Problems: the time, the caller's
 *       network address, the address called (IDs blanked), the method, the status, the code and the reference; the
 *       tenant only when a verified sign-in named it.</li>
 * </ul>
 * Never the message, a header's value other than the upload's own ID, a token, a file name or content. A failure to
 * record never changes the answer: the request was answered either way (it is then only missing from the report, and
 * one WARN line says so).
 */
@Component
public class IntakeRefusals {
    public static final String ACTION = "INTAKE_REFUSED";
    /** Task 0039: an intake request of a known sending system that failed on Vero's side (5xx). */
    public static final String FAILED_ACTION = "INTAKE_FAILED";
    public static final String INTAKE_PREFIX = "/api/v1/ingestion/";
    /** The delivery's request ID (sourceSubmissionId) as the controller read it from the body. */
    static final String REQUEST_ID_ATTRIBUTE = IntakeRefusals.class.getName() + ".requestId";
    private static final String DONE_ATTRIBUTE = IntakeRefusals.class.getName() + ".recorded";
    /** Records nothing (for tests that build the controllers or the error handler by hand). */
    public static final IntakeRefusals NONE = new IntakeRefusals(null, null, null, Clock.systemUTC());
    static final Logger LOG = LoggerFactory.getLogger(IntakeRefusals.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ID = 200;
    /** Task 0039 part 2: the words of Vero's intake addresses; any other part of an address is blanked to {id}. */
    private static final java.util.Set<String> ADDRESS_WORDS = java.util.Set.of("submissions", "documents", "retry-finalization");
    static final int MAX_ADDRESS_PARTS = 6;
    private static final java.util.Set<String> KNOWN_METHODS =
            java.util.Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");
    private static final Pattern SUBMISSION_PATH =
            Pattern.compile("^/api/v1/ingestion/submissions/([0-9a-fA-F-]{36})(/.*)?$");

    private final AuditEventPort audit;
    private final IngestionSourceRepository sources;
    private final UnidentifiedIntakeAttemptRepository unidentified;
    private final Clock clock;
    /** Task 0039 part 2: the caller's address, from X-Forwarded-For only behind a trusted proxy. */
    private final CallerAddress callers;

    @Autowired
    public IntakeRefusals(AuditEventPort audit, IngestionSourceRepository sources,
                          UnidentifiedIntakeAttemptRepository unidentified, CallerAddress callers) {
        this(audit, sources, unidentified, Clock.systemUTC(), callers);
    }

    IntakeRefusals(AuditEventPort audit, IngestionSourceRepository sources,
                   UnidentifiedIntakeAttemptRepository unidentified, Clock clock) {
        this(audit, sources, unidentified, clock, CallerAddress.DIRECT);
    }

    IntakeRefusals(AuditEventPort audit, IngestionSourceRepository sources,
                   UnidentifiedIntakeAttemptRepository unidentified, Clock clock, CallerAddress callers) {
        this.audit = audit;
        this.sources = sources;
        this.unidentified = unidentified;
        this.clock = clock;
        this.callers = callers == null ? CallerAddress.DIRECT : callers;
    }

    /** The controller read the delivery's request ID from the body; it is recorded if the delivery is refused. */
    static void noteRequestId(HttpServletRequest request, String sourceSubmissionId) {
        if (request != null) request.setAttribute(REQUEST_ID_ATTRIBUTE, sourceSubmissionId);
    }

    /**
     * Records an error answer ({@code status} 4xx or 5xx, {@code code} as sent) if the request was to an intake
     * address. Once per request; anything else is ignored.
     */
    public void answered(HttpServletRequest request, HttpStatusCode status, String code) {
        if (request == null || status == null || code == null || status.value() < 400) return;
        String path = originalPath(request);
        if (path == null || !path.startsWith(INTAKE_PREFIX)) return;
        if (request.getAttribute(DONE_ATTRIBUTE) != null) return;
        request.setAttribute(DONE_ATTRIBUTE, Boolean.TRUE);
        String reference = com.sdv.platform.web.foundation.ErrorReplies.reference(request);
        try {
            Object principal = principal(request);
            if (principal instanceof IngestionSourcePrincipal source && knownSystem(source)) {
                if ("POST".equalsIgnoreCase(request.getMethod())) recordForSystem(request, path, source, status, code, reference);
                return;
            }
            recordUnidentified(request, path, principal, status, code, reference);
        } catch (Exception ex) {
            // The answer was written already; the attempt is only missing from the report and from Problems.
            LOG.warn("code={} reference={} intake answer not recorded: {}", code, reference, ex.getClass().getName());
        }
    }

    private boolean knownSystem(IngestionSourcePrincipal source) {
        return sources != null && source.tenantId() != null && source.sourceId() != null
                && sources.findById(source.tenantId(), source.sourceId()).isPresent();
    }

    private void recordForSystem(HttpServletRequest request, String path, IngestionSourcePrincipal source,
                                 HttpStatusCode status, String code, String reference) throws Exception {
        if (audit == null) return;
        Matcher m = SUBMISSION_PATH.matcher(path);
        UUID submissionId = null;
        String stage = "SUBMISSION";
        if (m.matches()) {
            try {
                submissionId = UUID.fromString(m.group(1));
            } catch (IllegalArgumentException ignored) {
                // not an ID: the address names no submission
            }
            String rest = m.group(2) == null ? "" : m.group(2);
            stage = rest.contains("/retry-finalization") ? "FINALIZATION" : rest.startsWith("/documents") ? "DOCUMENT" : "SUBMISSION";
        }
        String documentRequestId = null;
        if (!"SUBMISSION".equals(stage)) {
            String sourceDocumentId = request.getHeader("X-SDV-Source-Document-Id");
            documentRequestId = sourceDocumentId != null && !sourceDocumentId.isBlank()
                    ? sourceDocumentId : request.getHeader("Idempotency-Key");
        }
        Object requestId = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        boolean failed = status.is5xxServerError();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stage", stage);
        details.put("sourceId", source.sourceId().toString());
        details.put("submissionId", submissionId == null ? null : submissionId.toString());
        details.put("requestId", submissionId == null && requestId instanceof String s ? id(s) : null);
        details.put("documentRequestId", id(documentRequestId));
        details.put("status", status.value());
        details.put("code", code);
        boolean upload = submissionId != null;
        audit.append(new AuditEvent(UUID.randomUUID(), source.tenantId(), "SOURCE", source.subject(),
                failed ? FAILED_ACTION : ACTION,
                upload ? "SUBMISSION" : "INGESTION_SOURCE",
                upload ? submissionId.toString() : source.sourceId().toString(),
                clock.instant(), reference, failed ? AuditOutcome.FAILED : AuditOutcome.DENIED,
                JSON.writeValueAsString(details)));
    }

    private void recordUnidentified(HttpServletRequest request, String path, Object principal, HttpStatusCode status,
                                    String code, String reference) {
        if (unidentified == null) return;
        UUID tenantId = principal instanceof IngestionSourcePrincipal source ? source.tenantId()
                : principal instanceof SdvPrincipal person ? person.tenantId() : null;
        unidentified.record(UnidentifiedIntakeAttemptRepository.Attempt.one(UUID.randomUUID(), tenantId,
                clock.instant(), cut(callers.of(request), 64), method(request.getMethod()), address(path),
                status.value(), cut(code, 40), cut(reference, 200)));
    }

    /** Task 0039 part 2: a method HTTP knows, or OTHER, so a made-up method can't make a new row per request. */
    static String method(String method) {
        String m = method == null ? "" : method.trim().toUpperCase(java.util.Locale.ROOT);
        return KNOWN_METHODS.contains(m) ? m : "OTHER";
    }

    /** The principal of a verified sign-in: from the security context, or as the tenant check left it (error page). */
    private static Object principal(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof IngestionSourcePrincipal || principal instanceof SdvPrincipal) return principal;
        return request.getAttribute(ActiveTenantFilter.PRINCIPAL_ATTRIBUTE);
    }

    /** The address the caller asked for; on Vero's /error page, the one that failed. */
    static String originalPath(HttpServletRequest request) {
        Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String uri = original instanceof String s ? s : request.getRequestURI();
        if (uri == null) return null;
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    /**
     * The address with every ID blanked, without control characters, at most 200 characters. Task 0039 part 2: every
     * part after {@code /api/v1/ingestion/} that isn't a word of Vero's intake addresses is blanked too (not only
     * UUIDs), and at most {@link #MAX_ADDRESS_PARTS} parts are kept, so made-up addresses can't make a new row per
     * request either.
     */
    static String address(String path) {
        String rest = path.substring(INTAKE_PREFIX.length());
        String[] parts = rest.split("/", -1);
        StringBuilder out = new StringBuilder(INTAKE_PREFIX.substring(0, INTAKE_PREFIX.length() - 1));
        for (int i = 0; i < parts.length; i++) {
            if (i == MAX_ADDRESS_PARTS) {
                out.append("/...");
                break;
            }
            String part = parts[i];
            out.append('/').append(part.isEmpty() || ADDRESS_WORDS.contains(part) ? part : "{id}");
        }
        return cut(out.toString(), 200);
    }

    private static String cut(String value, int max) {
        if (value == null) return null;
        String v = value.codePoints().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return v.length() > max ? v.substring(0, max) : v;
    }

    /** An ID as the sender sent it: trimmed, without control characters, at most 200 characters; null when blank. */
    static String id(String value) {
        if (value == null) return null;
        String v = cut(value, Integer.MAX_VALUE).trim();
        if (v.isEmpty()) return null;
        return v.length() > MAX_ID ? v.substring(0, MAX_ID) : v;
    }
}
