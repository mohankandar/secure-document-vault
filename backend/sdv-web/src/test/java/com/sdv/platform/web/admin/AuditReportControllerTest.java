package com.sdv.platform.web.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.audit.SealedAuditReportService;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.domain.audit.AuditEvent;
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
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
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
 * Task 0050: the sealed audit report endpoints through the real permission check. Only a Business Admin or IT Admin
 * generates, lists, downloads or verifies (there is no auditor role); Business User, both Stewards and Platform
 * Operations are refused with SDV-AUD-010 and the refusal is audited. The files carry reference, fingerprint and rows.
 */
class AuditReportControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String FINGERPRINT = "ab".repeat(32);
    private static final SealedAuditReportService.Report REPORT = new SealedAuditReportService.Report(
            UUID.fromString("3f2a9c1e-0000-4000-8000-000000000001"), "Priya Natarajan", Instant.parse("2026-11-23T09:30:00Z"),
            Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-23T00:00:00Z"), Map.of(), 2, FINGERPRINT);

    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final SealedAuditReportService service = mock(SealedAuditReportService.class);
    private final AuthorizationService authorization =
            new AuthorizationService(audit, new CapabilityPolicyService(mock(RoleCapabilityPolicyPort.class)));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuditReportController(new SecurityContextFacade(), authorization, service))
            .setControllerAdvice(new FoundationExceptionHandler())
            .setMessageConverters(new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(JSON))
            .build();

    private static void signIn(SdvRole role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SdvPrincipal(TENANT, "person-" + role.name().toLowerCase(), Set.of(role), "Pat Person", null), null, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static final String BODY = "{\"from\":\"2026-11-01\",\"to\":\"2026-11-22\"}";

    @Test
    void nobodyButABusinessAdminOrItAdminGetsIn() throws Exception {
        for (SdvRole role : EnumSet.of(SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD, SdvRole.RESTRICTED_STEWARD, SdvRole.PLATFORM_OPERATIONS_ADMIN)) {
            signIn(role);
            mvc.perform(post("/api/v1/audit-reports").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SDV-AUD-010"));
            mvc.perform(get("/api/v1/audit-reports")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SDV-AUD-010"));
            mvc.perform(get("/api/v1/audit-reports/" + REPORT.reportId() + "/csv")).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/audit-reports/" + REPORT.reportId() + "/pdf")).andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/audit-reports/verify").contentType(MediaType.APPLICATION_JSON).content("{\"fingerprint\":\"" + FINGERPRINT + "\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SDV-AUD-010"));
        }
        verifyNoInteractions(service);
        ArgumentCaptor<AuditEvent> refusals = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, atLeast(4)).append(refusals.capture());
        assertTrue(refusals.getAllValues().stream().allMatch(e -> e.action().equals("AUTHORIZATION_DENIED")));
    }

    @Test
    void aBusinessAdminAndAnItAdminGenerateListAndVerify() throws Exception {
        when(service.generate(any(), any())).thenReturn(REPORT);
        when(service.list(any())).thenReturn(List.of(REPORT));
        when(service.verify(any(), eq(FINGERPRINT))).thenReturn(new SealedAuditReportService.Verification(true, FINGERPRINT, REPORT));
        for (SdvRole role : EnumSet.of(SdvRole.BUSINESS_ADMIN, SdvRole.IT_ADMIN)) {
            signIn(role);
            mvc.perform(post("/api/v1/audit-reports").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.fingerprint").value(FINGERPRINT))
                    .andExpect(header().string("Cache-Control", containsString("no-store")));
            mvc.perform(get("/api/v1/audit-reports")).andExpect(status().isOk()).andExpect(jsonPath("$[0].rowCount").value(2));
            mvc.perform(post("/api/v1/audit-reports/verify").contentType(MediaType.APPLICATION_JSON).content("{\"fingerprint\":\"" + FINGERPRINT + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.matches").value(true))
                    .andExpect(jsonPath("$.report.generatedBy").value("Priya Natarajan"));
        }
        ArgumentCaptor<SealedAuditReportService.ReportRequest> request = ArgumentCaptor.forClass(SealedAuditReportService.ReportRequest.class);
        verify(service, times(2)).generate(argThat(a -> a.tenantId().equals(TENANT)), request.capture());
        assertEquals("2026-11-01", request.getValue().from());
        assertEquals("2026-11-22", request.getValue().to());
    }

    @Test
    void theFilesCarryReferenceFingerprintAndRows() throws Exception {
        byte[] csv = "﻿\"Vero sealed audit report\"\r\n".getBytes(StandardCharsets.UTF_8);
        when(service.csv(any(), eq(REPORT.reportId().toString()))).thenReturn(new SealedAuditReportService.Download(csv, "vero-audit-report-2026-11-01-to-2026-11-22-3f2a9c1e.csv", REPORT));
        when(service.pdf(any(), eq(REPORT.reportId().toString()))).thenReturn(new SealedAuditReportService.Download("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), "vero-audit-report-2026-11-01-to-2026-11-22-3f2a9c1e.pdf", REPORT));
        signIn(SdvRole.IT_ADMIN);
        mvc.perform(get("/api/v1/audit-reports/" + REPORT.reportId() + "/csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-SDV-Export-Reference", REPORT.reportId().toString()))
                .andExpect(header().string("X-SDV-Export-Fingerprint", FINGERPRINT))
                .andExpect(header().string("X-SDV-Export-Rows", "2"))
                .andExpect(header().string("Content-Disposition", containsString("vero-audit-report-2026-11-01-to-2026-11-22-3f2a9c1e.csv")))
                .andExpect(content().bytes(csv));
        mvc.perform(get("/api/v1/audit-reports/" + REPORT.reportId() + "/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-SDV-Export-Fingerprint", FINGERPRINT));
    }
}
