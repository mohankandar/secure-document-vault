package com.sdv.platform.web.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.application.port.SensitivityReportRepository;
import com.sdv.platform.application.reports.IntakeReportService;
import com.sdv.platform.application.reports.SensitivityReportService;
import com.sdv.platform.application.reports.StewardNoticeService;
import com.sdv.platform.application.reports.StorageReportService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 0032: the Reports endpoints through the real permission check. Every role but the Business Admin is refused
 * with SDV-RPT-001 before the report is read, and each refusal is audited as AUTHORIZATION_DENIED. The intake notices
 * are for the two Steward roles (SDV-INT-001 otherwise). The service's own codes pass through with their status.
 * Task 0033: the same for the Sensitivity report and its CSV; a Steward marks their own notices read.
 */
class ReportsControllerTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final byte[] CSV = "﻿SHA-256 checksum\r\n".getBytes(StandardCharsets.UTF_8);

    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final IntakeReportService reports = mock(IntakeReportService.class);
    private final SensitivityReportService sensitivity = mock(SensitivityReportService.class);
    private final StewardNoticeService notices = mock(StewardNoticeService.class);
    private final StorageReportService storage = mock(StorageReportService.class);
    private final AuthorizationService authorization =
            new AuthorizationService(audit, new CapabilityPolicyService(mock(RoleCapabilityPolicyPort.class)));
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new ReportsController(new SecurityContextFacade(), authorization, reports, sensitivity, storage),
                    new IntakeNoticeController(new SecurityContextFacade(), authorization, notices))
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
                get("/api/v1/admin/reports/intake/export"),
                // Task 0053.
                get("/api/v1/admin/reports/storage"), get("/api/v1/admin/reports/storage/export"));
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
        verifyNoInteractions(reports, storage);
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
                List.of(), new IntakeReportService.Totals(0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), 0, 0, List.of()), List.of(), 50));
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

    // ------------------------------------------------------------------ task 0033: Sensitivity

    @Test
    void onlyTheBusinessAdminSeesTheSensitivityReport() throws Exception {
        List<RequestBuilder> calls = List.of(get("/api/v1/admin/reports/sensitivity"),
                get("/api/v1/admin/reports/sensitivity").param("from", "2026-11-01").param("to", "2026-11-21"),
                get("/api/v1/admin/reports/sensitivity/export"));
        for (SdvRole role : EnumSet.complementOf(EnumSet.of(SdvRole.BUSINESS_ADMIN))) {
            for (RequestBuilder call : calls) {
                signIn(role);
                mvc.perform(call).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("SDV-RPT-001"));
            }
        }
        verifyNoInteractions(sensitivity);
        ArgumentCaptor<AuditEvent> denied = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(calls.size() * (SdvRole.values().length - 1))).append(denied.capture());
        assertTrue(denied.getAllValues().stream().allMatch(e -> e.action().equals("AUTHORIZATION_DENIED")
                && e.targetId().equals("VIEW_INTAKE_REPORTS")));
    }

    @Test
    void theBusinessAdminGetsTheSensitivityReportAndItsCsv() throws Exception {
        signIn(SdvRole.BUSINESS_ADMIN);
        when(sensitivity.report(TENANT, null, null, null)).thenReturn(new SensitivityReportService.SensitivityReport(
                "2026-11-01", "2026-11-21", "2026-11-21", "America/New_York", "INSTALL_DEFAULT", null, "Contract number",
                List.of(), new SensitivityReportService.Documents(5, 2, 1, 8, 1, 1),
                List.of(new SensitivityReportService.RequestRow("DOWNLOAD", 1, 0, 0, 2)),
                new SensitivityReportRepository.LevelChanges(3, 4, 1),
                List.of(new SensitivityReportService.AttemptRow(List.of("BUSINESS_USER"), 7, 2))));
        mvc.perform(get("/api/v1/admin/reports/sensitivity"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.documents.highlyConfidential").value(1))
                .andExpect(jsonPath("$.requests[0].waiting").value(2))
                .andExpect(jsonPath("$.levelChanges.raisedBySendingSystem").value(4))
                .andExpect(jsonPath("$.attempts[0].roles[0]").value("BUSINESS_USER"))
                .andExpect(jsonPath("$.attempts[0].people").value(2));

        byte[] csv = "﻿Section,Line\r\n".getBytes(StandardCharsets.UTF_8);
        when(sensitivity.export(eq(TENANT), eq("person-business_admin"), any(), eq("2026-11-01"), eq("2026-11-21"), isNull()))
                .thenReturn(new SensitivityReportService.Export(csv, 31, "vero-sensitivity-2026-11-01-to-2026-11-21.csv"));
        mvc.perform(get("/api/v1/admin/reports/sensitivity/export").param("from", "2026-11-01").param("to", "2026-11-21"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("vero-sensitivity-2026-11-01-to-2026-11-21.csv")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-SDV-Export-Rows", "31"))
                .andExpect(content().bytes(csv));
    }

    @Test
    void theSensitivityReportsCodesPassThroughWithTheirStatus() throws Exception {
        signIn(SdvRole.BUSINESS_ADMIN);
        when(sensitivity.report(any(), eq("bad"), any(), any())).thenThrow(new CodedRefusal.BadRequest(SdvErrorCode.RPT_002, "no"));
        when(sensitivity.report(any(), isNull(), isNull(), eq("x"))).thenThrow(new CodedRefusal.BadRequest(SdvErrorCode.RPT_003, "no"));
        when(sensitivity.export(any(), any(), any(), isNull(), isNull(), isNull())).thenThrow(new CodedRefusal.Conflict(SdvErrorCode.RPT_004, "no"));
        mvc.perform(get("/api/v1/admin/reports/sensitivity").param("from", "bad").param("to", "2026-11-20"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-RPT-002"));
        mvc.perform(get("/api/v1/admin/reports/sensitivity").param("sourceId", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-RPT-003"));
        mvc.perform(get("/api/v1/admin/reports/sensitivity/export"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SDV-RPT-004"));
    }

    // ------------------------------------------------------------------ notices (task 0032; stored since 0033)

    @Test
    void intakeNoticesAreForTheTwoStewardRoles() throws Exception {
        Set<SdvRole> stewards = EnumSet.of(SdvRole.DOCUMENT_STEWARD, SdvRole.RESTRICTED_STEWARD);
        String body = "{\"noticeIds\":[\"" + UUID.randomUUID() + "\"]}";
        for (SdvRole role : EnumSet.complementOf(EnumSet.copyOf(stewards))) {
            signIn(role);
            mvc.perform(get("/api/v1/business/intake-notices")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SDV-INT-001"));
            mvc.perform(post("/api/v1/business/intake-notices/read").contentType("application/json").content(body))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SDV-INT-001"));
        }
        verifyNoInteractions(notices);
        for (SdvRole role : stewards) {
            String subject = "person-" + role.name().toLowerCase();
            when(notices.unread(TENANT, subject)).thenReturn(new StewardNoticeService.Notices(List.of(), 0));
            signIn(role);
            mvc.perform(get("/api/v1/business/intake-notices")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.notices.length()").value(0)).andExpect(jsonPath("$.unread").value(0));
            verify(notices).unread(TENANT, subject);
        }
    }

    @Test
    void aStewardMarksTheirOwnNoticesRead() throws Exception {
        UUID one = UUID.randomUUID();
        signIn(SdvRole.DOCUMENT_STEWARD);
        when(notices.markRead(eq(TENANT), eq("person-document_steward"), any(), eq(List.of(one)))).thenReturn(1);
        mvc.perform(post("/api/v1/business/intake-notices/read").contentType("application/json")
                        .content("{\"noticeIds\":[\"" + one + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.marked").value(1));
        // A reference that is not a UUID is refused with a code, before the service is asked.
        mvc.perform(post("/api/v1/business/intake-notices/read").contentType("application/json")
                        .content("{\"noticeIds\":[\"not-a-notice\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-INT-003"));
        when(notices.markRead(any(), any(), any(), isNull()))
                .thenThrow(new CodedRefusal.BadRequest(SdvErrorCode.INT_003, "no"));
        mvc.perform(post("/api/v1/business/intake-notices/read").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SDV-INT-003"));
        UUID other = UUID.randomUUID();
        doThrow(new CodedRefusal.NotFound(SdvErrorCode.INT_002, "no"))
                .when(notices).markRead(any(), any(), any(), eq(List.of(other)));
        mvc.perform(post("/api/v1/business/intake-notices/read").contentType("application/json")
                        .content("{\"noticeIds\":[\"" + other + "\"]}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SDV-INT-002"));
    }

    @Test
    void theBusinessAdminGetsTheStorageReportAndItsRegisteredCsv() throws Exception {
        signIn(SdvRole.BUSINESS_ADMIN);
        when(storage.report(TENANT)).thenReturn(new StorageReportService.StorageReport("2026-11-23T10:00:00Z", 120, 15, "UTC",
                "INSTALL_DEFAULT", List.of(), List.of(), List.of(), new StorageReportService.Total(null, 3, 9, 300), List.of(),
                List.of(), List.of(), new StorageReportService.Held(1, 0, 0, 1)));
        mvc.perform(get("/api/v1/admin/reports/storage"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.total.documents").value(3))
                .andExpect(jsonPath("$.heldBack.legalHold").value(1));
        UUID reference = UUID.randomUUID();
        when(storage.export(eq(TENANT), eq("person-business_admin"), any())).thenReturn(new StorageReportService.Export(
                CSV, 1, "vero-storage-2026-11-23-abcdef12.csv", reference, "a".repeat(64)));
        mvc.perform(get("/api/v1/admin/reports/storage/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-SDV-Export-Reference", reference.toString()))
                .andExpect(header().string("X-SDV-Export-Fingerprint", "a".repeat(64)))
                .andExpect(header().string("X-SDV-Export-Rows", "1"))
                .andExpect(content().bytes(CSV));
    }
}
