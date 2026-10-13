package com.sdv.platform.web.admin.identity;

import com.sdv.platform.security.Capability;
import com.sdv.platform.security.RoleCapabilities;
import com.sdv.platform.security.SdvRole;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EnterpriseIdentityCheckpointBContractTest {
    @Test void tenantItOwnsTenantAuthenticationAndPlatformOpsDoesNot(){
        assertTrue(RoleCapabilities.capabilities(Set.of(SdvRole.TENANT_IT_ADMIN)).contains(Capability.CONFIGURE_TENANT_AUTH));
        assertFalse(RoleCapabilities.capabilities(Set.of(SdvRole.PLATFORM_OPERATIONS_ADMIN)).contains(Capability.CONFIGURE_TENANT_AUTH));
        assertFalse(RoleCapabilities.capabilities(Set.of(SdvRole.BUSINESS_ADMIN)).contains(Capability.CONFIGURE_TENANT_AUTH));
    }
    @Test void checkpointCExposesExplicitActivationAndRollbackMethods(){
        assertTrue(Arrays.stream(EnterpriseIdentityAdminController.class.getDeclaredMethods()).anyMatch(m->m.getName().equals("activateDraft")));
        assertTrue(Arrays.stream(EnterpriseIdentityAdminController.class.getDeclaredMethods()).anyMatch(m->m.getName().equals("rollback")));
    }
}
