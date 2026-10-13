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
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

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
        return operations.summary(principal.tenantId());
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
            @RequestParam(required = false) String cursor) {
        SdvPrincipal principal = requireTechnicalView();
        return audit.search(principal.tenantId(), principal.subject(),
                new AuditQueryService.SearchRequest(action, outcome, actorType, actorId, targetType, targetId,
                        correlationId, from, to, limit, cursor), correlationId());
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
