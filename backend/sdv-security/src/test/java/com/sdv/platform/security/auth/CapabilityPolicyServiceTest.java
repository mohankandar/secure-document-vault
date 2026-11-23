package com.sdv.platform.security.auth;

import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.SdvRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0025, rule 2: overrides that would break a separation rule are refused, and never take effect. */
class CapabilityPolicyServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final Map<String, Map<String, Boolean>> stored = new HashMap<>();
    private final List<String> writes = new ArrayList<>();
    private final CapabilityPolicyService service = new CapabilityPolicyService(new RoleCapabilityPolicyPort() {
        @Override public Map<String, Boolean> findOverrides(UUID tenantId, String roleName) {
            return stored.getOrDefault(roleName, Map.of());
        }
        @Override public void replaceOverrides(UUID tenantId, String roleName, Map<String, Boolean> states, String by) {
            writes.add(roleName);
            stored.put(roleName, Map.copyOf(states));
        }
    });

    private String refusalCode(SdvRole role, Capability capability) {
        var ex = assertThrows(CodedRefusal.BadRequest.class,
                () -> service.replace(TENANT, role, Map.of(capability.name(), true), "admin"));
        return ex.errorCode().code();
    }

    @Test
    void sensitiveViewingIsNeverGivenToAnAdminRole() {
        for (SdvRole admin : List.of(SdvRole.BUSINESS_ADMIN, SdvRole.IT_ADMIN, SdvRole.PLATFORM_OPERATIONS_ADMIN)) {
            for (Capability c : List.of(Capability.VIEW_CONFIDENTIAL, Capability.VIEW_HIGHLY_CONFIDENTIAL, Capability.REQUEST_SENSITIVE_ACCESS)) {
                assertEquals("SDV-ROL-001", refusalCode(admin, c), admin + " / " + c);
            }
        }
        var ex = assertThrows(CodedRefusal.BadRequest.class, () -> service.replace(TENANT, SdvRole.BUSINESS_ADMIN,
                Map.of(Capability.VIEW_CONFIDENTIAL.name(), true), "admin"));
        assertTrue(ex.getMessage().contains("BUSINESS_ADMIN can never be given VIEW_CONFIDENTIAL"), ex.getMessage());
        assertTrue(writes.isEmpty());
    }

    @Test
    void approvingIsNeverGivenToAStewardOrRestrictedSteward() {
        assertEquals("SDV-ROL-001", refusalCode(SdvRole.DOCUMENT_STEWARD, Capability.APPROVE_SENSITIVE_ACCESS));
        assertEquals("SDV-ROL-001", refusalCode(SdvRole.RESTRICTED_STEWARD, Capability.APPROVE_SENSITIVE_ACCESS));
        assertTrue(writes.isEmpty());
    }

    @Test
    void fixedRolesAndOtherCapabilitiesAreRefusedWithTheirOwnCodes() {
        assertEquals("SDV-ROL-002", refusalCode(SdvRole.RESTRICTED_STEWARD, Capability.PRINT_DOCUMENT));
        assertEquals("SDV-ROL-002", refusalCode(SdvRole.BUSINESS_ADMIN, Capability.PRINT_DOCUMENT));
        assertEquals("SDV-ROL-003", refusalCode(SdvRole.BUSINESS_USER, Capability.VIEW_CONFIDENTIAL));
        assertEquals("SDV-ROL-003", refusalCode(SdvRole.DOCUMENT_STEWARD, Capability.MANAGE_LEGAL_HOLD));
        var ex = assertThrows(CodedRefusal.BadRequest.class, () -> service.replace(TENANT, SdvRole.BUSINESS_USER,
                Map.of("NOT_A_CAPABILITY", false), "admin"));
        assertEquals("SDV-ROL-003", ex.errorCode().code());
        assertTrue(writes.isEmpty());
    }

    @Test
    void switchingOffAForbiddenGrantIsNotAForbiddenGrant() {
        var ex = assertThrows(CodedRefusal.BadRequest.class, () -> service.replace(TENANT, SdvRole.DOCUMENT_STEWARD,
                Map.of(Capability.APPROVE_SENSITIVE_ACCESS.name(), false), "admin"));
        assertEquals("SDV-ROL-003", ex.errorCode().code(), "still not changeable here");
    }

    @Test
    void theConfigurableOverridesStillWork() {
        service.replace(TENANT, SdvRole.BUSINESS_USER, Map.of(Capability.PRINT_DOCUMENT.name(), true), "admin");
        assertTrue(service.effectiveCapabilities(TENANT, SdvRole.BUSINESS_USER).contains(Capability.PRINT_DOCUMENT));
        assertEquals(List.of("BUSINESS_USER"), writes);
    }

    @Test
    void aStoredForbiddenRowNeverTakesEffect() {
        stored.put("DOCUMENT_STEWARD", Map.of(Capability.APPROVE_SENSITIVE_ACCESS.name(), true));
        stored.put("BUSINESS_USER", Map.of(Capability.VIEW_HIGHLY_CONFIDENTIAL.name(), true));
        stored.put("BUSINESS_ADMIN", Map.of(Capability.VIEW_CONFIDENTIAL.name(), true));
        assertFalse(service.effectiveCapabilities(TENANT, SdvRole.DOCUMENT_STEWARD).contains(Capability.APPROVE_SENSITIVE_ACCESS));
        assertFalse(service.effectiveCapabilities(TENANT, SdvRole.BUSINESS_USER).contains(Capability.VIEW_HIGHLY_CONFIDENTIAL));
        assertFalse(service.effectiveCapabilities(TENANT, SdvRole.BUSINESS_ADMIN).contains(Capability.VIEW_CONFIDENTIAL));
    }

    @Test
    void aRestrictedStewardsPermissionsIgnoreStoredOverrides() {
        stored.put("RESTRICTED_STEWARD", Map.of(Capability.PRINT_DOCUMENT.name(), false));
        assertTrue(service.effectiveCapabilities(TENANT, SdvRole.RESTRICTED_STEWARD).contains(Capability.PRINT_DOCUMENT));
    }
}
