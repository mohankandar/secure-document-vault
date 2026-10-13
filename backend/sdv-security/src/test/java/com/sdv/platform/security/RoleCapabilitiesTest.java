package com.sdv.platform.security;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class RoleCapabilitiesTest {
    @Test
    void businessAdminDoesNotImplicitlyReceiveDocumentAccess() {
        var roles = EnumSet.of(SdvRole.BUSINESS_ADMIN);
        assertTrue(RoleCapabilities.has(roles, Capability.CONFIGURE_METADATA_SEARCH));
        assertFalse(RoleCapabilities.has(roles, Capability.VIEW_PDF));
        assertFalse(RoleCapabilities.has(roles, Capability.DOWNLOAD_CURRENT_PDF));
    }

    @Test
    void tenantItAdminDoesNotImplicitlyReceiveDocumentAccess() {
        var roles = EnumSet.of(SdvRole.TENANT_IT_ADMIN);
        assertTrue(RoleCapabilities.has(roles, Capability.CONFIGURE_INGESTION_SOURCES));
        assertFalse(RoleCapabilities.has(roles, Capability.VIEW_PDF));
    }

    @Test
    void multipleRolesComposeCapabilities() {
        var roles = EnumSet.of(SdvRole.BUSINESS_ADMIN, SdvRole.TENANT_IT_ADMIN);
        var capabilities = RoleCapabilities.capabilities(roles);
        assertTrue(capabilities.contains(Capability.CONFIGURE_METADATA_SEARCH));
        assertTrue(capabilities.contains(Capability.CONFIGURE_INGESTION_SOURCES));
        assertFalse(capabilities.contains(Capability.VIEW_PDF));
    }
}
