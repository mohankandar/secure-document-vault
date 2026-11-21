package com.sdv.platform.security.auth;

import com.sdv.platform.application.roles.RoleConflictService;
import com.sdv.platform.security.SdvRole;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Task 0025, rule 1: writes each role conflict as a problem record for the Tenant IT Admin, with an audit event. */
@Component
public class RecordingRoleConflictReporter implements RoleConflictReporter {
    private final RoleConflictService conflicts;

    public RecordingRoleConflictReporter(RoleConflictService conflicts) {
        this.conflicts = conflicts;
    }

    @Override
    public void report(RoleConflict conflict) {
        conflicts.record(conflict.tenantId(), conflict.subject(), conflict.displayName(), conflict.source().name(),
                names(conflict.held()), names(conflict.withheld()), conflict.brokenRules());
    }

    @Override
    public void noConflict(java.util.UUID tenantId, String subject, String displayName, Source source) {
        conflicts.ended(tenantId, subject, displayName, source.name());
    }

    private static java.util.List<String> names(Set<SdvRole> roles) {
        return roles.stream().map(Enum::name).sorted().toList();
    }
}
