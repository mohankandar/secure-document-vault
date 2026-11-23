package com.sdv.platform.security.auth;

import com.sdv.platform.security.SdvRole;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0025, rule 1: told when a person's sign-in named roles that can never be held together. The roles are already
 * withheld; the reporter writes the audit event and the problem record the IT Admin sees. If it throws, the
 * sign-in is refused (fail closed): a conflict is never left unrecorded.
 */
public interface RoleConflictReporter {
    /** Where the roles were resolved. */
    enum Source { LOGIN_GROUPS, LOCAL_SIGN_IN }

    record RoleConflict(UUID tenantId, String subject, String displayName, Source source,
                        Set<SdvRole> held, Set<SdvRole> withheld, List<String> brokenRules) { }

    void report(RoleConflict conflict);

    /**
     * Task 0026, carry-over 3: this sign-in's roles don't conflict. A conflict this person had from this source has
     * ended; the reporter audits that once. Failing here never refuses the sign-in: the open problem stays, so the
     * next sign-in tries again.
     */
    default void noConflict(java.util.UUID tenantId, String subject, String displayName, Source source) { }
}
