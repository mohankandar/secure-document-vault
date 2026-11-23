package com.sdv.platform.application.reports;

import com.sdv.platform.application.port.ActivityReportRepository;
import com.sdv.platform.application.port.ActivityReportRepository.Count;
import com.sdv.platform.application.port.ActivityReportRepository.Person;
import com.sdv.platform.application.port.ActivityReportRepository.Window;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IntakeReportRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.error.CodedRefusal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0054: the Activity report's period, kept days and live part, each section, and the CSV. */
class ActivityReportServiceTest {
    private static final UUID T = UUID.fromString("11111111-1111-1111-1111-111111111111");
    /** 15:00 UTC is 10:00 in New York. */
    private static final Instant NOW = Instant.parse("2026-11-23T15:00:00Z");

    private final ActivityReportRepository repo = mock(ActivityReportRepository.class);
    private final IntakeReportRepository tenants = mock(IntakeReportRepository.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final Map<LocalDate, String> kept = new HashMap<>();
    private ActivityReportService service;

    @BeforeEach
    void setUp() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(tenants.findTenantTimeZone(T)).thenReturn(Optional.of("America/New_York"));
        when(repo.findKeptDays(eq(T), any(), any())).thenAnswer(inv -> new HashMap<>(kept));
        when(repo.keepDay(eq(T), any(), anyString(), any(), any(), any())).thenAnswer(inv -> {
            kept.put(inv.getArgument(1), inv.getArgument(2));
            return 0L;
        });
        when(repo.count(eq(T), any())).thenReturn(List.of(
                new Count("OPENED", "BUSINESS_USER", "STANDARD", 40),
                new Count("OPENED", "BUSINESS_USER", "CONFIDENTIAL", 5),
                new Count("OPENED", "", "STANDARD", 3),
                new Count("OPENED", "DOCUMENT_STEWARD", "HIGHLY_CONFIDENTIAL", 2),
                new Count("PRINTED", "DOCUMENT_STEWARD", "HIGHLY_CONFIDENTIAL", 1),
                new Count("DOWNLOADED", "BUSINESS_USER", null, 4),
                new Count("REFUSED_LEVEL", "BUSINESS_USER", "HIGHLY_CONFIDENTIAL", 7),
                new Count("REFUSED_QUARANTINE", "BUSINESS_USER", "STANDARD", 2),
                new Count("REFUSED_QUARANTINE", "", "STANDARD", 1),
                new Count("REQUESTED", "", null, 3),
                new Count("APPROVED", "", null, 2),
                new Count("DECLINED", "", null, 1),
                new Count("EXPIRED", "", null, 1),
                new Count("GRANT_USED", "", null, 2),
                new Count("GRANT_RETURNED", "", null, 1)));
        when(repo.busiest(eq(T), any(), any(), eq(20))).thenReturn(List.of(
                new Person("bob@example.test", "Bob Person", "BUSINESS_USER", 30, 5, 0, 35),
                new Person("dee@example.test", null, "DOCUMENT_STEWARD;DOCUMENT_STEWARD,RESTRICTED_STEWARD", 0, 0, 2, 2)));
        service = new ActivityReportService(repo, tenants, audit, tx, "UTC", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theLastSevenDaysByDefaultTheClosedOnesKeptAndTodayFromTheAudit() {
        ActivityReportService.ActivityReport r = service.report(T, null, null);
        assertEquals("2026-11-17", r.from());
        assertEquals("2026-11-23", r.to());
        assertEquals("2026-11-22", r.keptThrough());
        // The six closed days were kept, in New York's time zone, newest first.
        assertEquals(6, kept.size());
        verify(repo).keepDay(T, LocalDate.parse("2026-11-22"), "America/New_York", Instant.parse("2026-11-22T05:00:00Z"),
                Instant.parse("2026-11-23T05:00:00Z"), NOW);
        ArgumentCaptor<Window> window = ArgumentCaptor.forClass(Window.class);
        verify(repo).count(eq(T), window.capture());
        assertEquals(LocalDate.parse("2026-11-17"), window.getValue().firstDay());
        assertEquals(LocalDate.parse("2026-11-22"), window.getValue().lastKeptDay());
        assertEquals(Instant.parse("2026-11-23T05:00:00Z"), window.getValue().liveFrom(), "today, from midnight in New York");
        assertEquals(NOW, window.getValue().liveTo());
        verify(repo).live(T, Instant.parse("2026-11-23T05:00:00Z"), NOW);
        // A second report keeps nothing again.
        service.report(T, null, null);
        verify(repo, times(6)).keepDay(eq(T), any(), anyString(), any(), any(), any());
    }

    @Test
    void aDayIsKeptOnlyOnceItHasSettled() {
        // 05:05 UTC: midnight in New York was 5 minutes ago, so yesterday isn't settled yet.
        ActivityReportService early = new ActivityReportService(repo, tenants, audit, tx, "UTC",
                Clock.fixed(Instant.parse("2026-11-23T05:05:00Z"), ZoneOffset.UTC));
        ActivityReportService.ActivityReport r = early.report(T, "2026-11-22", "2026-11-23");
        assertNull(r.keptThrough());
        assertFalse(kept.containsKey(LocalDate.parse("2026-11-22")));
    }

    @Test
    void eachSectionCountsWhatItShould() {
        ActivityReportService.ActivityReport r = service.report(T, null, null);
        ActivityReportService.AccessTotal opened = r.accessTotals().getFirst();
        assertEquals("OPENED", opened.kind());
        assertEquals(50, opened.total());
        assertEquals(43, opened.standard());
        assertEquals(5, opened.confidential());
        assertEquals(2, opened.highlyConfidential());
        assertEquals(4, r.accessTotals().get(2).levelNotRecorded(), "downloads without a level");
        assertTrue(r.access().stream().anyMatch(a -> a.kind().equals("OPENED") && a.roles().isEmpty() && a.total() == 3), "role not recorded");
        assertEquals(List.of("BUSINESS_USER"), r.refusals().getFirst().roles());
        assertEquals(7, r.refusals().getFirst().level());
        assertEquals(2, r.refusals().getFirst().quarantine());
        assertEquals("Bob Person", r.busiest().getFirst().name());
        assertEquals("dee@example.test", r.busiest().get(1).name(), "no name recorded: the sign-in ID, as on the Audit page");
        assertEquals(List.of("DOCUMENT_STEWARD", "RESTRICTED_STEWARD"), r.busiest().get(1).roles());
        assertEquals(new ActivityReportService.Approvals(3, 2, 1, 1, 2, 1), r.approvals());
    }

    @Test
    void thePeriodIsCheckedLikeTheOtherReports() {
        for (String[] bad : List.of(new String[]{"2026-11-20", null}, new String[]{"2026-11-20", "2026-11-19"},
                new String[]{"2026-08-01", "2026-11-23"}, new String[]{"2026-11-20", "2026-11-24"}, new String[]{"20 Nov", "2026-11-23"})) {
            CodedRefusal refusal = (CodedRefusal) assertThrows(RuntimeException.class, () -> service.report(T, bad[0], bad[1]));
            assertEquals("SDV-RPT-002", refusal.errorCode().code());
        }
        assertEquals("2026-08-24", service.report(T, "2026-08-24", "2026-11-23").from(), "92 days");
    }

    @Test
    void theCsvHoldsEveryCountWithAReferenceAndAFingerprintAndIsAudited() {
        ActivityReportService.Export export = service.export(T, "ada", "corr", null, null);
        String csv = new String(export.csv(), StandardCharsets.UTF_8).replace("\"", "");
        assertTrue(csv.contains("Access,Opened, Business user,Standard,Events,40,2026-11-17,2026-11-23,America/New_York"));
        assertTrue(csv.contains("Access,Opened, Role not recorded,Standard,Events,3,"));
        assertTrue(csv.contains("Refusals,Business user,All levels,Opens refused for the level,7,"));
        assertTrue(csv.contains("Busiest people,1. Bob Person (Business user),Standard,Opens,30,"));
        assertTrue(csv.contains("Highly Confidential approvals,One-time grants,Highly Confidential,Given back,1,"));
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertEquals(ActivityReportService.EXPORT_ACTION, event.getValue().action());
        assertTrue(event.getValue().safeDetailsJson().contains(export.fingerprint()));
        assertTrue(export.filename().startsWith("vero-activity-2026-11-17-to-2026-11-23-"));
    }

    @Test
    void todayFromTheAuditJoinsTheKeptDaysByRoleNameAndRanksPeopleWithIt() {
        // Today, as recorded: roles as JSON text, in any order.
        when(repo.live(eq(T), any(), any())).thenReturn(List.of(
                new ActivityReportRepository.Row("OPENED", "[\"BUSINESS_USER\"]", "STANDARD", "carl@example.test", "Carl Person", 6),
                new ActivityReportRepository.Row("REFUSED_LEVEL", "[\"RESTRICTED_STEWARD\", \"BUSINESS_USER\"]", "HIGHLY_CONFIDENTIAL", null, null, 1)));
        ActivityReportService.ActivityReport r = service.report(T, null, null);
        assertEquals(56, r.accessTotals().getFirst().total(), "50 kept and 6 today");
        assertTrue(r.access().stream().anyMatch(a -> a.kind().equals("OPENED") && a.roles().equals(List.of("BUSINESS_USER")) && a.total() == 51),
                "kept 'BUSINESS_USER' and today's '[\"BUSINESS_USER\"]' are one row");
        assertTrue(r.refusals().stream().anyMatch(f -> f.roles().equals(List.of("BUSINESS_USER", "RESTRICTED_STEWARD")) && f.level() == 1), "sorted");
        ArgumentCaptor<List<Person>> today = ArgumentCaptor.forClass(List.class);
        verify(repo).busiest(eq(T), any(), today.capture(), eq(20));
        assertEquals(List.of(new Person("carl@example.test", "Carl Person", "[\"BUSINESS_USER\"]", 6, 0, 0, 6)), today.getValue(),
                "today's people go to the busiest list");
    }
}
