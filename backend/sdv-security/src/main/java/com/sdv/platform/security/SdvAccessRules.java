package com.sdv.platform.security;

import com.sdv.platform.security.separation.ForbiddenGrant;
import com.sdv.platform.security.separation.RoleSeparationPolicy;
import com.sdv.platform.security.separation.RoleSeparationRule;

import java.util.EnumSet;
import java.util.List;

/**
 * Task 0025, section 3: Vero's separation rules, as data for the generic rules in
 * {@link com.sdv.platform.security.separation}. Every place roles are resolved applies {@link #SEPARATION}; the Roles &
 * permissions overrides apply {@link #FORBIDDEN_GRANTS}.
 */
public final class SdvAccessRules {
    /** Rule 1: a Restricted Steward can never also be a Business Admin, IT Admin or Platform Operations Admin. */
    public static final String RESTRICTED_STEWARD_NOT_ADMIN = "RESTRICTED_STEWARD_NOT_ADMIN";

    public static final RoleSeparationPolicy<SdvRole> SEPARATION = new RoleSeparationPolicy<>(List.of(
            new RoleSeparationRule<>(RESTRICTED_STEWARD_NOT_ADMIN,
                    EnumSet.of(SdvRole.RESTRICTED_STEWARD),
                    EnumSet.of(SdvRole.BUSINESS_ADMIN, SdvRole.IT_ADMIN, SdvRole.PLATFORM_OPERATIONS_ADMIN))));

    /** Rule 2: what a Business Admin's role-permission override may never give. */
    public static final List<ForbiddenGrant<SdvRole, Capability>> FORBIDDEN_GRANTS = List.of(
            new ForbiddenGrant<>("ADMINS_NEVER_SEE_SENSITIVE_DOCUMENTS",
                    EnumSet.of(SdvRole.BUSINESS_ADMIN, SdvRole.IT_ADMIN, SdvRole.PLATFORM_OPERATIONS_ADMIN),
                    EnumSet.of(Capability.VIEW_CONFIDENTIAL, Capability.VIEW_HIGHLY_CONFIDENTIAL,
                            Capability.REQUEST_SENSITIVE_ACCESS)),
            new ForbiddenGrant<>("STEWARDS_NEVER_APPROVE_SENSITIVE_ACCESS",
                    EnumSet.of(SdvRole.DOCUMENT_STEWARD, SdvRole.RESTRICTED_STEWARD),
                    EnumSet.of(Capability.APPROVE_SENSITIVE_ACCESS)));

    private SdvAccessRules() { }

    public static ForbiddenGrant<SdvRole, Capability> forbiddenGrant(SdvRole role, Capability capability) {
        return ForbiddenGrant.find(FORBIDDEN_GRANTS, role, capability);
    }
}
