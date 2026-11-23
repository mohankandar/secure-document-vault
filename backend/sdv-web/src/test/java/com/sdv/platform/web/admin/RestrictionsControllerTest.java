package com.sdv.platform.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.application.restrictions.RestrictionsService;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.web.foundation.FoundationExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 0055: only the Business Admin sees the Restrictions page; every other role is refused and audited. */
class RestrictionsControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final RestrictionsService service = mock(RestrictionsService.class);
    private final AuthorizationService authorization =
            new AuthorizationService(audit, new CapabilityPolicyService(mock(RoleCapabilityPolicyPort.class)));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RestrictionsController(new SecurityContextFacade(), authorization, service))
            .setControllerAdvice(new FoundationExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
            .build();

    private static void signIn(SdvRole role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SdvPrincipal(TENANT, "person-" + role.name().toLowerCase(), Set.of(role), "Pat Person", null), null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void onlyTheBusinessAdminSeesTheRestrictions() throws Exception {
        List<String> paths = List.of("/api/v1/admin/restrictions", "/api/v1/admin/restrictions/records", "/api/v1/admin/restrictions/level-changes");
        for (SdvRole role : EnumSet.complementOf(EnumSet.of(SdvRole.BUSINESS_ADMIN))) {
            for (String path : paths) {
                signIn(role);
                mvc.perform(get(path)).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("SDV-RST-001"))
                        .andExpect(jsonPath("$.message").value("Only a Business Admin can see the restrictions."));
            }
        }
        verifyNoInteractions(service);
        ArgumentCaptor<AuditEvent> denied = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(paths.size() * (SdvRole.values().length - 1))).append(denied.capture());
        assertTrue(denied.getAllValues().stream().allMatch(e -> e.action().equals("AUTHORIZATION_DENIED")
                && e.outcome() == AuditOutcome.DENIED && e.targetId().equals("VIEW_RESTRICTIONS")));
    }

    @Test
    void theBusinessAdminGetsTheOverviewAndThePages() throws Exception {
        signIn(SdvRole.BUSINESS_ADMIN);
        when(service.overview(TENANT)).thenReturn(new RestrictionsService.Overview("2026-11-23T10:00:00Z", List.of(), 3, 1, 0,
                new RestrictionsService.RecordPage(List.of(), null), 0, new RestrictionsService.ChangePage(List.of(), null),
                new RestrictionsService.Lowerings(0, null), new RestrictionsService.LegalHolds(2, 2), new RestrictionsService.QuarantineCounts(0, 0, 0)));
        mvc.perform(get("/api/v1/admin/restrictions")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.legalHolds.holds").value(2));
        when(service.records(TENANT, "abc")).thenReturn(new RestrictionsService.RecordPage(List.of(), null));
        mvc.perform(get("/api/v1/admin/restrictions/records").param("after", "abc")).andExpect(status().isOk());
        when(service.changes(eq(TENANT), isNull())).thenReturn(new RestrictionsService.ChangePage(List.of(), null));
        mvc.perform(get("/api/v1/admin/restrictions/level-changes")).andExpect(status().isOk());
    }
}
