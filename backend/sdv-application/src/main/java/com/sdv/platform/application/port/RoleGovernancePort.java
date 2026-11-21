package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0025: storage for the role separation rules (changeset 025). Role names are plain text here, so this layer
 * doesn't depend on a product's role list.
 */
public interface RoleGovernancePort {
    /** One person's sign-in that named roles which can never be held together. */
    record RoleConflictProblem(UUID problemId, UUID tenantId, String subject, String displayName, String resolvedFrom,
                               List<String> heldRoles, List<String> withheldRoles, List<String> brokenRules,
                               Instant firstSeenAt, Instant lastSeenAt, long occurrences) { }

    /** A change to the login groups that give a watched role, for one tenant's Business Admin. Before is null at first. */
    record RoleMappingNotice(UUID noticeId, UUID tenantId, String roleName, List<String> groupsBefore,
                             List<String> groupsAfter, Instant createdAt) { }

    /**
     * Adds the problem, or, when the same person, source and held roles are already recorded, updates its last-seen
     * time and count. Returns true only when the problem is new.
     */
    boolean recordRoleConflict(RoleConflictProblem problem);

    List<RoleConflictProblem> findRoleConflicts(UUID tenantId, int limit);

    /**
     * Task 0026, carry-over 3: the person signed in from this source with roles that no longer conflict. Removes their
     * open problems from that source and returns them, all in one statement, so only one caller ever gets them.
     * Empty when there were none.
     */
    List<RoleConflictProblem> endRoleConflicts(UUID tenantId, String subject, String resolvedFrom);

    /** The login groups last recorded for {@code roleName}; empty when nothing was ever recorded. */
    Optional<List<String>> findRecordedGroups(String roleName);

    void saveRecordedGroups(String roleName, List<String> groups, Instant recordedAt);

    List<UUID> tenantIds();

    void addNotice(RoleMappingNotice notice);

    List<RoleMappingNotice> findNotices(UUID tenantId, int limit);
}
