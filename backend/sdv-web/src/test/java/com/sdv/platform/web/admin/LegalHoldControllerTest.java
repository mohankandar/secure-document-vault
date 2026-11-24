package com.sdv.platform.web.admin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.legalhold.LegalHoldService;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.web.foundation.FoundationExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 0027: the legal-hold endpoints, through the real permission check. Only the Business Admin gets past it; every
 * other role is refused with SDV-HLD-001 before the service is reached, and the refusal is audited. The JSON a Business
 * Admin gets carries no title, file name or content.
 */
class LegalHoldControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOCUMENT = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID HOLD = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final ObjectMapper JSON = com.sdv.platform.application.json.VeroJson.mapper();

    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final LegalHoldService service = mock(LegalHoldService.class);
    private final com.sdv.platform.application.approvals.AccessRequestService requests = mock(com.sdv.platform.application.approvals.AccessRequestService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LegalHoldController(new SecurityContextFacade(),
                    new AuthorizationService(audit, new CapabilityPolicyService(mock(RoleCapabilityPolicyPort.class))), service, requests))
            .setControllerAdvice(new FoundationExceptionHandler())
            .setMessageConverters(new JacksonJsonHttpMessageConverter((tools.jackson.databind.json.JsonMapper) JSON))
            .build();

    private static void signIn(SdvRole role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SdvPrincipal(TENANT, "person-" + role.name().toLowerCase(), Set.of(role), "Pat Person", null), null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static LegalHoldService.LegalHoldView view() {
        return new LegalHoldService.LegalHoldView(HOLD, DOCUMENT, "300000000001", "Contract number",
                SensitivityLevel.HIGHLY_CONFIDENTIAL, NOW.minusSeconds(86400), 9, "Litigation", "person-business_admin", "Pat Person",
                NOW, null, null, null);
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        for (Iterator<String> it = node.propertyNames().iterator(); it.hasNext(); ) keys.add(it.next());
        return keys;
    }

    @Test
    void everyRoleButTheBusinessAdminIsRefusedWithACodeAndAudited() throws Exception {
        for (SdvRole role : EnumSet.complementOf(EnumSet.of(SdvRole.BUSINESS_ADMIN))) {
            signIn(role);
            for (var request : List.of(get("/api/v1/admin/legal-holds"),
                    get("/api/v1/admin/legal-holds/documents").param("identifier", "300000000001"),
                    post("/api/v1/admin/legal-holds").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"documentId\":\"" + DOCUMENT + "\",\"reason\":\"Litigation\"}"),
                    get("/api/v1/admin/legal-holds/release-requests"),
                    post("/api/v1/admin/legal-holds/" + HOLD + "/release"))) {
                mvc.perform(request).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("SDV-HLD-001"))
                        .andExpect(jsonPath("$.message").value("Only a Business Admin can manage legal holds."));
            }
        }
        verifyNoInteractions(service, requests);
        ArgumentCaptor<AuditEvent> denied = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(5 * (SdvRole.values().length - 1))).append(denied.capture());
        assertTrue(denied.getAllValues().stream().allMatch(e -> e.action().equals("AUTHORIZATION_DENIED")
                && e.outcome() == AuditOutcome.DENIED && e.targetId().equals("MANAGE_LEGAL_HOLD")));
    }

    @Test
    void theBusinessAdminPlacesFindsListsAndReleasesAndSeesNoFileNameOrTitle() throws Exception {
        signIn(SdvRole.BUSINESS_ADMIN);
        when(service.place(eq(TENANT), eq("person-business_admin"), eq("Pat Person"), eq(DOCUMENT.toString()), eq("Litigation"), any()))
                .thenReturn(view());
        when(service.active(TENANT)).thenReturn(List.of(view()));
        when(service.find(TENANT, "300000000001")).thenReturn(List.of(new LegalHoldService.HoldDocumentView(DOCUMENT,
                "300000000001", "Contract number", SensitivityLevel.STANDARD, NOW, 3, false, null)));

        MvcResult placed = mvc.perform(post("/api/v1/admin/legal-holds").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"" + DOCUMENT + "\",\"reason\":\"Litigation\"}"))
                .andExpect(status().isCreated()).andReturn();
        assertEquals("no-store, must-revalidate, private", placed.getResponse().getHeader("Cache-Control"));
        assertEquals(new TreeSet<>(List.of("holdId", "documentId", "primaryIdentifier", "primaryIdentifierLabel",
                "sensitivityLevel", "receivedAt", "pageCount", "reason", "placedBy", "placedByName", "placedAt", "releasedBy",
                "releasedByName", "releasedAt")),
                keys(JSON.readTree(placed.getResponse().getContentAsString())));

        MvcResult found = mvc.perform(get("/api/v1/admin/legal-holds/documents").param("identifier", "300000000001"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(new TreeSet<>(List.of("documentId", "primaryIdentifier", "primaryIdentifierLabel", "sensitivityLevel",
                "receivedAt", "pageCount", "onHold", "activeHoldId")),
                keys(JSON.readTree(found.getResponse().getContentAsString()).get(0)));

        mvc.perform(get("/api/v1/admin/legal-holds")).andExpect(status().isOk()).andExpect(jsonPath("$[0].holdId").value(HOLD.toString()));
        // Task 0071: a release is a request; nothing is released here.
        mvc.perform(post("/api/v1/admin/legal-holds/" + HOLD + "/release").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Case closed\"}"))
                .andExpect(status().isAccepted());
        verify(requests).requestHoldRelease(eq(TENANT), eq("person-business_admin"), eq("Pat Person"), eq(HOLD.toString()), eq("Case closed"), any());
        verify(service, never()).release(any(), any(), any(), any(), any());
        verify(audit, never()).append(any());
    }

    @Test
    void theServicesRefusalsKeepTheirCodesAndStatuses() throws Exception {
        signIn(SdvRole.BUSINESS_ADMIN);
        when(service.place(any(), any(), any(), any(), any(), any()))
                .thenThrow(new com.sdv.platform.domain.error.CodedRefusal.NotFound(
                        com.sdv.platform.domain.error.SdvErrorCode.HLD_004, "No document has this reference."));
        mvc.perform(post("/api/v1/admin/legal-holds").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SDV-HLD-004"));
        when(requests.requestHoldRelease(any(), any(), any(), any(), any(), any()))
                .thenThrow(new com.sdv.platform.domain.error.CodedRefusal.Conflict(
                        com.sdv.platform.domain.error.SdvErrorCode.HLD_008, "This legal hold was already released."));
        mvc.perform(post("/api/v1/admin/legal-holds/x/release"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SDV-HLD-008"));
        when(service.find(any(), any()))
                .thenThrow(new com.sdv.platform.domain.error.CodedRefusal.BadRequest(
                        com.sdv.platform.domain.error.SdvErrorCode.HLD_003, "Enter the record's identifier."));
        mvc.perform(get("/api/v1/admin/legal-holds/documents"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-HLD-003"));
        // An empty body reaches the service as no document and no reason (it refuses those itself).
        verify(service).place(eq(TENANT), any(), any(), isNull(), isNull(), any());
    }
}
