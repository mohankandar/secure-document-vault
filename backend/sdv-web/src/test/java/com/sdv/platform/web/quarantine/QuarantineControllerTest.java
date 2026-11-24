package com.sdv.platform.web.quarantine;

import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.application.quarantine.QuarantineService;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.web.admin.QuarantineReleaseController;
import com.sdv.platform.web.foundation.FoundationExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 0048: the quarantine endpoints through the real permission check. Stewards and Restricted Stewards quarantine;
 * only a Restricted Steward quarantines by filter; only a Business Admin decides whole-incident releases, one at a time.
 * Business User, Business Admin and IT Admin never quarantine. Each refusal is audited.
 */
class QuarantineControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ObjectMapper JSON = com.sdv.platform.application.json.VeroJson.mapper();

    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final QuarantineService service = mock(QuarantineService.class);
    private final AuthorizationService authorization =
            new AuthorizationService(audit, new CapabilityPolicyService(mock(RoleCapabilityPolicyPort.class)));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new QuarantineController(new SecurityContextFacade(), authorization, service),
                    new QuarantineReleaseController(new SecurityContextFacade(), authorization, service))
            .setControllerAdvice(new FoundationExceptionHandler())
            .setMessageConverters(new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter((tools.jackson.databind.json.JsonMapper) JSON))
            .build();

    private static void signIn(SdvRole role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SdvPrincipal(TENANT, "person-" + role.name().toLowerCase(), Set.of(role), "Pat Person", null), null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static final String DOCUMENT_BODY = "{\"documentId\":\"" + UUID.randomUUID() + "\",\"name\":\"Leak\",\"reason\":\"Suspected leak\"}";
    private static final String FILTER_BODY = "{\"receivedFrom\":\"2026-11-01\",\"receivedTo\":\"2026-11-30\",\"expectedCount\":3,"
            + "\"name\":\"Leak\",\"reason\":\"Suspected leak\",\"fields\":{\"contractNumber\":\"300000000001\"}}";

    @Test
    void onlyStewardsQuarantineAndReview() throws Exception {
        for (SdvRole role : EnumSet.of(SdvRole.BUSINESS_USER, SdvRole.BUSINESS_ADMIN, SdvRole.IT_ADMIN)) {
            signIn(role);
            mvc.perform(post("/api/v1/quarantine/documents").contentType(MediaType.APPLICATION_JSON).content(DOCUMENT_BODY))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SDV-QUA-002"));
            mvc.perform(get("/api/v1/quarantine/incidents")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SDV-QUA-002"));
            mvc.perform(get("/api/v1/quarantine/incidents/" + UUID.randomUUID() + "/exposure/export"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SDV-QUA-002"));
        }
        verifyNoInteractions(service);
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, atLeastOnce()).append(events.capture());
        assertTrue(events.getAllValues().stream().allMatch(e -> e.action().equals("AUTHORIZATION_DENIED")));

        for (SdvRole role : EnumSet.of(SdvRole.DOCUMENT_STEWARD, SdvRole.RESTRICTED_STEWARD)) {
            signIn(role);
            mvc.perform(post("/api/v1/quarantine/documents").contentType(MediaType.APPLICATION_JSON).content(DOCUMENT_BODY))
                    .andExpect(status().isCreated());
        }
        ArgumentCaptor<QuarantineService.Actor> actors = ArgumentCaptor.forClass(QuarantineService.Actor.class);
        verify(service, times(2)).quarantineDocument(actors.capture(), anyString(), isNull(), eq("Leak"), eq("Suspected leak"));
        assertFalse(actors.getAllValues().get(0).clearance().highlyConfidential(), "a Steward's clearance stops below Highly Confidential");
        assertTrue(actors.getAllValues().get(1).clearance().highlyConfidential());
        assertTrue(actors.getAllValues().stream().allMatch(a -> a.clearance().reviewsQuarantine()));
    }

    @Test
    void onlyARestrictedStewardQuarantinesByFilter() throws Exception {
        for (SdvRole role : EnumSet.of(SdvRole.DOCUMENT_STEWARD, SdvRole.BUSINESS_USER, SdvRole.BUSINESS_ADMIN)) {
            signIn(role);
            mvc.perform(post("/api/v1/quarantine/filter/count").contentType(MediaType.APPLICATION_JSON).content(FILTER_BODY))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SDV-QUA-003"));
            mvc.perform(post("/api/v1/quarantine/filter").contentType(MediaType.APPLICATION_JSON).content(FILTER_BODY))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SDV-QUA-003"));
        }
        verifyNoInteractions(service);
        signIn(SdvRole.RESTRICTED_STEWARD);
        when(service.countMatching(any(), any())).thenReturn(new QuarantineService.MatchCount(3));
        mvc.perform(post("/api/v1/quarantine/filter/count").contentType(MediaType.APPLICATION_JSON).content(FILTER_BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.limit").doesNotExist()); // task 0051: no limit
        mvc.perform(post("/api/v1/quarantine/filter").contentType(MediaType.APPLICATION_JSON).content(FILTER_BODY))
                .andExpect(status().isCreated());
        ArgumentCaptor<QuarantineService.FilterRequest> filter = ArgumentCaptor.forClass(QuarantineService.FilterRequest.class);
        verify(service).quarantineByFilter(any(), filter.capture(), eq(3L), eq("Leak"), eq("Suspected leak"));
        assertEquals("2026-11-01", filter.getValue().receivedFrom());
        assertEquals("300000000001", filter.getValue().fields().get("contractNumber"));
    }

    @Test
    void onlyABusinessAdminDecidesWholeIncidentReleasesAndNeverInBulk() throws Exception {
        UUID request = UUID.randomUUID();
        for (SdvRole role : EnumSet.of(SdvRole.DOCUMENT_STEWARD, SdvRole.RESTRICTED_STEWARD, SdvRole.BUSINESS_USER, SdvRole.IT_ADMIN)) {
            signIn(role);
            mvc.perform(get("/api/v1/admin/quarantine-releases")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SDV-APR-005"));
            mvc.perform(post("/api/v1/admin/quarantine-releases/" + request + "/approve")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SDV-APR-005"));
        }
        verifyNoInteractions(service);
        signIn(SdvRole.BUSINESS_ADMIN);
        mvc.perform(get("/api/v1/admin/quarantine-releases")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/quarantine-releases/" + request + "/approve")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/quarantine-releases/" + request + "/decline")).andExpect(status().isOk());
        verify(service).decide(any(), eq(request.toString()), eq(true));
        verify(service).decide(any(), eq(request.toString()), eq(false));
        // No bulk endpoint for these requests.
        mvc.perform(post("/api/v1/admin/quarantine-releases/decisions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(result -> assertTrue(result.getResponse().getStatus() >= 400, "no bulk decision"));
        verify(service, never()).decide(any(), eq("decisions"), anyBoolean());
    }

    @Test
    void theExposureExportStreamsWithItsReference() throws Exception {
        signIn(SdvRole.DOCUMENT_STEWARD);
        UUID reference = UUID.randomUUID();
        // Task 0052: the file streams; the reference comes first, the fingerprint goes to the audit at the end.
        when(service.openExposureExport(any(), anyString())).thenReturn(new QuarantineService.ExposureExport() {
            @Override public UUID reference() { return reference; }
            @Override public String filename() { return "vero-quarantine-exposure-2026-11-20-abcdef12.csv"; }
            @Override public QuarantineService.Written writeTo(java.io.OutputStream out) throws java.io.IOException {
                out.write("a,b\r\n".getBytes());
                return new QuarantineService.Written(1, 1, "f".repeat(64));
            }
        });
        mvc.perform(get("/api/v1/quarantine/incidents/" + UUID.randomUUID() + "/exposure/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-SDV-Export-Reference", reference.toString()))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("vero-quarantine-exposure-2026-11-20-abcdef12.csv")))
                .andExpect(header().doesNotExist("Content-Length"))
                .andExpect(content().string("a,b\r\n"));
    }
}
