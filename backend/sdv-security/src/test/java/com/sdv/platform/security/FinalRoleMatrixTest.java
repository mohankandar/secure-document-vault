package com.sdv.platform.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalRoleMatrixTest {
    /**
     * Task 0025: every role against every capability. A capability added without a decision here fails the test, as
     * does any cell that differs. Task 0025's table: VIEW_CONFIDENTIAL and CHANGE_SENSITIVITY_LEVEL for both stewards;
     * VIEW_HIGHLY_CONFIDENTIAL and REQUEST_SENSITIVE_ACCESS for the Restricted Steward only; APPROVE_SENSITIVE_ACCESS
     * and MANAGE_LEGAL_HOLD for the Business Admin only.
     */
    private static final Map<SdvRole, Set<Capability>> EXPECTED = new EnumMap<>(SdvRole.class);

    static {
        Set<Capability> steward = EnumSet.of(
                Capability.SEARCH_RECORDS, Capability.VIEW_PDF, Capability.PERMANENT_REDACTION,
                Capability.DOWNLOAD_CURRENT_PDF, Capability.PRINT_DOCUMENT, Capability.ADD_DOCUMENT_TAG,
                Capability.ADD_DOCUMENT_COMMENT, Capability.VIEW_CONFIDENTIAL, Capability.CHANGE_SENSITIVITY_LEVEL);
        EnumSet<Capability> restricted = EnumSet.copyOf(steward);
        restricted.add(Capability.VIEW_HIGHLY_CONFIDENTIAL);
        restricted.add(Capability.REQUEST_SENSITIVE_ACCESS);
        EXPECTED.put(SdvRole.BUSINESS_USER, EnumSet.of(Capability.SEARCH_RECORDS, Capability.VIEW_PDF));
        EXPECTED.put(SdvRole.DOCUMENT_STEWARD, steward);
        EXPECTED.put(SdvRole.RESTRICTED_STEWARD, restricted);
        EXPECTED.put(SdvRole.BUSINESS_ADMIN, EnumSet.of(
                Capability.CONFIGURE_METADATA_SEARCH, Capability.CONFIGURE_RETENTION,
                Capability.CONFIGURE_BUSINESS_LIMITS, Capability.CONFIGURE_ROLE_PERMISSIONS,
                Capability.APPROVE_SENSITIVE_ACCESS, Capability.MANAGE_LEGAL_HOLD));
        EXPECTED.put(SdvRole.TENANT_IT_ADMIN, EnumSet.of(
                Capability.CONFIGURE_INGESTION_SOURCES, Capability.CONFIGURE_TENANT_AUTH,
                Capability.CONFIGURE_STORAGE_KEY_PROVIDER, Capability.VIEW_TECHNICAL_FAILURES));
        EXPECTED.put(SdvRole.PLATFORM_OPERATIONS_ADMIN, EnumSet.of(Capability.VIEW_TECHNICAL_FAILURES));
    }

    @Test
    void everyCellOfTheRoleMatrixIsExact() {
        assertEquals(EnumSet.allOf(SdvRole.class), EXPECTED.keySet(), "every role has a row");
        List<String> wrong = new ArrayList<>();
        for (SdvRole role : SdvRole.values()) {
            for (Capability capability : Capability.values()) {
                boolean expected = EXPECTED.get(role).contains(capability);
                boolean actual = RoleCapabilities.has(EnumSet.of(role), capability);
                if (expected != actual) wrong.add(role + " / " + capability + ": expected " + expected);
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
        for (SdvRole role : SdvRole.values()) assertEquals(EXPECTED.get(role), RoleCapabilities.capabilities(role), role.name());
    }

    @Test
    void v1RoleMatrixIsExact() {
        assertEquals(
                Set.of(Capability.SEARCH_RECORDS, Capability.VIEW_PDF),
                RoleCapabilities.capabilities(EnumSet.of(SdvRole.BUSINESS_USER)));

        assertEquals(
                Set.of(
                        Capability.SEARCH_RECORDS,
                        Capability.VIEW_PDF,
                        Capability.PERMANENT_REDACTION,
                        Capability.DOWNLOAD_CURRENT_PDF,
                        Capability.PRINT_DOCUMENT,
                        Capability.ADD_DOCUMENT_TAG,
                        Capability.ADD_DOCUMENT_COMMENT,
                        Capability.VIEW_CONFIDENTIAL,
                        Capability.CHANGE_SENSITIVITY_LEVEL),
                RoleCapabilities.capabilities(EnumSet.of(SdvRole.DOCUMENT_STEWARD)));

        assertEquals(
                Set.of(
                        Capability.CONFIGURE_METADATA_SEARCH,
                        Capability.CONFIGURE_RETENTION,
                        Capability.CONFIGURE_BUSINESS_LIMITS,
                        Capability.CONFIGURE_ROLE_PERMISSIONS,
                        Capability.APPROVE_SENSITIVE_ACCESS,
                        Capability.MANAGE_LEGAL_HOLD),
                RoleCapabilities.capabilities(EnumSet.of(SdvRole.BUSINESS_ADMIN)));

        assertEquals(
                Set.of(
                        Capability.CONFIGURE_INGESTION_SOURCES,
                        Capability.CONFIGURE_TENANT_AUTH,
                        Capability.CONFIGURE_STORAGE_KEY_PROVIDER,
                        Capability.VIEW_TECHNICAL_FAILURES),
                RoleCapabilities.capabilities(EnumSet.of(SdvRole.TENANT_IT_ADMIN)));

        assertEquals(
                Set.of(
                        Capability.VIEW_TECHNICAL_FAILURES),
                RoleCapabilities.capabilities(EnumSet.of(SdvRole.PLATFORM_OPERATIONS_ADMIN)));
    }

    @Test
    void explicitlyAssignedRolesComposeWithoutImplicitAdminContentAccess() {
        assertEquals(
                Set.of(
                        Capability.SEARCH_RECORDS,
                        Capability.VIEW_PDF,
                        Capability.CONFIGURE_METADATA_SEARCH,
                        Capability.CONFIGURE_RETENTION,
                        Capability.CONFIGURE_BUSINESS_LIMITS,
                        Capability.CONFIGURE_ROLE_PERMISSIONS,
                        Capability.APPROVE_SENSITIVE_ACCESS,
                        Capability.MANAGE_LEGAL_HOLD),
                RoleCapabilities.capabilities(EnumSet.of(
                        SdvRole.BUSINESS_USER,
                        SdvRole.BUSINESS_ADMIN)));
    }
}
