package com.sdv.platform.web.admin;

import com.sdv.platform.application.port.RoleGovernancePort.RoleConflictProblem;
import com.sdv.platform.application.port.RoleGovernancePort.RoleMappingNotice;
import com.sdv.platform.application.roles.RoleConflictService;
import com.sdv.platform.application.roles.RoleMappingWatchService;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Task 0025. Read-only, newest first, at most 200 each:
 * <ul>
 *   <li>role conflicts (rule 1), for the Tenant IT Admin (CONFIGURE_TENANT_AUTH);</li>
 *   <li>notices of changes to the login groups that give Restricted Steward (rule 3), for the Business Admin
 *       (CONFIGURE_ROLE_PERMISSIONS).</li>
 * </ul>
 * No screen shows them yet.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class RoleGovernanceAdminController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final RoleConflictService conflicts;
    private final RoleMappingWatchService mappings;

    public RoleGovernanceAdminController(SecurityContextFacade security, AuthorizationService authorization,
                                         RoleConflictService conflicts, RoleMappingWatchService mappings) {
        this.security = security;
        this.authorization = authorization;
        this.conflicts = conflicts;
        this.mappings = mappings;
    }

    @GetMapping("/role-conflicts")
    public List<RoleConflictProblem> roleConflicts() {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.CONFIGURE_TENANT_AUTH);
        return conflicts.list(principal.tenantId());
    }

    @GetMapping("/role-notices")
    public List<RoleMappingNotice> roleNotices() {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.CONFIGURE_ROLE_PERMISSIONS);
        return mappings.notices(principal.tenantId());
    }
}
