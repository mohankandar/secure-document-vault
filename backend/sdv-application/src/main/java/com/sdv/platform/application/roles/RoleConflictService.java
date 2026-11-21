package com.sdv.platform.application.roles;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RoleGovernancePort;
import com.sdv.platform.application.port.RoleGovernancePort.RoleConflictProblem;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 0025, rule 1: records a sign-in whose roles can never be held together. The problem record is what the Tenant
 * IT Admin sees (GET /api/v1/admin/role-conflicts). The audit event ROLE_CONFLICT_DETECTED is written when the problem
 * is first seen; later sign-ins of the same person with the same roles only update the problem's last-seen time and
 * count, so not every request of that person adds an audit event.
 */
@Service
public class RoleConflictService {
    public static final String AUDIT_ACTION = "ROLE_CONFLICT_DETECTED";
    /** Task 0026, carry-over 3: written once when a conflict ends. */
    public static final String ENDED_AUDIT_ACTION = "ROLE_CONFLICT_ENDED";
    static final int LIST_LIMIT = 200;

    private final RoleGovernancePort store;
    private final AuditEventPort audit;
    private final Clock clock;

    @Autowired
    public RoleConflictService(RoleGovernancePort store, AuditEventPort audit) {
        this(store, audit, Clock.systemUTC());
    }

    RoleConflictService(RoleGovernancePort store, AuditEventPort audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public void record(UUID tenantId, String subject, String displayName, String resolvedFrom,
                       List<String> heldRoles, List<String> withheldRoles, List<String> brokenRules) {
        Instant now = clock.instant();
        List<String> held = heldRoles.stream().sorted().toList();
        List<String> withheld = withheldRoles.stream().sorted().toList();
        boolean first = store.recordRoleConflict(new RoleConflictProblem(UUID.randomUUID(), tenantId, subject,
                displayName, resolvedFrom, held, withheld, List.copyOf(brokenRules), now, now, 1));
        if (!first) return;
        String details = "{\"resolvedFrom\":" + RoleJson.quote(resolvedFrom)
                + ",\"heldRoles\":" + RoleJson.array(held)
                + ",\"withheldRoles\":" + RoleJson.array(withheld)
                + ",\"rules\":" + RoleJson.array(brokenRules) + "}";
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", subject, AUDIT_ACTION, "USER", subject, now,
                null, AuditOutcome.DENIED, details, displayName));
    }

    /**
     * Task 0026, carry-over 3: the person signed in from this source and their roles no longer conflict. Their open
     * problems from that source are removed and one ROLE_CONFLICT_ENDED event is written, in one transaction. With no
     * open problem nothing happens, so a conflict that lasts or a sign-in that never conflicted adds no event.
     */
    @Transactional
    public void ended(UUID tenantId, String subject, String displayName, String resolvedFrom) {
        List<RoleConflictProblem> ended = store.endRoleConflicts(tenantId, subject, resolvedFrom);
        if (ended.isEmpty()) return;
        List<String> held = ended.stream().flatMap(p -> p.heldRoles().stream()).distinct().sorted().toList();
        List<String> withheld = ended.stream().flatMap(p -> p.withheldRoles().stream()).distinct().sorted().toList();
        Instant firstSeen = ended.stream().map(RoleConflictProblem::firstSeenAt).min(Instant::compareTo)
                .orElseThrow(() -> new InternalCheckFailed("An ended role conflict has no first-seen time."));
        long occurrences = ended.stream().mapToLong(RoleConflictProblem::occurrences).sum();
        String details = "{\"resolvedFrom\":" + RoleJson.quote(resolvedFrom)
                + ",\"heldRoles\":" + RoleJson.array(held)
                + ",\"withheldRoles\":" + RoleJson.array(withheld)
                + ",\"firstSeenAt\":" + RoleJson.quote(firstSeen.toString())
                + ",\"occurrences\":" + occurrences + "}";
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", subject, ENDED_AUDIT_ACTION, "USER", subject,
                clock.instant(), null, AuditOutcome.SUCCESS, details, displayName));
    }

    public List<RoleConflictProblem> list(UUID tenantId) {
        return store.findRoleConflicts(tenantId, LIST_LIMIT);
    }
}
