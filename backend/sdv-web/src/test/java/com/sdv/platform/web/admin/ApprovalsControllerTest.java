package com.sdv.platform.web.admin;

import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.approvals.AccessRequestService;
import com.sdv.platform.application.approvals.AccessRequestStatus;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.application.sensitivity.SensitivityLevelService;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.web.business.AccessRequestController;
import com.sdv.platform.web.business.SensitivityLevelController;
import com.sdv.platform.web.foundation.FoundationExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 0028: the new endpoints through the real permission check (role matrix 3.2 and 3.4). Each role without the
 * capability is refused with its code before any service is reached, and the refusal is audited as
 * AUTHORIZATION_DENIED. Codes from the services pass through with their status.
 */
class ApprovalsControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOCUMENT = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID REQUEST = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final ObjectMapper JSON = com.sdv.platform.application.json.VeroJson.mapper();

    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final AccessRequestService requests = mock(AccessRequestService.class);
    private final SensitivityLevelService levels = mock(SensitivityLevelService.class);
    private final AuthorizationService authorization =
            new AuthorizationService(audit, new CapabilityPolicyService(mock(RoleCapabilityPolicyPort.class)));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new ApprovalsController(new SecurityContextFacade(), authorization, requests),
                    new AccessRequestController(new SecurityContextFacade(), authorization, requests),
                    new SensitivityLevelController(new SecurityContextFacade(), authorization, levels))
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

    private static List<RequestBuilder> approvals() {
        return List.of(get("/api/v1/admin/access-requests"), get("/api/v1/admin/access-requests/decided"),
                post("/api/v1/admin/access-requests/" + REQUEST + "/approve"),
                post("/api/v1/admin/access-requests/" + REQUEST + "/decline"),
                post("/api/v1/admin/access-requests/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"requestIds\":[\"" + REQUEST + "\"]}"));
    }

    private static List<RequestBuilder> levelChanges() {
        String body = "{\"level\":\"CONFIDENTIAL\",\"reason\":\"Salary details\"}";
        return List.of(
                post("/api/v1/business/documents/" + DOCUMENT + "/sensitivity/raise").contentType(MediaType.APPLICATION_JSON).content(body),
                post("/api/v1/business/documents/" + DOCUMENT + "/sensitivity/lower-request").contentType(MediaType.APPLICATION_JSON).content(body),
                post("/api/v1/business/records/abc/sensitivity/raise").contentType(MediaType.APPLICATION_JSON).content(body),
                post("/api/v1/business/records/abc/sensitivity/lower-request").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void refused(SdvRole role, RequestBuilder request, String code) throws Exception {
        signIn(role);
        mvc.perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void onlyTheBusinessAdminSeesAndDecidesRequests() throws Exception {
        for (SdvRole role : EnumSet.complementOf(EnumSet.of(SdvRole.BUSINESS_ADMIN))) {
            for (RequestBuilder request : approvals()) refused(role, request, "SDV-APR-005");
        }
        verifyNoInteractions(requests);
        assertDenied("APPROVE_SENSITIVE_ACCESS", approvals().size() * (SdvRole.values().length - 1));
    }

    @Test
    void onlyStewardsChangeLevels() throws Exception {
        Set<SdvRole> stewards = EnumSet.of(SdvRole.DOCUMENT_STEWARD, SdvRole.RESTRICTED_STEWARD);
        for (SdvRole role : EnumSet.complementOf(EnumSet.copyOf(stewards))) {
            for (RequestBuilder request : levelChanges()) refused(role, request, "SDV-SEN-010");
        }
        verifyNoInteractions(levels);
        assertDenied("CHANGE_SENSITIVITY_LEVEL", 4 * (SdvRole.values().length - 2));
    }

    @Test
    void onlyStewardsAndBusinessAdminsReadTheLevelHistory() throws Exception {
        // Task 0029 (0.4): the reasons are in the history, so IT and Operations admins and Business users are refused.
        Set<SdvRole> readers = EnumSet.of(SdvRole.DOCUMENT_STEWARD, SdvRole.RESTRICTED_STEWARD, SdvRole.BUSINESS_ADMIN);
        List<RequestBuilder> reads = List.of(get("/api/v1/business/documents/" + DOCUMENT + "/sensitivity/history"),
                get("/api/v1/business/records/abc/sensitivity/history"));
        for (SdvRole role : EnumSet.complementOf(EnumSet.copyOf(readers))) {
            for (RequestBuilder request : reads) refused(role, request, "SDV-SEN-018");
        }
        verifyNoInteractions(levels);
        assertDenied("CHANGE_SENSITIVITY_LEVEL", 2 * (SdvRole.values().length - 3));

        when(levels.documentHistory(any(), any(), any(), anyBoolean(), any(), any())).thenReturn(List.of());
        when(levels.recordHistory(any(), any())).thenReturn(List.of());
        for (SdvRole role : readers) {
            signIn(role);
            for (RequestBuilder request : reads) mvc.perform(request).andExpect(status().isOk());
        }
        // Only the Business Admin reads a document's history without having to open the document.
        verify(levels).documentHistory(eq(TENANT), eq("person-business_admin"), any(), eq(true), eq(DOCUMENT.toString()), any());
        verify(levels).documentHistory(eq(TENANT), eq("person-document_steward"), any(), eq(false), eq(DOCUMENT.toString()), any());
        verify(levels).documentHistory(eq(TENANT), eq("person-restricted_steward"), any(), eq(false), eq(DOCUMENT.toString()), any());
    }

    @Test
    void onlyTheRestrictedStewardAsksToPrintOrDownloadAndOnlyStewardsListTheirOwn() throws Exception {
        for (SdvRole role : EnumSet.complementOf(EnumSet.of(SdvRole.RESTRICTED_STEWARD))) {
            refused(role, post("/api/v1/business/documents/" + DOCUMENT + "/access-requests")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"PRINT\",\"reason\":\"Court\"}"), "SDV-APR-001");
        }
        for (SdvRole role : EnumSet.complementOf(EnumSet.of(SdvRole.DOCUMENT_STEWARD, SdvRole.RESTRICTED_STEWARD))) {
            refused(role, get("/api/v1/business/access-requests"), "SDV-APR-015");
        }
        verifyNoInteractions(requests);
    }

    @Test
    void allowedRolesReachTheServicesAndCodesPassThrough() throws Exception {
        signIn(SdvRole.BUSINESS_ADMIN);
        when(requests.decide(eq(TENANT), eq("person-business_admin"), eq("Pat Person"), eq(REQUEST.toString()),
                eq(AccessRequestService.Decision.APPROVE), any()))
                .thenThrow(new CodedRefusal.Forbidden(SdvErrorCode.APR_006, "You can't approve or decline your own request."));
        mvc.perform(post("/api/v1/admin/access-requests/" + REQUEST + "/approve")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SDV-APR-006"));
        when(requests.decide(any(), any(), any(), eq(REQUEST.toString()), eq(AccessRequestService.Decision.DECLINE), any()))
                .thenReturn(new AccessRequestService.DecisionView(REQUEST, AccessRequestStatus.DECLINED, null));
        mvc.perform(post("/api/v1/admin/access-requests/" + REQUEST + "/decline")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));
        when(requests.decideMany(any(), any(), any(), eq("APPROVE"), eq(List.of(REQUEST.toString())), any()))
                .thenReturn(new AccessRequestService.BulkResult(List.of(new AccessRequestService.BulkItem(REQUEST.toString(),
                        "REFUSED", "SDV-APR-009", "A request for a whole record is decided on its own, not with others.")), 0, 1));
        mvc.perform(post("/api/v1/admin/access-requests/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("decision", "APPROVE", "requestIds", List.of(REQUEST.toString())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].code").value("SDV-APR-009"))
                .andExpect(jsonPath("$.notDecided").value(1));
        when(requests.pending(TENANT, "PRINT", "Rae", null)).thenReturn(new AccessRequestService.PendingList(List.of(), 0, 7));
        mvc.perform(get("/api/v1/admin/access-requests").param("kind", "PRINT").param("requester", "Rae"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestCount").value(0))
                .andExpect(jsonPath("$.pendingExpiryDays").value(7));
        when(requests.decidedRecently(TENANT)).thenReturn(List.of(new AccessRequestService.DecidedView(REQUEST,
                com.sdv.platform.application.approvals.AccessRequestKind.PRINT, com.sdv.platform.application.approvals.AccessRequestKind.Scope.DOCUMENT,
                null, "300000000001", "Contract number", AccessRequestService.Decision.APPROVE, "Bea Admin", java.time.Instant.parse("2026-11-20T10:00:00Z"))));
        mvc.perform(get("/api/v1/admin/access-requests/decided")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].decidedBy").value("Bea Admin")).andExpect(jsonPath("$[0].decision").value("APPROVE"))
                .andExpect(jsonPath("$[0].reason").doesNotExist());

        signIn(SdvRole.DOCUMENT_STEWARD);
        when(levels.raiseDocument(eq(TENANT), eq("person-document_steward"), eq("Pat Person"), any(), eq(DOCUMENT.toString()),
                eq("CONFIDENTIAL"), eq("Salary details"), any()))
                .thenThrow(new CodedRefusal.Conflict(SdvErrorCode.SEN_013, "A raise must go higher."));
        mvc.perform(levelChanges().getFirst()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SDV-SEN-013"));
        when(levels.requestLowerDocument(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SensitivityLevelService.LevelChange(SensitivityLevelService.LevelChange.Outcome.REQUESTED,
                        com.sdv.platform.domain.document.SensitivityLevel.CONFIDENTIAL, true, REQUEST, null));
        mvc.perform(levelChanges().get(1)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("REQUESTED")).andExpect(jsonPath("$.requestId").value(REQUEST.toString()));
        when(requests.mine(eq(TENANT), eq("person-document_steward"), any())).thenReturn(List.of());
        mvc.perform(get("/api/v1/business/access-requests")).andExpect(status().isOk());

        signIn(SdvRole.RESTRICTED_STEWARD);
        when(requests.requestPrintOrDownload(eq(TENANT), eq("person-restricted_steward"), eq("Pat Person"), any(),
                eq(DOCUMENT.toString()), eq("PRINT"), eq("Court"), any()))
                .thenThrow(new CodedRefusal.Conflict(SdvErrorCode.APR_003, "No approval needed."));
        mvc.perform(post("/api/v1/business/documents/" + DOCUMENT + "/access-requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PRINT\",\"reason\":\"Court\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SDV-APR-003"));
    }

    private void assertDenied(String capability, int count) {
        ArgumentCaptor<AuditEvent> denied = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(count)).append(denied.capture());
        assertTrue(denied.getAllValues().stream().allMatch(e -> e.action().equals("AUTHORIZATION_DENIED")
                && e.outcome() == AuditOutcome.DENIED && e.targetId().equals(capability)));
    }
}
