package com.sdv.platform.security.auth;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.SdvAccessRules;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.separation.RoleSeparationPolicy;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0025, rule 1: the one step every sign-in path runs on the roles it resolved. Conflicting roles are withheld and
 * reported. Fail closed: with no reporter, or when reporting fails, a conflicted sign-in is refused with
 * {@link RefusedException}; so is one left with no role.
 */
public final class RoleSeparationGuard {
    /** The sign-in's 401 carries the code at the start of its description (there is no JSON body at sign-in). */
    public static final String NO_ROLE_LEFT = SdvErrorCode.ROL_004.code()
            + ": the roles from this sign-in can never be held together, so none of them were given.";
    public static final String NOT_RECORDED = SdvErrorCode.ROL_005.code()
            + ": Vero couldn't record a role conflict in this sign-in, so the sign-in was refused.";
    public static final String NO_ROLE = "Authenticated identity has no mapped SDV role.";

    private final RoleSeparationPolicy<SdvRole> policy;
    private final RoleConflictReporter reporter;

    public RoleSeparationGuard(RoleConflictReporter reporter) {
        this(SdvAccessRules.SEPARATION, reporter);
    }

    public RoleSeparationGuard(RoleSeparationPolicy<SdvRole> policy, RoleConflictReporter reporter) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.reporter = reporter;
    }

    public EnumSet<SdvRole> apply(UUID tenantId, String subject, String displayName,
                                  RoleConflictReporter.Source source, Set<SdvRole> held) {
        RoleSeparationPolicy.Resolution<SdvRole> resolution = policy.resolve(held);
        if (resolution.conflicted()) {
            if (reporter == null) throw new RefusedException(NOT_RECORDED);
            try {
                reporter.report(new RoleConflictReporter.RoleConflict(tenantId, subject, displayName, source,
                        resolution.held(), resolution.withheld(), resolution.brokenRules()));
            } catch (RuntimeException ex) {
                throw new RefusedException(NOT_RECORDED);
            }
        }
        if (resolution.granted().isEmpty()) {
            throw new RefusedException(resolution.conflicted() ? NO_ROLE_LEFT : NO_ROLE);
        }
        return EnumSet.copyOf(resolution.granted());
    }

    /** The sign-in is refused. The message names no group and no role. */
    public static final class RefusedException extends RuntimeException {
        public RefusedException(String message) {
            super(message);
        }
    }
}
