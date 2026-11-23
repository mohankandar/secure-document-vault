package com.sdv.platform.web.quarantine;

import com.sdv.platform.application.quarantine.QuarantineService;
import com.sdv.platform.application.quarantine.QuarantineService.FilterRequest;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Task 0048: quarantine for Stewards (QUARANTINE_DOCUMENTS; anyone else gets SDV-QUA-002, audited as
 * AUTHORIZATION_DENIED). Quarantine by filter needs QUARANTINE_BY_FILTER, a Restricted Steward's (SDV-QUA-003). The
 * service checks every level against the person's clearance. No screen uses these yet (task 0049).
 * <ul>
 *   <li>{@code POST /documents}: quarantine one document, into a new incident or an open one (201);</li>
 *   <li>{@code POST /documents/{documentId}/release}: release one document;</li>
 *   <li>{@code POST /filter/count}, {@code POST /filter}: count, then quarantine every matching document (201);</li>
 *   <li>{@code GET /incidents}, {@code GET /incidents/{id}}: incidents for review;</li>
 *   <li>{@code POST /incidents/{id}/release-request}: ask a Business Admin to release the whole incident;</li>
 *   <li>{@code GET /incidents/{id}/exposure} and {@code /exposure/export}: the exposure report and its CSV.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/quarantine")
public class QuarantineController {
    static final String REFUSED = "Only a Document Steward or Restricted Steward can quarantine documents.";
    static final String FILTER_REFUSED = "Only a Restricted Steward can quarantine documents by filter.";

    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final QuarantineService quarantine;

    public QuarantineController(SecurityContextFacade security, AuthorizationService authorization,
                                QuarantineService quarantine) {
        this.security = security;
        this.authorization = authorization;
        this.quarantine = quarantine;
    }

    @PostMapping("/documents")
    public ResponseEntity<QuarantineService.IncidentView> quarantineDocument(@RequestBody(required = false) DocumentRequest body) {
        QuarantineService.Actor actor = actor(Capability.QUARANTINE_DOCUMENTS, SdvErrorCode.QUA_002, REFUSED);
        DocumentRequest b = body == null ? new DocumentRequest(null, null, null, null) : body;
        return noStore(HttpStatus.CREATED, quarantine.quarantineDocument(actor, b.documentId(), b.incidentId(), b.name(), b.reason()));
    }

    @PostMapping("/documents/{documentId}/release")
    public ResponseEntity<QuarantineService.QuarantinedDocumentView> release(@PathVariable String documentId,
                                                                             @RequestBody(required = false) ReasonRequest body) {
        QuarantineService.Actor actor = actor(Capability.QUARANTINE_DOCUMENTS, SdvErrorCode.QUA_002, REFUSED);
        return noStore(HttpStatus.OK, quarantine.releaseDocument(actor, documentId, body == null ? null : body.reason()));
    }

    @PostMapping("/filter/count")
    public ResponseEntity<QuarantineService.MatchCount> count(@RequestBody(required = false) FilterBody body) {
        QuarantineService.Actor actor = filterActor();
        return noStore(HttpStatus.OK, quarantine.countMatching(actor, body == null ? null : body.filter()));
    }

    @PostMapping("/filter")
    public ResponseEntity<QuarantineService.IncidentView> quarantineByFilter(@RequestBody(required = false) FilterBody body) {
        QuarantineService.Actor actor = filterActor();
        FilterBody b = body == null ? new FilterBody(null, null, null, null, null, null, null) : body;
        return noStore(HttpStatus.CREATED, quarantine.quarantineByFilter(actor, b.filter(), b.expectedCount(), b.name(), b.reason()));
    }

    @GetMapping("/incidents")
    public ResponseEntity<List<QuarantineService.IncidentSummary>> incidents() {
        QuarantineService.Actor actor = actor(Capability.QUARANTINE_DOCUMENTS, SdvErrorCode.QUA_002, REFUSED);
        return noStore(HttpStatus.OK, quarantine.incidents(actor));
    }

    @GetMapping("/incidents/{incidentId}")
    public ResponseEntity<QuarantineService.IncidentView> incident(@PathVariable String incidentId,
                                                                   @RequestParam(name = "after", required = false) String after) {
        QuarantineService.Actor actor = actor(Capability.QUARANTINE_DOCUMENTS, SdvErrorCode.QUA_002, REFUSED);
        return noStore(HttpStatus.OK, quarantine.incident(actor, incidentId, after));
    }

    @PostMapping("/incidents/{incidentId}/release-request")
    public ResponseEntity<QuarantineService.IncidentView> requestRelease(@PathVariable String incidentId,
                                                                         @RequestBody(required = false) ReasonRequest body) {
        QuarantineService.Actor actor = actor(Capability.QUARANTINE_DOCUMENTS, SdvErrorCode.QUA_002, REFUSED);
        return noStore(HttpStatus.CREATED, quarantine.requestIncidentRelease(actor, incidentId, body == null ? null : body.reason()));
    }

    @GetMapping("/incidents/{incidentId}/exposure")
    public ResponseEntity<QuarantineService.ExposureReport> exposure(@PathVariable String incidentId,
                                                                     @RequestParam(name = "after", required = false) String after) {
        QuarantineService.Actor actor = actor(Capability.QUARANTINE_DOCUMENTS, SdvErrorCode.QUA_002, REFUSED);
        return noStore(HttpStatus.OK, quarantine.exposure(actor, incidentId, after));
    }

    /**
     * Task 0052: the CSV streams row by row, whatever the size. The reference is sent first; the row count and the
     * SHA-256 fingerprint can only be known at the end, so they are recorded in the audit (the screen works out the
     * same fingerprint from the file it received). Refusals come before the first byte, as before.
     */
    @GetMapping("/incidents/{incidentId}/exposure/export")
    public void exportExposure(@PathVariable String incidentId, HttpServletResponse response) throws IOException {
        QuarantineService.Actor actor = actor(Capability.QUARANTINE_DOCUMENTS, SdvErrorCode.QUA_002, REFUSED);
        QuarantineService.ExposureExport export = quarantine.openExposureExport(actor, incidentId);
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8).toString());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(export.filename()).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-SDV-Export-Reference", export.reference().toString());
        export.writeTo(response.getOutputStream());
        response.flushBuffer();
    }

    private QuarantineService.Actor filterActor() {
        return actor(Capability.QUARANTINE_BY_FILTER, SdvErrorCode.QUA_003, FILTER_REFUSED);
    }

    private QuarantineService.Actor actor(Capability capability, SdvErrorCode code, String message) {
        SdvPrincipal principal = security.requirePrincipal();
        try {
            authorization.require(principal, capability);
        } catch (AccessDeniedException ex) {
            throw new CodedRefusal.Forbidden(code, message);
        }
        return new QuarantineService.Actor(principal.tenantId(), principal.subject(), principal.displayName(),
                authorization.clearance(principal), MDC.get("correlationId"));
    }

    private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate()).body(body);
    }

    public record DocumentRequest(String documentId, String incidentId, String name, String reason) { }

    public record ReasonRequest(String reason) { }

    /** The filter (received dates YYYY-MM-DD, UTC days; a sending system; field values), the count seen, name, reason. */
    public record FilterBody(String receivedFrom, String receivedTo, String sourceId, Map<String, String> fields,
                             Long expectedCount, String name, String reason) {
        FilterRequest filter() {
            return new FilterRequest(receivedFrom, receivedTo, sourceId, fields);
        }
    }
}
