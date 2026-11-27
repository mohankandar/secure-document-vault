package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.TenantId;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 0100 (VSEC-201): the approver is the signed-in person with the approver's permission there, not a list. */
class SignedInApproverTest {
    static final UUID ENTITY = NotificationKit.ENTITY;
    static final TenantId CUSTOMER = TenantId.of(NotificationKit.CUSTOMER);
    final CapabilityPolicyService capabilities;
    SdvPrincipal current;

    SignedInApproverTest() {
        RoleCapabilityPolicyPort policies = mock(RoleCapabilityPolicyPort.class);
        when(policies.findOverrides(any(), any())).thenReturn(Map.of());
        capabilities = new CapabilityPolicyService(policies);
    }

    private SignedInApprover check() {
        return new SignedInApprover(() -> Optional.ofNullable(current), capabilities,
                e -> e.equals(ENTITY) ? Optional.of(NotificationKit.CUSTOMER) : Optional.empty());
    }

    private boolean holds(String userId) {
        return check().holdsRole(CUSTOMER, EntityId.of(ENTITY.toString()), VeroCatalogue.BUSINESS_ADMIN, userId);
    }

    @Test
    void aBusinessAdminSignedInNowHoldsIt() {
        current = new SdvPrincipal(ENTITY, "ba-1", Set.of(SdvRole.BUSINESS_ADMIN));
        assertTrue(holds("ba-1"));
    }

    @Test
    void onTheListButWithoutTheRoleNow() {
        current = new SdvPrincipal(ENTITY, "ba-1", Set.of(SdvRole.BUSINESS_USER));
        assertFalse(holds("ba-1"));
        current = new SdvPrincipal(ENTITY, "ba-1", Set.of(SdvRole.DOCUMENT_STEWARD));
        assertFalse(holds("ba-1"));
    }

    @Test
    void anotherPersonEntityOrCustomerIsNo() {
        current = new SdvPrincipal(ENTITY, "ba-2", Set.of(SdvRole.BUSINESS_ADMIN));
        assertFalse(holds("ba-1"));
        current = new SdvPrincipal(UUID.randomUUID(), "ba-1", Set.of(SdvRole.BUSINESS_ADMIN));
        assertFalse(holds("ba-1"));
        current = new SdvPrincipal(ENTITY, "ba-1", Set.of(SdvRole.BUSINESS_ADMIN));
        assertFalse(check().holdsRole(TenantId.of("another-customer"), EntityId.of(ENTITY.toString()), VeroCatalogue.BUSINESS_ADMIN, "ba-1"));
    }

    @Test
    void noSignInOrAnotherRoleIsNo() {
        current = null;
        assertFalse(holds("ba-1"));
        current = new SdvPrincipal(ENTITY, "ba-1", Set.of(SdvRole.BUSINESS_ADMIN));
        assertFalse(check().holdsRole(CUSTOMER, EntityId.of(ENTITY.toString()), RoleId.of("IT_ADMIN"), "ba-1"));
        assertFalse(check().holdsRole(CUSTOMER, EntityId.of(ENTITY.toString()), VeroCatalogue.BUSINESS_ADMIN, null));
    }
}
