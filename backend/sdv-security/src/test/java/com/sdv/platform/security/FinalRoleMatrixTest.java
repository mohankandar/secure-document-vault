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
                Capability.ADD_DOCUMENT_COMMENT, Capability.VIEW_CONFIDENTIAL, Capability.CHANGE_SENSITIVITY_LEVEL,
                // Task 0048: both stewards quarantine and review (levels they can open); filter quarantine is the
                // Restricted Steward's only.
                Capability.QUARANTINE_DOCUMENTS);
        EnumSet<Capability> restricted = EnumSet.copyOf(steward);
        restricted.add(Capability.QUARANTINE_BY_FILTER);
        restricted.add(Capability.VIEW_HIGHLY_CONFIDENTIAL);
        restricted.add(Capability.REQUEST_SENSITIVE_ACCESS);
        EXPECTED.put(SdvRole.BUSINESS_USER, EnumSet.of(Capability.SEARCH_RECORDS, Capability.VIEW_PDF));
        EXPECTED.put(SdvRole.DOCUMENT_STEWARD, steward);
        EXPECTED.put(SdvRole.RESTRICTED_STEWARD, restricted);
        EXPECTED.put(SdvRole.BUSINESS_ADMIN, EnumSet.of(
                Capability.CONFIGURE_METADATA_SEARCH, Capability.CONFIGURE_RETENTION,
                Capability.CONFIGURE_BUSINESS_LIMITS, Capability.CONFIGURE_ROLE_PERMISSIONS,
                Capability.APPROVE_SENSITIVE_ACCESS, Capability.MANAGE_LEGAL_HOLD, Capability.VIEW_INTAKE_REPORTS,
                // Task 0050: the sealed audit report, for the Business Admin and IT Admin (no auditor role).
                Capability.GENERATE_AUDIT_REPORT,
                // Task 0055: the Restrictions page, Business Admin only.
                Capability.VIEW_RESTRICTIONS));
        EXPECTED.put(SdvRole.IT_ADMIN, EnumSet.of(
                Capability.CONFIGURE_INGESTION_SOURCES, Capability.CONFIGURE_TENANT_AUTH,
                Capability.CONFIGURE_STORAGE_KEY_PROVIDER, Capability.VIEW_TECHNICAL_FAILURES, Capability.GENERATE_AUDIT_REPORT));
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

    /**
     * Task 0026, carry-over 1: docs/design/sensitivity/ROLE-MATRIX.md sections 3.2 and 3.4, every cell, as the
     * capability each function needs. Section 3.1 (documents, with its L cells) is checked through the real
     * controllers and services in DocumentLevelEnforcementTest (sdv-web), for every role and level.
     * Two cells carry a condition that isn't a capability. Task 0028 enforces both in the services, tested in sdv-web:
     * a Steward may ask to lower only from Confidential (SensitivityLevelChangeTest, SDV-SEN-015), and nobody approves
     * their own request (AccessRequestApprovalsTest and AccessRequestPostgresTest, SDV-APR-006 and the table's check).
     * The endpoints' permission checks for these cells are in ApprovalsControllerTest.
     */
    @Test
    void sections32And34OfTheSensitivityMatrix() {
        // Roles in the matrix's column order: Business User, Steward, Restricted Steward, Business Admin, IT Admin,
        // Platform Ops. ✓ = 1.
        Map<String, Object[]> rows = new java.util.LinkedHashMap<>();
        rows.put("3.2 Raise the level", new Object[]{Capability.CHANGE_SENSITIVITY_LEVEL, "011000"});
        rows.put("3.2 Request to lower", new Object[]{Capability.CHANGE_SENSITIVITY_LEVEL, "011000"});
        rows.put("3.2 Approve lowering", new Object[]{Capability.APPROVE_SENSITIVE_ACCESS, "000100"});
        rows.put("3.4 Request print / download of Highly Confidential", new Object[]{Capability.REQUEST_SENSITIVE_ACCESS, "001000"});
        rows.put("3.4 Approve or decline requests", new Object[]{Capability.APPROVE_SENSITIVE_ACCESS, "000100"});
        rows.put("3.4 Place and release legal holds", new Object[]{Capability.MANAGE_LEGAL_HOLD, "000100"});
        rows.put("Task 0032: Reports tab, seen by Business admin only", new Object[]{Capability.VIEW_INTAKE_REPORTS, "000100"});
        rows.put("Task 0048: Quarantine and review", new Object[]{Capability.QUARANTINE_DOCUMENTS, "011000"});
        rows.put("Task 0048: Quarantine by filter (whole incident)", new Object[]{Capability.QUARANTINE_BY_FILTER, "001000"});
        rows.put("Task 0050: Sealed audit report: generate, download, verify", new Object[]{Capability.GENERATE_AUDIT_REPORT, "000110"});
        rows.put("Task 0055: Restrictions page, Business admin only", new Object[]{Capability.VIEW_RESTRICTIONS, "000100"});
        List<SdvRole> columns = List.of(SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD, SdvRole.RESTRICTED_STEWARD,
                SdvRole.BUSINESS_ADMIN, SdvRole.IT_ADMIN, SdvRole.PLATFORM_OPERATIONS_ADMIN);
        assertEquals(EnumSet.allOf(SdvRole.class), EnumSet.copyOf(columns), "every role has a column");
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Object[]> row : rows.entrySet()) {
            Capability capability = (Capability) row.getValue()[0];
            String cells = (String) row.getValue()[1];
            for (int i = 0; i < columns.size(); i++) {
                boolean expected = cells.charAt(i) == '1';
                boolean actual = RoleCapabilities.has(EnumSet.of(columns.get(i)), capability);
                if (expected != actual) wrong.add(row.getKey() + " / " + columns.get(i) + ": expected " + expected);
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
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
                        Capability.CHANGE_SENSITIVITY_LEVEL,
                        Capability.QUARANTINE_DOCUMENTS),
                RoleCapabilities.capabilities(EnumSet.of(SdvRole.DOCUMENT_STEWARD)));

        assertEquals(
                Set.of(
                        Capability.CONFIGURE_METADATA_SEARCH,
                        Capability.CONFIGURE_RETENTION,
                        Capability.CONFIGURE_BUSINESS_LIMITS,
                        Capability.CONFIGURE_ROLE_PERMISSIONS,
                        Capability.APPROVE_SENSITIVE_ACCESS,
                        Capability.MANAGE_LEGAL_HOLD,
                        Capability.VIEW_INTAKE_REPORTS,
                        Capability.GENERATE_AUDIT_REPORT,
                        Capability.VIEW_RESTRICTIONS),
                RoleCapabilities.capabilities(EnumSet.of(SdvRole.BUSINESS_ADMIN)));

        assertEquals(
                Set.of(
                        Capability.CONFIGURE_INGESTION_SOURCES,
                        Capability.CONFIGURE_TENANT_AUTH,
                        Capability.CONFIGURE_STORAGE_KEY_PROVIDER,
                        Capability.VIEW_TECHNICAL_FAILURES,
                        Capability.GENERATE_AUDIT_REPORT),
                RoleCapabilities.capabilities(EnumSet.of(SdvRole.IT_ADMIN)));

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
                        Capability.MANAGE_LEGAL_HOLD,
                        Capability.VIEW_INTAKE_REPORTS,
                        Capability.GENERATE_AUDIT_REPORT,
                        Capability.VIEW_RESTRICTIONS),
                RoleCapabilities.capabilities(EnumSet.of(
                        SdvRole.BUSINESS_USER,
                        SdvRole.BUSINESS_ADMIN)));
    }
}
