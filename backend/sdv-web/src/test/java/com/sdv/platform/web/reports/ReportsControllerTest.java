package com.sdv.platform.web.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.application.reports.IntakeReportService;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.web.business.IntakeNoticeController;
import com.sdv.platform.web.foundation.FoundationExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 0032: the Reports endpoints through the real permission check. Every role but the Business Admin is refused
 * with SDV-RPT-001 before the report is read, and each refusal is audited as AUTHORIZATION_DENIED. The intake notices
 * are for the two Steward roles (SDV-INT-001 otherwise). The service's own codes pass through with their status.
 */
class ReportsControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final byte[] CSV = "﻿SHA-256 checksum\r\n".getBytes(StandardCharsets.UTF_8);

    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final IntakeReportService reports = mock(IntakeReportService.class);
    private final AuthorizationService authorization =
            new AuthorizationService(audit, new CapabilityPolicyService(mock(RoleCapabilityPolicyPort.class)));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new ReportsController(new SecurityContextFacade(), authorization, reports),
                    new IntakeNoticeController(new SecurityContextFacade(), authorization, reports))
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

    private static List<RequestBuilder> reportCalls() {
        return List.of(get("/api/v1/admin/reports/intake"),
                get("/api/v1/admin/reports/intake").param("from", "2026-11-01").param("to", "2026-11-20"),
                get("/api/v1/admin/reports/intake/export"));
    }

    @Test
    void onlyTheBusinessAdminSeesTheReports() throws Exception {
        for (SdvRole role : EnumSet.complementOf(EnumSet.of(SdvRole.BUSINESS_ADMIN))) {
            for (RequestBuilder call : reportCalls()) {
                signIn(role);
                mvc.perform(call).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("SDV-RPT-001"))
                        .andExpect(jsonPath("$.message").value("Only a Business Admin can see the reports."));
            }
        }
        verifyNoInteractions(reports);
        ArgumentCaptor<AuditEvent> denied = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(reportCalls().size() * (SdvRole.values().length - 1))).append(denied.capture());
        assertTrue(denied.getAllValues().stream().allMatch(e -> e.action().equals("AUTHORIZATION_DENIED")
                && e.outcome() == AuditOutcome.DENIED && e.targetId().equals("VIEW_INTAKE_REPORTS")));
    }

    @Test
    void theBusinessAdminGetsTheReportAndTheCsv() throws Exception {
        signIn(SdvRole.BUSINESS_ADMIN);
        when(reports.intake(TENANT, "2026-11-20", "2026-11-20", null)).thenReturn(new IntakeReportService.IntakeReport(
                "2026-11-20", "2026-11-20", "2026-11-20", "America/New_York", "INSTALL_DEFAULT", null, "Contract number",
                List.of(), new IntakeReportService.Totals(0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), 0), List.of(), 50));
        mvc.perform(get("/api/v1/admin/reports/intake").param("from", "2026-11-20").param("to", "2026-11-20"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.timeZone").value("America/New_York"))
                .andExpect(jsonPath("$.totals.deliveries").value(0));

        when(reports.export(eq(TENANT), eq("person-business_admin"), any(), isNull(), isNull(), eq("")))
                .thenReturn(new IntakeReportService.Export(CSV, 0, "vero-daily-intake-2026-11-20.csv"));
        mvc.perform(get("/api/v1/admin/reports/intake/export").param("sourceId", ""))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("vero-daily-intake-2026-11-20.csv")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-SDV-Export-Rows", "0"))
                .andExpect(content().bytes(CSV));
    }

    @Test
    void theServicesCodesPassThroughWithTheirStatus() throws Exception {
        signIn(SdvRole.BUSINESS_ADMIN);
        when(reports.intake(any(), eq("bad"), any(), any())).thenThrow(new CodedRefusal.BadRequest(SdvErrorCode.RPT_002, "no"));
        when(reports.intake(any(), isNull(), isNull(), eq("x"))).thenThrow(new CodedRefusal.BadRequest(SdvErrorCode.RPT_003, "no"));
        when(reports.intake(any(), eq("2026-11-20"), isNull(), isNull())).thenThrow(new CodedRefusal.Conflict(SdvErrorCode.RPT_004, "no"));
        when(reports.export(any(), any(), any(), eq("2026-01-01"), any(), any())).thenThrow(new CodedRefusal.BadRequest(SdvErrorCode.RPT_005, "no"));
        mvc.perform(get("/api/v1/admin/reports/intake").param("from", "bad").param("to", "2026-11-20"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-RPT-002"));
        mvc.perform(get("/api/v1/admin/reports/intake").param("sourceId", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-RPT-003"));
        mvc.perform(get("/api/v1/admin/reports/intake").param("from", "2026-11-20"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SDV-RPT-004"));
        mvc.perform(get("/api/v1/admin/reports/intake/export").param("from", "2026-01-01").param("to", "2026-01-31"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-RPT-005"));
    }

    @Test
    void intakeNoticesAreForTheTwoStewardRoles() throws Exception {
        Set<SdvRole> stewards = EnumSet.of(SdvRole.DOCUMENT_STEWARD, SdvRole.RESTRICTED_STEWARD);
        for (SdvRole role : EnumSet.complementOf(EnumSet.copyOf(stewards))) {
            signIn(role);
            mvc.perform(get("/api/v1/business/intake-notices")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SDV-INT-001"));
        }
        verifyNoInteractions(reports);
        when(reports.uncorrectedCopyNotices(TENANT)).thenReturn(List.of());
        for (SdvRole role : stewards) {
            signIn(role);
            mvc.perform(get("/api/v1/business/intake-notices")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        }
        verify(reports, times(2)).uncorrectedCopyNotices(TENANT);
    }
}
