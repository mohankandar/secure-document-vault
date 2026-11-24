package com.sdv.platform.web.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionCapabilitiesControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final CapabilityPolicyService capabilities = mock(CapabilityPolicyService.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final SessionCapabilitiesController controller =
            new SessionCapabilitiesController(new SecurityContextFacade(), capabilities, tenants);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsNameAndEmailWhenTheTokenHasThem() throws Exception {
        signIn(new SdvPrincipal(TENANT, "okta|00u1", Set.of(SdvRole.BUSINESS_USER), "Maya Okafor", "maya.okafor@example.com"));

        var response = controller.currentSession();

        assertEquals("Maya Okafor", response.displayName());
        assertEquals("maya.okafor@example.com", response.email());
        assertEquals("okta|00u1", response.subject());
        assertEquals(List.of("BUSINESS_USER"), response.roles());
        assertEquals(List.of("SEARCH_RECORDS"), response.capabilities());

        JsonNode json = new ObjectMapper().valueToTree(response);
        assertEquals("Maya Okafor", json.get("displayName").asString());
        assertEquals("maya.okafor@example.com", json.get("email").asString());
    }

    @Test
    void leavesNameAndEmailEmptyWhenTheTokenHasNone() {
        signIn(new SdvPrincipal(TENANT, "local-business-user", Set.of(SdvRole.BUSINESS_USER)));

        var response = controller.currentSession();

        assertNull(response.displayName());
        assertNull(response.email());
        assertEquals("local-business-user", response.subject());

        JsonNode json = new ObjectMapper().valueToTree(response);
        assertTrue(json.get("displayName").isNull());
        assertTrue(json.get("email").isNull());
    }

    private void signIn(SdvPrincipal principal) {
        when(capabilities.effectiveCapabilities(eq(TENANT), anySet())).thenReturn(EnumSet.of(Capability.SEARCH_RECORDS));
        when(tenants.findById(TENANT)).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_BUSINESS_USER"))));
    }
}
