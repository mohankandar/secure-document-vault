package com.sdv.platform.web.admin;

import com.documentplatform.identity.store.GrantChange;
import com.documentplatform.identity.store.GrantHistory;
import com.sdv.platform.application.port.RoleGovernancePort.RoleConflictProblem;
import com.sdv.platform.application.roles.RoleConflictService;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Task 0025. Read-only, newest first, at most 200 each:
 * <ul>
 *   <li>role conflicts (rule 1), for the IT Admin (CONFIGURE_TENANT_AUTH);</li>
 *   <li>task 0041: the entity's grant changes, for the Business Admin (CONFIGURE_ROLE_PERMISSIONS): which login group
 *       got or lost which role, by setup or in the app, from platform-core's grant history ({@code idn_grant_history},
 *       insert only). It replaces the notices of changed Restricted Steward groups (rule 3), which came from the old
 *       server settings.</li>
 * </ul>
 * No screen shows them yet.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class RoleGovernanceAdminController {
    static final int LIMIT = 200;
    static final Duration HISTORY_WINDOW = Duration.ofDays(366);

    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final RoleConflictService conflicts;
    private final GrantHistory grants;

    public RoleGovernanceAdminController(SecurityContextFacade security, AuthorizationService authorization,
                                         RoleConflictService conflicts, GrantHistory grants) {
        this.security = security;
        this.authorization = authorization;
        this.conflicts = conflicts;
        this.grants = grants;
    }

    @GetMapping("/role-conflicts")
    public List<RoleConflictProblem> roleConflicts() {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.CONFIGURE_TENANT_AUTH);
        return conflicts.list(principal.tenantId());
    }

    /** One grant change: no login system address and no setup details, only what the Business Admin reads. */
    public record GrantChangeView(String group, String roleBefore, String roleAfter, String change, String source,
                                  String changedBy, Instant changedAt) { }

    @GetMapping("/grant-history")
    public List<GrantChangeView> grantHistory() {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.CONFIGURE_ROLE_PERMISSIONS);
        Instant now = Instant.now();
        return grants.forEntity(principal.tenantId(), now.minus(HISTORY_WINDOW), now.plusSeconds(1)).stream()
                .sorted(Comparator.comparing(GrantChange::changedAt).reversed())
                .limit(LIMIT)
                .map(g -> new GrantChangeView(g.group(), g.roleBefore(), g.roleAfter(), g.change().name(),
                        g.source().name(), g.changedBy(), g.changedAt()))
                .toList();
    }
}
