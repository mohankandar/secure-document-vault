package com.sdv.platform.web.admin;

import com.sdv.platform.application.audit.SealedAuditReportService;
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
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Task 0050: the sealed audit report, for a Business Admin or IT Admin (GENERATE_AUDIT_REPORT; anyone else is refused
 * with SDV-AUD-010 and the refusal is audited as AUTHORIZATION_DENIED). There is no auditor role: an admin generates
 * the report and hands it over. Generate, list the register, download the sealed CSV or its PDF, verify a fingerprint.
 * The files carry the report's reference, fingerprint and row count in X-SDV-Export-* headers.
 */
@RestController
@RequestMapping("/api/v1/audit-reports")
public class AuditReportController {
    static final String REFUSED = "Only a Business Admin or IT Admin can generate or verify an audit report.";

    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final SealedAuditReportService reports;

    public AuditReportController(SecurityContextFacade security, AuthorizationService authorization,
                                 SealedAuditReportService reports) {
        this.security = security;
        this.authorization = authorization;
        this.reports = reports;
    }

    @GetMapping
    public ResponseEntity<List<SealedAuditReportService.Report>> list() {
        return noStore(HttpStatus.OK, reports.list(actor()));
    }

    @PostMapping
    public ResponseEntity<SealedAuditReportService.Report> generate(@RequestBody(required = false) ReportBody body) {
        SealedAuditReportService.Actor actor = actor();
        ReportBody b = body == null ? new ReportBody(null, null, null, null, null, null) : body;
        return noStore(HttpStatus.CREATED, reports.generate(actor, new SealedAuditReportService.ReportRequest(
                b.from(), b.to(), b.action(), b.outcome(), b.recordNumber(), b.sensitivityLevel())));
    }

    @GetMapping("/{reportId}/csv")
    public ResponseEntity<byte[]> csv(@PathVariable String reportId) {
        return file(reports.csv(actor(), reportId), new MediaType("text", "csv", StandardCharsets.UTF_8));
    }

    @GetMapping("/{reportId}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String reportId) {
        return file(reports.pdf(actor(), reportId), MediaType.APPLICATION_PDF);
    }

    @PostMapping("/verify")
    public ResponseEntity<SealedAuditReportService.Verification> verify(@RequestBody(required = false) VerifyBody body) {
        SealedAuditReportService.Actor actor = actor();
        return noStore(HttpStatus.OK, reports.verify(actor, body == null ? null : body.fingerprint()));
    }

    private SealedAuditReportService.Actor actor() {
        SdvPrincipal principal = security.requirePrincipal();
        try {
            authorization.require(principal, Capability.GENERATE_AUDIT_REPORT);
        } catch (AccessDeniedException ex) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.AUD_010, REFUSED);
        }
        return new SealedAuditReportService.Actor(principal.tenantId(), principal.subject(), principal.displayName(),
                MDC.get("correlationId"));
    }

    private static ResponseEntity<byte[]> file(SealedAuditReportService.Download download, MediaType type) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(download.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-SDV-Export-Rows", Integer.toString(download.report().rowCount()))
                .header("X-SDV-Export-Reference", download.report().reportId().toString())
                .header("X-SDV-Export-Fingerprint", download.report().fingerprint())
                .body(download.bytes());
    }

    private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate()).body(body);
    }

    /** from and to: days (YYYY-MM-DD, UTC), both included; the rest are the Audit list's filters, each optional. */
    public record ReportBody(String from, String to, String action, String outcome, String recordNumber,
                             String sensitivityLevel) { }

    public record VerifyBody(String fingerprint) { }
}
