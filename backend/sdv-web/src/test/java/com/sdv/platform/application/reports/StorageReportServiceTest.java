package com.sdv.platform.application.reports;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IntakeReportRepository;
import com.sdv.platform.application.port.IntakeReportRepository.SendingSystem;
import com.sdv.platform.application.port.StorageReportRepository;
import com.sdv.platform.application.port.StorageReportRepository.Due;
import com.sdv.platform.application.port.StorageReportRepository.HeldBack;
import com.sdv.platform.application.port.StorageReportRepository.Kept;
import com.sdv.platform.application.port.StorageReportRepository.Month;
import com.sdv.platform.application.port.StorageReportRepository.Stored;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.document.SensitivityLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0053: the Storage and retention report from its kept counts; each section, the first count, and the CSV. */
class StorageReportServiceTest {
    private static final UUID T = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID S1 = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID S2 = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private final StorageReportRepository repo = mock(StorageReportRepository.class);
    private final IntakeReportRepository tenants = mock(IntakeReportRepository.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final AtomicReference<Kept> kept = new AtomicReference<>();
    private StorageReportService service;

    @BeforeEach
    void setUp() {
        when(tenants.findTenantTimeZone(T)).thenReturn(Optional.of("America/New_York"));
        when(tenants.findSendingSystems(T)).thenReturn(List.of(new SendingSystem(S1, "Loans"), new SendingSystem(S2, "Cards")));
        when(repo.countStored(T)).thenReturn(List.of(
                new Stored(S1, SensitivityLevel.STANDARD, 10, 30, 1000),
                new Stored(S1, SensitivityLevel.HIGHLY_CONFIDENTIAL, 1, 2, 200),
                new Stored(S2, SensitivityLevel.CONFIDENTIAL, 4, 4, 400)));
        when(repo.countReceived(eq(T), any(), eq("America/New_York"))).thenReturn(List.of(
                new Month("2026-11", S1, SensitivityLevel.STANDARD, 6, 18, 600),
                new Month("2026-11", S2, SensitivityLevel.CONFIDENTIAL, 4, 4, 400),
                new Month("2026-02", S1, SensitivityLevel.HIGHLY_CONFIDENTIAL, 1, 2, 200)));
        Map<Integer, String> names = new HashMap<>();
        names.put(365, "One year");
        when(repo.findRuleNames(T)).thenReturn(names);
        when(repo.countDue(eq(T), any())).thenReturn(List.of(new Due(30, 3, 1, 2, 3), new Due(365, 8, 0, 0, 1), new Due(null, 4, 0, 0, 0)));
        when(repo.countHeldBack(eq(T), any())).thenReturn(new HeldBack(2, 1, 1));
        when(repo.findKept(T)).thenAnswer(inv -> Optional.ofNullable(kept.get()));
        doAnswer(inv -> { kept.set(new Kept(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3))); return null; })
                .when(repo).keep(eq(T), any(), anyLong(), anyString());
        service = new StorageReportService(repo, tenants, audit, "UTC", Clock.fixed(NOW, ZoneOffset.UTC), 15);
    }

    @Test
    void theFirstLoadCountsOnceThenEveryLoadReadsTheKeptCounts() {
        service.report(T);
        service.report(T);
        verify(repo, times(1)).countStored(T);
        verify(repo, times(3)).findKept(T); // the first load: none kept, count, read; the second: read
        // From the twelve months back, in the entity's time zone: 1 December 2025, New York.
        verify(repo).countReceived(T, Instant.parse("2025-12-01T05:00:00Z"), "America/New_York");
        service.countAll(); // no active entity
        when(repo.findActiveTenants()).thenReturn(List.of(T));
        assertEquals(1, service.countAll());
        verify(repo, times(2)).countStored(T);
    }

    @Test
    void eachSectionCountsWhatItShould() {
        StorageReportService.StorageReport r = service.report(T);
        assertEquals(NOW.toString(), r.countedAt());
        assertEquals(15, r.recountMinutes());
        // Storage now, by system and level, with totals.
        assertEquals("Loans", r.stored().getFirst().sendingSystem());
        assertEquals(15, r.total().documents());
        assertEquals(36, r.total().pages());
        assertEquals(1600, r.total().bytes());
        assertEquals(10, r.totals().get(0).documents());
        assertEquals(4, r.totals().get(1).documents());
        assertEquals(1, r.totals().get(2).documents());
        // Growth: twelve months, oldest first, months without documents at zero.
        assertEquals(12, r.growth().size());
        assertEquals("2025-12", r.growth().getFirst().month());
        assertEquals("2026-11", r.growth().getLast().month());
        assertEquals(10, r.growth().getLast().documents());
        assertEquals(6, r.growth().getLast().standard());
        assertEquals(4, r.growth().getLast().confidential());
        assertEquals(1, r.growth().get(2).highlyConfidential(), "February");
        assertEquals(0, r.growth().get(1).documents());
        // Due: named by the policy of that length; keep forever shows none.
        assertEquals("Kept 30 days", StorageReportService.ruleWords(r.retention().get(0)));
        assertEquals("One year (365 days)", StorageReportService.ruleWords(r.retention().get(1)));
        StorageReportService.RuleRow forever = r.retention().get(2);
        assertTrue(forever.keptForever());
        assertEquals("Keep forever", forever.ruleName());
        assertNull(forever.within30());
        assertNull(forever.within90());
        // Held back.
        assertEquals(4, r.heldBack().total());
    }

    @Test
    void theCsvHoldsEveryCountWithAReferenceAndAFingerprintAndIsAudited() throws Exception {
        StorageReportService.Export export = service.export(T, "ada", "corr-1");
        String quoted = new String(export.csv(), StandardCharsets.UTF_8);
        String csv = quoted.replace("\"", ""); // every text cell is quoted (AuditCsv)
        assertTrue(csv.startsWith("﻿" + StorageReportService.CSV_HEADER + "\r\n"));
        assertTrue(csv.contains("Storage now,Loans,Standard,Documents,10,2026-11-23T10:00:00Z,America/New_York\r\n"));
        assertTrue(csv.contains("Storage now,All sending systems,All levels,Bytes,1600,"));
        assertTrue(csv.contains("Growth,2026-11 Cards,Confidential,Documents,4,"));
        assertTrue(csv.contains("Due for deletion,One year (365 days),All levels,Within 90 days,1,"));
        assertTrue(csv.contains("Due for deletion,Keep forever,All levels,Documents stored,4,"));
        assertFalse(csv.contains("Keep forever,All levels,Within"), "keep forever shows none");
        assertTrue(csv.contains("Held back from deletion,Legal hold and quarantine,All levels,Documents,1,"));
        assertEquals(quoted.split("\r\n").length - 1, export.rowCount());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(export.csv())), export.fingerprint());
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertEquals(StorageReportService.EXPORT_ACTION, event.getValue().action());
        assertEquals("ada", event.getValue().actorId());
        assertTrue(event.getValue().safeDetailsJson().contains(export.fingerprint()));
        assertTrue(event.getValue().safeDetailsJson().contains(export.reference().toString()));
        assertTrue(export.filename().matches("vero-storage-2026-11-23-[0-9a-f]{8}\\.csv"));
    }
}
