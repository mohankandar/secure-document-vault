package com.sdv.platform.web.operations;

import com.sdv.platform.application.audit.AuditQueryService;
import com.sdv.platform.application.operations.OperationsService;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.web.retention.RetentionSweepMonitor;
import org.slf4j.MDC;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final OperationsService operations;
    private final AuditQueryService audit;
    private final RetentionSweepMonitor retentionSweepMonitor;

    public OperationsController(SecurityContextFacade security,
                                AuthorizationService authorization,
                                OperationsService operations,
                                AuditQueryService audit,
                                RetentionSweepMonitor retentionSweepMonitor) {
        this.security = security;
        this.authorization = authorization;
        this.operations = operations;
        this.audit = audit;
        this.retentionSweepMonitor = retentionSweepMonitor;
    }

    @GetMapping("/capacity-banner")
    public OperationsService.CapacityBanner capacityBanner() {
        SdvPrincipal principal = security.requirePrincipal();
        return operations.capacityBanner(principal.tenantId());
    }

    @GetMapping("/summary")
    public OperationsService.OperationsSummary summary() {
        SdvPrincipal principal = requireTechnicalView();
        return operations.summary(principal.tenantId(), authorization.clearance(principal));
    }

    @GetMapping("/retention-status")
    public RetentionSweepMonitor.Status retentionStatus() {
        requireTechnicalView();
        return retentionSweepMonitor.status();
    }

    @GetMapping("/audit-events")
    public AuditQueryService.AuditPage auditEvents(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String recordNumber) {
        SdvPrincipal principal = requireTechnicalView();
        return audit.search(principal.tenantId(), principal.subject(),
                new AuditQueryService.SearchRequest(action, outcome, actorType, actorId, targetType, targetId,
                        correlationId, from, to, limit, cursor, recordNumber), correlationId(),
                authorization.clearance(principal));
    }

    /**
     * Task 0011 Part B.6: the filtered audit as a CSV file, built here so the AUDIT_EXPORTED record matches exactly
     * what left the system. Same permission and filters as the list, plus the screen's activity group
     * ({@code actions}) and hidden routine events ({@code excludeActions}).
     */
    @GetMapping("/audit-events/export")
    public ResponseEntity<byte[]> exportAuditEvents(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String recordNumber,
            @RequestParam(required = false) List<String> actions,
            @RequestParam(required = false) List<String> excludeActions) {
        SdvPrincipal principal = requireTechnicalView();
        AuditQueryService.AuditExport export = audit.export(principal.tenantId(), principal.subject(),
                new AuditQueryService.ExportRequest(new AuditQueryService.SearchRequest(action, outcome, actorType, actorId,
                        targetType, targetId, correlationId, from, to, null, null, recordNumber), actions, excludeActions),
                correlationId(), authorization.clearance(principal));
        String filename = "vero-audit-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now()) + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-SDV-Export-Rows", Integer.toString(export.rowCount()))
                .body(export.csv());
    }

    private SdvPrincipal requireTechnicalView() {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.VIEW_TECHNICAL_FAILURES);
        return principal;
    }

    private static String correlationId() {
        return MDC.get("correlationId");
    }
}
