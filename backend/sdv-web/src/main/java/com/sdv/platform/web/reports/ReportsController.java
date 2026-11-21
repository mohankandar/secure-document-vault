package com.sdv.platform.web.reports;

import com.sdv.platform.application.reports.IntakeReportService;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Task 0032: the Business Admin's Reports tab (VIEW_INTAKE_REPORTS). Anyone else is refused with SDV-RPT-001, and the
 * refusal is audited as AUTHORIZATION_DENIED.
 * <ul>
 *   <li>{@code GET /intake?from=&to=&sourceId=}: the Daily intake report (counts only). from and to are days
 *       (YYYY-MM-DD) in the tenant's time zone, both left out for today; sourceId left out for every sending system.</li>
 *   <li>{@code GET /intake/export} with the same filters: one CSV line per document; the export is audited.</li>
 * </ul>
 * Other refusals: SDV-RPT-002 (the day or range), SDV-RPT-003 (the sending system), SDV-RPT-004 (the tenant's time
 * zone), SDV-RPT-005 (too many lines).
 */
@RestController
@RequestMapping("/api/v1/admin/reports")
public class ReportsController {
    static final String REFUSED = "Only a Business Admin can see the reports.";

    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final IntakeReportService reports;

    public ReportsController(SecurityContextFacade security, AuthorizationService authorization,
                             IntakeReportService reports) {
        this.security = security;
        this.authorization = authorization;
        this.reports = reports;
    }

    @GetMapping("/intake")
    public ResponseEntity<IntakeReportService.IntakeReport> intake(@RequestParam(required = false) String from,
                                                                   @RequestParam(required = false) String to,
                                                                   @RequestParam(required = false) String sourceId) {
        SdvPrincipal p = requireReports();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(reports.intake(p.tenantId(), from, to, sourceId));
    }

    @GetMapping("/intake/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) String sourceId) {
        SdvPrincipal p = requireReports();
        IntakeReportService.Export export = reports.export(p.tenantId(), p.subject(), MDC.get("correlationId"),
                from, to, sourceId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(export.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-SDV-Export-Rows", Integer.toString(export.rowCount()))
                .body(export.csv());
    }

    private SdvPrincipal requireReports() {
        SdvPrincipal principal = security.requirePrincipal();
        try {
            authorization.require(principal, Capability.VIEW_INTAKE_REPORTS);
        } catch (AccessDeniedException ex) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.RPT_001, REFUSED);
        }
        return principal;
    }
}
