package com.sdv.platform.application.roles;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RoleGovernancePort;
import com.sdv.platform.application.port.RoleGovernancePort.RoleMappingNotice;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 0025, rule 3: the login groups that give a watched role (in Vero, Restricted Steward) come from the server's
 * settings. At every start they are compared with the groups recorded the last time. When they differ, every tenant
 * gets the audit event ROLE_GROUP_MAPPING_CHANGED and a notice for its Business Admin, and the new groups are recorded,
 * all in one transaction. On the first start it is a change when any group is configured.
 */
@Service
public class RoleMappingWatchService {
    public static final String AUDIT_ACTION = "ROLE_GROUP_MAPPING_CHANGED";
    public static final String SYSTEM_ACTOR = "sdv-server";
    static final int LIST_LIMIT = 200;

    private final RoleGovernancePort store;
    private final AuditEventPort audit;
    private final Clock clock;

    @Autowired
    public RoleMappingWatchService(RoleGovernancePort store, AuditEventPort audit) {
        this(store, audit, Clock.systemUTC());
    }

    RoleMappingWatchService(RoleGovernancePort store, AuditEventPort audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    /** Returns true when the groups changed; the change is then audited, noticed and recorded. */
    @Transactional
    public boolean check(String roleName, Collection<String> configuredGroups) {
        Objects.requireNonNull(roleName, "roleName");
        List<String> after = configuredGroups == null ? List.of()
                : configuredGroups.stream().map(String::trim).filter(g -> !g.isEmpty()).distinct().sorted().toList();
        List<String> before = store.findRecordedGroups(roleName)
                .map(groups -> groups.stream().sorted().toList()).orElse(null);
        Instant at = clock.instant();
        if (before == null && after.isEmpty()) {
            store.saveRecordedGroups(roleName, after, at);
            return false;
        }
        if (after.equals(before)) return false;

        String details = "{\"role\":" + RoleJson.quote(roleName)
                + ",\"groupsBefore\":" + RoleJson.array(before)
                + ",\"groupsAfter\":" + RoleJson.array(after) + "}";
        for (UUID tenantId : store.tenantIds()) {
            audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "SYSTEM", SYSTEM_ACTOR, AUDIT_ACTION, "ROLE",
                    roleName, at, null, AuditOutcome.SUCCESS, details));
            store.addNotice(new RoleMappingNotice(UUID.randomUUID(), tenantId, roleName, before, after, at));
        }
        store.saveRecordedGroups(roleName, after, at);
        return true;
    }

    public List<RoleMappingNotice> notices(UUID tenantId) {
        return store.findNotices(tenantId, LIST_LIMIT);
    }
}
