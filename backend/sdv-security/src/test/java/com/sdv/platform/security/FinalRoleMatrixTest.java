package com.sdv.platform.security;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FinalRoleMatrixTest {
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
                        Capability.ADD_DOCUMENT_COMMENT),
                RoleCapabilities.capabilities(EnumSet.of(SdvRole.DOCUMENT_STEWARD)));

        assertEquals(
                Set.of(
                        Capability.CONFIGURE_METADATA_SEARCH,
                        Capability.CONFIGURE_RETENTION,
                        Capability.CONFIGURE_BUSINESS_LIMITS,
                        Capability.CONFIGURE_ROLE_PERMISSIONS),
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
                        Capability.PLATFORM_CONFIGURATION,
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
                        Capability.CONFIGURE_ROLE_PERMISSIONS),
                RoleCapabilities.capabilities(EnumSet.of(
                        SdvRole.BUSINESS_USER,
                        SdvRole.BUSINESS_ADMIN)));
    }
}
