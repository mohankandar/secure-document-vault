package com.sdv.platform.web.reports;

import com.sdv.platform.application.reports.ActivityReportService;
import com.sdv.platform.application.reports.IntakeReportService;
import com.sdv.platform.application.reports.SensitivityReportService;
import com.sdv.platform.application.reports.StorageReportService;
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
 *   <li>Task 0033: {@code GET /sensitivity?from=&to=&sourceId=}: the Sensitivity report (counts only); from and to
 *       left out for this month so far (up to 366 days). {@code GET /sensitivity/export}: its CSV, audited.</li>
 *   <li>Task 0053: {@code GET /storage}: the Storage and retention report, from the counts kept in the background;
 *       {@code GET /storage/export}: its CSV, with a reference and its SHA-256 fingerprint, audited.</li>
 *   <li>Task 0054: {@code GET /activity?from=&to=}: the Activity report (the last 7 days when both are left out, up to
 *       92 days), from the kept days and the audit trail; {@code GET /activity/export}: its CSV, with a reference and
 *       its SHA-256 fingerprint, audited.</li>
 * </ul>
 * Other refusals: SDV-RPT-002 (the day or range), SDV-RPT-003 (the sending system), SDV-RPT-004 (the tenant's time
 * zone), SDV-RPT-005 (too many lines, Daily intake only).
 */
@RestController
@RequestMapping("/api/v1/admin/reports")
public class ReportsController {
    static final String REFUSED = "Only a Business Admin can see the reports.";

    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final IntakeReportService reports;
    private final SensitivityReportService sensitivity;
    private final StorageReportService storage;
    private final ActivityReportService activity;

    public ReportsController(SecurityContextFacade security, AuthorizationService authorization,
                             IntakeReportService reports, SensitivityReportService sensitivity,
                             StorageReportService storage, ActivityReportService activity) {
        this.security = security;
        this.authorization = authorization;
        this.reports = reports;
        this.sensitivity = sensitivity;
        this.storage = storage;
        this.activity = activity;
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
        return csv(export.filename(), export.rowCount(), export.csv());
    }

    @GetMapping("/sensitivity")
    public ResponseEntity<SensitivityReportService.SensitivityReport> sensitivity(@RequestParam(required = false) String from,
                                                                                @RequestParam(required = false) String to,
                                                                                @RequestParam(required = false) String sourceId) {
        SdvPrincipal p = requireReports();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(sensitivity.report(p.tenantId(), from, to, sourceId));
    }

    @GetMapping("/sensitivity/export")
    public ResponseEntity<byte[]> sensitivityExport(@RequestParam(required = false) String from,
                                                    @RequestParam(required = false) String to,
                                                    @RequestParam(required = false) String sourceId) {
        SdvPrincipal p = requireReports();
        SensitivityReportService.Export export = sensitivity.export(p.tenantId(), p.subject(), MDC.get("correlationId"),
                from, to, sourceId);
        return csv(export.filename(), export.rowCount(), export.csv());
    }

    @GetMapping("/storage")
    public ResponseEntity<StorageReportService.StorageReport> storage() {
        SdvPrincipal p = requireReports();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate()).body(storage.report(p.tenantId()));
    }

    @GetMapping("/storage/export")
    public ResponseEntity<byte[]> storageExport() {
        SdvPrincipal p = requireReports();
        StorageReportService.Export export = storage.export(p.tenantId(), p.subject(), MDC.get("correlationId"));
        return registered(export.filename(), export.rowCount(), export.csv(), export.reference().toString(), export.fingerprint());
    }

    @GetMapping("/activity")
    public ResponseEntity<ActivityReportService.ActivityReport> activity(@RequestParam(required = false) String from,
                                                                       @RequestParam(required = false) String to) {
        SdvPrincipal p = requireReports();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate()).body(activity.report(p.tenantId(), from, to));
    }

    @GetMapping("/activity/export")
    public ResponseEntity<byte[]> activityExport(@RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        SdvPrincipal p = requireReports();
        ActivityReportService.Export export = activity.export(p.tenantId(), p.subject(), MDC.get("correlationId"), from, to);
        return registered(export.filename(), export.rowCount(), export.csv(), export.reference().toString(), export.fingerprint());
    }

    /** Task 0053: an export with its reference and SHA-256 fingerprint, as the quarantine exposure export had them. */
    static ResponseEntity<byte[]> registered(String filename, int rows, byte[] body, String reference, String fingerprint) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-SDV-Export-Rows", Integer.toString(rows))
                .header("X-SDV-Export-Reference", reference)
                .header("X-SDV-Export-Fingerprint", fingerprint)
                .body(body);
    }

    private static ResponseEntity<byte[]> csv(String filename, int rows, byte[] body) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-SDV-Export-Rows", Integer.toString(rows))
                .body(body);
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
