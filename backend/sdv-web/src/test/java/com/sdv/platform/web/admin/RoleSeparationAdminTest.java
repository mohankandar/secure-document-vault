package com.sdv.platform.web.admin;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.application.port.RoleGovernancePort;
import com.sdv.platform.application.roles.RoleConflictService;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.web.foundation.FoundationExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 0025, rule 2 through the real Roles & permissions endpoint and error handler: forbidden overrides are refused
 * with a code and a plain message, nothing is stored for any role in the request, and the refusal is audited.
 * Also who may read the role-conflict and role-notice lists.
 */
class RoleSeparationAdminTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final RoleCapabilityPolicyPort overrides = mock(RoleCapabilityPolicyPort.class);
    private final CapabilityPolicyService policy = new CapabilityPolicyService(overrides);
    private final AuthorizationService authorization = new AuthorizationService(audit, policy);
    private final RoleGovernancePort governance = mock(RoleGovernancePort.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(overrides.findOverrides(any(), anyString())).thenReturn(Map.of());
        mvc = MockMvcBuilders.standaloneSetup(
                        new RoleCapabilityAdminController(new SecurityContextFacade(), authorization, policy, audit))
                .setControllerAdvice(new FoundationExceptionHandler()).build();
        signIn(SdvRole.BUSINESS_ADMIN);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(SdvRole role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SdvPrincipal(TENANT, "person-" + role.name().toLowerCase(), Set.of(role)), null, List.of()));
    }

    private ResultActions save(String body) throws Exception {
        return mvc.perform(put("/api/v1/admin/role-capabilities").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void eachForbiddenOverrideIsRefusedWithItsCodeAndNothingIsStored() throws Exception {
        String[][] forbidden = {
                {"BUSINESS_ADMIN", "VIEW_CONFIDENTIAL"}, {"BUSINESS_ADMIN", "VIEW_HIGHLY_CONFIDENTIAL"},
                {"BUSINESS_ADMIN", "REQUEST_SENSITIVE_ACCESS"},
                {"IT_ADMIN", "VIEW_CONFIDENTIAL"}, {"IT_ADMIN", "VIEW_HIGHLY_CONFIDENTIAL"},
                {"IT_ADMIN", "REQUEST_SENSITIVE_ACCESS"},
                {"PLATFORM_OPERATIONS_ADMIN", "VIEW_CONFIDENTIAL"}, {"PLATFORM_OPERATIONS_ADMIN", "VIEW_HIGHLY_CONFIDENTIAL"},
                {"PLATFORM_OPERATIONS_ADMIN", "REQUEST_SENSITIVE_ACCESS"},
                {"DOCUMENT_STEWARD", "APPROVE_SENSITIVE_ACCESS"}, {"RESTRICTED_STEWARD", "APPROVE_SENSITIVE_ACCESS"}};
        for (String[] pair : forbidden) {
            // A valid change for Business User comes first in the same request: it must not be stored either.
            save("{\"roles\":{\"BUSINESS_USER\":{\"PRINT_DOCUMENT\":true},\"" + pair[0] + "\":{\"" + pair[1] + "\":true}}}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SDV-ROL-001"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith(
                            pair[0] + " can never be given " + pair[1] + ".")));
        }
        verify(overrides, never()).replaceOverrides(any(), anyString(), anyMap(), anyString());
        verify(audit, times(forbidden.length)).append(argThat(e -> e.action().equals("ROLE_CAPABILITIES_UPDATE_REFUSED")
                && e.outcome() == AuditOutcome.DENIED && e.safeDetailsJson().contains("\"code\":\"SDV-ROL-001\"")));
        verify(audit, never()).append(argThat(e -> e.action().equals("ROLE_CAPABILITIES_UPDATED")));
    }

    @Test
    void theRestrictedStewardIsFixedAndOtherCapabilitiesAreRefused() throws Exception {
        save("{\"roles\":{\"RESTRICTED_STEWARD\":{\"PRINT_DOCUMENT\":false}}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-ROL-002"));
        save("{\"roles\":{\"DOCUMENT_STEWARD\":{\"VIEW_HIGHLY_CONFIDENTIAL\":true}}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-ROL-003"));
        verify(overrides, never()).replaceOverrides(any(), anyString(), anyMap(), anyString());
    }

    @Test
    void anAllowedChangeIsStillSaved() throws Exception {
        save("{\"roles\":{\"BUSINESS_USER\":{\"PRINT_DOCUMENT\":true},\"DOCUMENT_STEWARD\":{\"PRINT_DOCUMENT\":false}}}")
                .andExpect(status().isOk());
        verify(overrides).replaceOverrides(eq(TENANT), eq("BUSINESS_USER"), anyMap(), anyString());
        verify(overrides).replaceOverrides(eq(TENANT), eq("DOCUMENT_STEWARD"), anyMap(), anyString());
        verify(audit).append(argThat(e -> e.action().equals("ROLE_CAPABILITIES_UPDATED")));
    }

    @Test
    void onlyTheItAdminReadsRoleConflictsAndOnlyTheBusinessAdminReadsGrantHistory() {
        when(governance.findRoleConflicts(eq(TENANT), anyInt())).thenReturn(List.of());
        var controller = new RoleGovernanceAdminController(new SecurityContextFacade(), authorization,
                new RoleConflictService(governance, audit), (entity, from, to) -> List.of());

        signIn(SdvRole.IT_ADMIN);
        assertEquals(List.of(), controller.roleConflicts());
        assertThrows(AccessDeniedException.class, controller::grantHistory);

        signIn(SdvRole.BUSINESS_ADMIN);
        assertEquals(List.of(), controller.grantHistory());
        assertThrows(AccessDeniedException.class, controller::roleConflicts);

        for (SdvRole other : List.of(SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD, SdvRole.RESTRICTED_STEWARD,
                SdvRole.PLATFORM_OPERATIONS_ADMIN)) {
            signIn(other);
            assertThrows(AccessDeniedException.class, controller::roleConflicts, other.name());
            assertThrows(AccessDeniedException.class, controller::grantHistory, other.name());
        }
    }
}
