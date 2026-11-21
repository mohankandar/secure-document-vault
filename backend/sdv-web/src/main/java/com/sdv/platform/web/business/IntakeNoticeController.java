package com.sdv.platform.web.business;

import com.sdv.platform.application.reports.IntakeReportService;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Task 0032 (0): notices for the record's Document stewards (Document and Restricted stewards: CHANGE_SENSITIVITY_LEVEL)
 * that a sending system sent an uncorrected copy of a corrected document and nothing was stored. The last 30 days,
 * newest first; each names the record by its primary identifier and the sending system, never a file or its content.
 * Anyone else is refused with SDV-INT-001 (audited as AUTHORIZATION_DENIED).
 */
@RestController
@RequestMapping("/api/v1/business")
public class IntakeNoticeController {
    static final String REFUSED = "Intake notices are for Document stewards.";

    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final IntakeReportService reports;

    public IntakeNoticeController(SecurityContextFacade security, AuthorizationService authorization,
                                  IntakeReportService reports) {
        this.security = security;
        this.authorization = authorization;
        this.reports = reports;
    }

    @GetMapping("/intake-notices")
    public ResponseEntity<List<IntakeReportService.IntakeNotice>> notices() {
        SdvPrincipal principal = security.requirePrincipal();
        try {
            authorization.require(principal, Capability.CHANGE_SENSITIVITY_LEVEL);
        } catch (AccessDeniedException ex) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.INT_001, REFUSED);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(reports.uncorrectedCopyNotices(principal.tenantId()));
    }
}
