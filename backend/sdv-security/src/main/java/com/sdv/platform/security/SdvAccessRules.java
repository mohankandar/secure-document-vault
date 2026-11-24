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

    /** Task 0062: admins never open sensitive documents, so nobody is Business Admin and Document Steward at once. */
    public static final String BUSINESS_ADMIN_NOT_STEWARD = "BUSINESS_ADMIN_NOT_STEWARD";
    /** Task 0062: an IT Admin never has a role that opens documents. */
    public static final String IT_ADMIN_NOT_DOCUMENT_ROLE = "IT_ADMIN_NOT_DOCUMENT_ROLE";

    public static final RoleSeparationPolicy<SdvRole> SEPARATION = new RoleSeparationPolicy<>(List.of(
            new RoleSeparationRule<>(RESTRICTED_STEWARD_NOT_ADMIN,
                    EnumSet.of(SdvRole.RESTRICTED_STEWARD),
                    EnumSet.of(SdvRole.BUSINESS_ADMIN, SdvRole.IT_ADMIN, SdvRole.PLATFORM_OPERATIONS_ADMIN)),
            new RoleSeparationRule<>(BUSINESS_ADMIN_NOT_STEWARD,
                    EnumSet.of(SdvRole.BUSINESS_ADMIN),
                    EnumSet.of(SdvRole.DOCUMENT_STEWARD)),
            new RoleSeparationRule<>(IT_ADMIN_NOT_DOCUMENT_ROLE,
                    EnumSet.of(SdvRole.IT_ADMIN),
                    EnumSet.of(SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD, SdvRole.RESTRICTED_STEWARD))));

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
