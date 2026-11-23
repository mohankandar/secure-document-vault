package com.sdv.platform.web.admin;

import com.sdv.platform.application.restrictions.RestrictionsService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 0055: the Business Admin's read-only Restrictions page (VIEW_RESTRICTIONS). Anyone else gets SDV-RST-001,
 * audited as AUTHORIZATION_DENIED. Nothing here changes anything.
 * <ul>
 *   <li>{@code GET /}: the overview, with the first page of each list;</li>
 *   <li>{@code GET /records?after=}: a page of records with a record level;</li>
 *   <li>{@code GET /level-changes?after=}: a page of the last 30 days' level changes.</li>
 * </ul>
 * A page position Vero didn't give is SDV-RST-002.
 */
@RestController
@RequestMapping("/api/v1/admin/restrictions")
public class RestrictionsController {
    static final String REFUSED = "Only a Business Admin can see the restrictions.";

    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final RestrictionsService restrictions;

    public RestrictionsController(SecurityContextFacade security, AuthorizationService authorization,
                                  RestrictionsService restrictions) {
        this.security = security;
        this.authorization = authorization;
        this.restrictions = restrictions;
    }

    @GetMapping
    public ResponseEntity<RestrictionsService.Overview> overview() {
        return noStore(restrictions.overview(admin().tenantId()));
    }

    @GetMapping("/records")
    public ResponseEntity<RestrictionsService.RecordPage> records(@RequestParam(required = false) String after) {
        return noStore(restrictions.records(admin().tenantId(), after));
    }

    @GetMapping("/level-changes")
    public ResponseEntity<RestrictionsService.ChangePage> changes(@RequestParam(required = false) String after) {
        return noStore(restrictions.changes(admin().tenantId(), after));
    }

    private SdvPrincipal admin() {
        SdvPrincipal principal = security.requirePrincipal();
        try {
            authorization.require(principal, Capability.VIEW_RESTRICTIONS);
        } catch (AccessDeniedException ex) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.RST_001, REFUSED);
        }
        return principal;
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate()).body(body);
    }
}
