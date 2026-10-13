package com.sdv.platform.application.port;

import java.util.Map;
import java.util.UUID;

public interface RoleCapabilityPolicyPort {
    Map<String, Boolean> findOverrides(UUID tenantId, String roleName);
    void replaceOverrides(UUID tenantId, String roleName, Map<String, Boolean> capabilityStates, String updatedBy);
}
