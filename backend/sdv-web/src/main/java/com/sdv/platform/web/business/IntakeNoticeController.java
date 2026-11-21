package com.sdv.platform.web.business;

import com.sdv.platform.application.reports.StewardNoticeService;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notices for the record's Document stewards (Document and Restricted stewards: CHANGE_SENSITIVITY_LEVEL) that a
 * sending system sent an uncorrected copy of a corrected document and nothing was stored (task 0032). Task 0033 (0.1):
 * the notices are stored, and each steward sees a notice until they mark it read.
 * <ul>
 *   <li>{@code GET /intake-notices}: this person's unread notices, newest first (at most 50), and how many are
 *       unread: {@code {"notices": [...], "unread": n}}.</li>
 *   <li>{@code POST /intake-notices/read} with {@code {"noticeIds": [...]}}: marks them read by this person (audited
 *       as STEWARD_NOTICE_READ); {@code {"marked": n}}. SDV-INT-002: a notice isn't the tenant's; SDV-INT-003: none, or
 *       more than 100.</li>
 * </ul>
 * Each names the record by its primary identifier and the sending system, never a file or its content. Anyone else is
 * refused with SDV-INT-001 (audited as AUTHORIZATION_DENIED).
 */
@RestController
@RequestMapping("/api/v1/business")
public class IntakeNoticeController {
    static final String REFUSED = "Intake notices are for Document stewards.";

    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final StewardNoticeService notices;

    public IntakeNoticeController(SecurityContextFacade security, AuthorizationService authorization,
                                  StewardNoticeService notices) {
        this.security = security;
        this.authorization = authorization;
        this.notices = notices;
    }

    @GetMapping("/intake-notices")
    public ResponseEntity<StewardNoticeService.Notices> notices() {
        SdvPrincipal principal = requireSteward();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                .body(notices.unread(principal.tenantId(), principal.subject()));
    }

    @PostMapping("/intake-notices/read")
    public ResponseEntity<Map<String, Integer>> markRead(@RequestBody(required = false) MarkRead body) {
        SdvPrincipal principal = requireSteward();
        int marked = notices.markRead(principal.tenantId(), principal.subject(), MDC.get("correlationId"),
                body == null ? null : ids(body.noticeIds()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("marked", marked));
    }

    public record MarkRead(List<String> noticeIds) { }

    /** A reference that is not a UUID is refused like a missing one (SDV-INT-003), with a code. */
    private static List<UUID> ids(List<String> values) {
        if (values == null) return null;
        try {
            return values.stream().map(v -> v == null ? null : UUID.fromString(v.trim())).toList();
        } catch (IllegalArgumentException ex) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.INT_003, "A notice reference is not valid.");
        }
    }

    private SdvPrincipal requireSteward() {
        SdvPrincipal principal = security.requirePrincipal();
        try {
            authorization.require(principal, Capability.CHANGE_SENSITIVITY_LEVEL);
        } catch (AccessDeniedException ex) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.INT_001, REFUSED);
        }
        return principal;
    }
}
