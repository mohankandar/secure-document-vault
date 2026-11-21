package com.sdv.platform.web.business;

import com.sdv.platform.application.sensitivity.SensitivityLevelService;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Task 0028 (S5): a Steward (CHANGE_SENSITIVITY_LEVEL) raises a level, or asks for it to be lowered, for one document
 * or a whole record. Anyone else is refused with SDV-SEN-010 (and the refusal is audited as AUTHORIZATION_DENIED).
 * <ul>
 *   <li>{@code POST /documents/{documentId}/sensitivity/raise} and {@code /records/{recordRef}/sensitivity/raise}
 *       with {@code {level, reason}}: applies at once (200).</li>
 *   <li>{@code POST .../sensitivity/lower-request} with {@code {level, reason}}: a request for a Business Admin (201).</li>
 * </ul>
 * The record reference is the one search and record detail hand out. References are taken as text, so a malformed
 * one gets its coded 404 like an unknown one.
 */
@RestController
@RequestMapping("/api/v1/business")
public class SensitivityLevelController {
    static final String REFUSED = "Only a Steward can change a sensitivity level.";
    static final String HISTORY_REFUSED = "Only a Steward or a Business admin can see the level history.";

    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final SensitivityLevelService levels;

    public SensitivityLevelController(SecurityContextFacade security, AuthorizationService authorization,
                                      SensitivityLevelService levels) {
        this.security = security;
        this.authorization = authorization;
        this.levels = levels;
    }

    @PostMapping("/documents/{documentId}/sensitivity/raise")
    public ResponseEntity<SensitivityLevelService.LevelChange> raiseDocument(
            @PathVariable String documentId, @RequestBody(required = false) LevelRequest request) {
        SdvPrincipal p = requireSteward();
        LevelRequest r = safe(request);
        return noStore(HttpStatus.OK, levels.raiseDocument(p.tenantId(), p.subject(), p.displayName(),
                authorization.clearance(p), documentId, r.level(), r.reason(), MDC.get("correlationId")));
    }

    @PostMapping("/documents/{documentId}/sensitivity/lower-request")
    public ResponseEntity<SensitivityLevelService.LevelChange> lowerDocument(
            @PathVariable String documentId, @RequestBody(required = false) LevelRequest request) {
        SdvPrincipal p = requireSteward();
        LevelRequest r = safe(request);
        return noStore(HttpStatus.CREATED, levels.requestLowerDocument(p.tenantId(), p.subject(), p.displayName(),
                authorization.clearance(p), documentId, r.level(), r.reason(), MDC.get("correlationId")));
    }

    @PostMapping("/records/{recordRef}/sensitivity/raise")
    public ResponseEntity<SensitivityLevelService.LevelChange> raiseRecord(
            @PathVariable String recordRef, @RequestBody(required = false) LevelRequest request) {
        SdvPrincipal p = requireSteward();
        LevelRequest r = safe(request);
        return noStore(HttpStatus.OK, levels.raiseRecord(p.tenantId(), p.subject(), p.displayName(),
                authorization.clearance(p), recordRef, r.level(), r.reason(), MDC.get("correlationId")));
    }

    @PostMapping("/records/{recordRef}/sensitivity/lower-request")
    public ResponseEntity<SensitivityLevelService.LevelChange> lowerRecord(
            @PathVariable String recordRef, @RequestBody(required = false) LevelRequest request) {
        SdvPrincipal p = requireSteward();
        LevelRequest r = safe(request);
        return noStore(HttpStatus.CREATED, levels.requestLowerRecord(p.tenantId(), p.subject(), p.displayName(),
                authorization.clearance(p), recordRef, r.level(), r.reason(), MDC.get("correlationId")));
    }

    /**
     * Task 0029 (0.4): a document's level changes with their reasons, newest first. Stewards (who must be able to open
     * the document) and Business Admins; anyone else is refused with SDV-SEN-018.
     */
    @GetMapping("/documents/{documentId}/sensitivity/history")
    public ResponseEntity<List<SensitivityLevelService.HistoryEntry>> documentHistory(@PathVariable String documentId) {
        SdvPrincipal p = requireHistoryReader();
        boolean businessAdmin = authorization.has(p, Capability.APPROVE_SENSITIVE_ACCESS);
        return noStore(HttpStatus.OK, levels.documentHistory(p.tenantId(), p.subject(), authorization.clearance(p),
                businessAdmin, documentId, MDC.get("correlationId")));
    }

    /** Task 0029 (0.4): a record's level changes with their reasons, newest first. Same readers as above. */
    @GetMapping("/records/{recordRef}/sensitivity/history")
    public ResponseEntity<List<SensitivityLevelService.HistoryEntry>> recordHistory(@PathVariable String recordRef) {
        SdvPrincipal p = requireHistoryReader();
        return noStore(HttpStatus.OK, levels.recordHistory(p.tenantId(), recordRef));
    }

    private SdvPrincipal requireHistoryReader() {
        SdvPrincipal principal = security.requirePrincipal();
        if (authorization.has(principal, Capability.CHANGE_SENSITIVITY_LEVEL)
                || authorization.has(principal, Capability.APPROVE_SENSITIVE_ACCESS)) {
            return principal;
        }
        try {
            authorization.require(principal, Capability.CHANGE_SENSITIVITY_LEVEL); // audits the refusal
        } catch (AccessDeniedException ex) {
            // refused below
        }
        throw new CodedRefusal.Forbidden(SdvErrorCode.SEN_018, HISTORY_REFUSED);
    }

    private SdvPrincipal requireSteward() {
        SdvPrincipal principal = security.requirePrincipal();
        try {
            authorization.require(principal, Capability.CHANGE_SENSITIVITY_LEVEL);
        } catch (AccessDeniedException ex) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.SEN_010, REFUSED);
        }
        return principal;
    }

    private static LevelRequest safe(LevelRequest request) {
        return request == null ? new LevelRequest(null, null) : request;
    }

    static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate()).body(body);
    }

    public record LevelRequest(String level, String reason) { }
}
