package com.sdv.platform.application.reports;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IntakeReportRepository;
import com.sdv.platform.application.port.SensitivityReportRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Task 0033: the Sensitivity report's periods, blocks and CSV, against an in-memory repository (the SQL is run on
 * PostgreSQL by SensitivityReportPostgresTest).
 * <p>
 * "Now" is 02:00 UTC on Sunday 1 November 2026, which is still Saturday 31 October in New York (the tenant's time zone
 * here): "this month" there is October, from 04:00 UTC on 1 October to 04:00 UTC on 1 November.
 */
class SensitivityReportServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RS = UUID.fromString("22222222-0000-0000-0000-000000000001");
    private static final UUID LOS = UUID.fromString("22222222-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-11-01T02:00:00Z");
    private static final String NY = "America/New_York";

    private final FakeSensitivity repo = new FakeSensitivity();
    private final FakeTenant tenant = new FakeTenant();
    private final AuditEventPort audit = mock(AuditEventPort.class);

    /** Task 0081: the customer's zone in identity (was the install default, gone). */
    private SensitivityReportService service(String customerZone) {
        tenant.customerZone = customerZone;
        return new SensitivityReportService(repo, tenant, audit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void seed() {
        tenant.systems.add(new IntakeReportRepository.SendingSystem(RS, "Redaction Service"));
        tenant.systems.add(new IntakeReportRepository.SendingSystem(LOS, "=HYPERLINK(\"x\")"));
        repo.levels = new SensitivityReportRepository.DocumentLevels(1_204_330, 78_412, 26_058, 412, 4);
        repo.requests.add(new SensitivityReportRepository.RequestCount("DOWNLOAD", "APPROVED", 63));
        repo.requests.add(new SensitivityReportRepository.RequestCount("DOWNLOAD", "DECLINED", 4));
        repo.requests.add(new SensitivityReportRepository.RequestCount("DOWNLOAD", "EXPIRED", 2));
        repo.requests.add(new SensitivityReportRepository.RequestCount("DOWNLOAD", "WAITING", 21));
        repo.requests.add(new SensitivityReportRepository.RequestCount("PRINT", "APPROVED", 51));
        repo.requests.add(new SensitivityReportRepository.RequestCount("LOWER_RECORD_LEVEL", "WAITING", 1));
        repo.changes = new SensitivityReportRepository.LevelChanges(37, 118, 11);
        repo.attempts.add(new SensitivityReportRepository.AttemptCount(List.of("DOCUMENT_STEWARD"), 9, 4));
        repo.attempts.add(new SensitivityReportRepository.AttemptCount(List.of("BUSINESS_USER"), 214, 96));
        repo.attempts.add(new SensitivityReportRepository.AttemptCount(List.of(), 3, 2));
        repo.label = "Contract number";
    }

    @Test
    void thisMonthIsTheTenantsMonthAndEveryBlockIsCounted() {
        seed();
        SensitivityReportService.SensitivityReport r = service(NY).report(TENANT, null, null, null);
        // 02:00 UTC on 1 November is still 31 October in New York: this month is October there.
        assertEquals("2026-10-01", r.from());
        assertEquals("2026-10-31", r.to());
        assertEquals("2026-10-31", r.today());
        assertEquals(Instant.parse("2026-10-01T04:00:00Z"), repo.from, "midnight on 1 October, New York (summer time)");
        assertEquals(Instant.parse("2026-11-01T04:00:00Z"), repo.to, "midnight on 1 November, New York");
        assertEquals(NOW, repo.now, "requests are judged now: waiting or expired");
        assertNull(repo.source);
        assertEquals("America/New_York", r.timeZone());
        assertEquals("CUSTOMER", r.timeZoneSource());

        assertEquals(new SensitivityReportService.Documents(1_204_330, 78_412, 26_058, 1_308_800, 412, 4), r.documents());
        assertEquals(List.of("DOWNLOAD", "PRINT", "LOWER_LEVEL", "LOWER_RECORD_LEVEL"),
                r.requests().stream().map(SensitivityReportService.RequestRow::kind).toList(), "the board's order, every kind");
        assertEquals(new SensitivityReportService.RequestRow("DOWNLOAD", 63, 4, 2, 21), r.requests().get(0));
        assertEquals(new SensitivityReportService.RequestRow("PRINT", 51, 0, 0, 0), r.requests().get(1));
        assertEquals(new SensitivityReportService.RequestRow("LOWER_LEVEL", 0, 0, 0, 0), r.requests().get(2));
        assertEquals(new SensitivityReportService.RequestRow("LOWER_RECORD_LEVEL", 0, 0, 0, 1), r.requests().get(3));
        assertEquals(new SensitivityReportRepository.LevelChanges(37, 118, 11), r.levelChanges());
        // Most attempts first; no names anywhere, only roles and counts.
        assertEquals(List.of(List.of("BUSINESS_USER"), List.of("DOCUMENT_STEWARD"), List.of()),
                r.attempts().stream().map(SensitivityReportService.AttemptRow::roles).toList());
        assertEquals(96, r.attempts().get(0).people());
        assertEquals("Contract number", r.primaryIdentifierLabel());
    }

    @Test
    void thePeriodBoundaryIsInTheTenantsTimeZoneAndItsOwnSettingWins() {
        seed();
        tenant.zone = "Asia/Tokyo";
        SensitivityReportService.SensitivityReport r = service(NY).report(TENANT, "2026-11-01", "2026-11-01", RS.toString());
        // 1 November in Tokyo is 15:00 UTC on 31 October to 15:00 UTC on 1 November.
        assertEquals(Instant.parse("2026-10-31T15:00:00Z"), repo.from);
        assertEquals(Instant.parse("2026-11-01T15:00:00Z"), repo.to);
        assertEquals("ENTITY", r.timeZoneSource());
        assertEquals("2026-11-01", r.today(), "11:00 on 1 November in Tokyo");
        assertEquals(RS, repo.source);
        assertEquals(RS, r.sourceId());
    }

    /** Task 0081: zones are identity's; a day follows summer time in the zone that applies, as before. */
    @Test
    void daysFollowSummerTimeInTheZoneIdentityGives() {
        seed();
        // 1 November 2026: New York (the customer's zone) leaves summer time, so the day is 25 hours long.
        SensitivityReportService.SensitivityReport ny = service(NY).report(TENANT, "2026-11-01", "2026-11-01", RS.toString());
        assertEquals("CUSTOMER", ny.timeZoneSource());
        assertEquals(Instant.parse("2026-11-01T04:00:00Z"), repo.from);
        assertEquals(Instant.parse("2026-11-02T05:00:00Z"), repo.to);
        // The entity's own zone wins: London left summer time on 25 October, a 25-hour day there.
        tenant.zone = "Europe/London";
        SensitivityReportService.SensitivityReport london = service(NY).report(TENANT, "2026-10-25", "2026-10-25", RS.toString());
        assertEquals("ENTITY", london.timeZoneSource());
        assertEquals(Instant.parse("2026-10-24T23:00:00Z"), repo.from);
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), repo.to);
    }

    @Test
    void aPeriodOrSendingSystemThatIsNotValidIsRefusedWithItsCode() {
        seed();
        SensitivityReportService s = service(NY);
        assertEquals("SDV-RPT-002", code(() -> s.report(TENANT, "2026-11-01", null, null)), "only one day");
        assertEquals("SDV-RPT-002", code(() -> s.report(TENANT, "1 Nov", "2026-11-02", null)), "not a date");
        assertEquals("SDV-RPT-002", code(() -> s.report(TENANT, "2026-11-02", "2026-11-01", null)), "backwards");
        assertEquals("SDV-RPT-002", code(() -> s.report(TENANT, "2025-01-01", "2026-01-02", null)), "367 days");
        assertDoesNotThrow(() -> s.report(TENANT, "2025-01-02", "2026-01-02", null), "366 days");
        assertEquals("SDV-RPT-003", code(() -> s.report(TENANT, null, null, UUID.randomUUID().toString())), "another tenant's");
        assertEquals("SDV-RPT-003", code(() -> s.report(TENANT, null, null, "loans")), "not an ID");
        tenant.zone = "Mars/Olympus";
        assertEquals("SDV-RPT-004", code(() -> s.report(TENANT, null, null, null)));
        assertEquals("SDV-RPT-004", code(() -> s.export(TENANT, "priya", "c", null, null, null)));
        verifyNoInteractions(audit);
        tenant.zone = null;
        assertEquals("SDV-RPT-004", code(() -> service("Not/AZone").report(TENANT, null, null, null)), "a customer's zone that is not one (task 0081)");
    }

    @Test
    void whatTheRepositoryCantExplainStopsTheReport() {
        seed();
        repo.requests.add(new SensitivityReportRepository.RequestCount("SHARE", "APPROVED", 1));
        assertThrows(com.sdv.platform.domain.error.InternalCheckFailed.class, () -> service(NY).report(TENANT, null, null, null));
        repo.requests.removeLast();
        repo.requests.add(new SensitivityReportRepository.RequestCount("PRINT", "MAYBE", 1));
        assertThrows(com.sdv.platform.domain.error.InternalCheckFailed.class, () -> service(NY).report(TENANT, null, null, null));
    }

    @Test
    void theCsvHasEveryCountWithItsPeriodAndTheFormulaGuardAndIsAudited() {
        seed();
        SensitivityReportService.Export export = service(NY).export(TENANT, "priya", "corr-1", "2026-10-01", "2026-10-31", LOS.toString());
        String csv = new String(export.csv(), StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertEquals("﻿Section,Line,Measure,Count,From,To,Time zone,Sending system", lines[0]);
        // 5 document lines, 16 request lines, 3 level changes, 2 per role (3 roles).
        assertEquals(5 + 16 + 3 + 6, export.rowCount());
        assertEquals(export.rowCount() + 1, lines.length);
        String guard = "\"'=HYPERLINK(\"\"x\"\")\"";
        assertTrue(lines[1].equals("\"Documents by level\",\"Standard\",\"Documents stored\",1204330,\"2026-10-31\",\"2026-10-31\",\"America/New_York\"," + guard), lines[1]);
        assertTrue(csv.contains("\"Documents by level\",\"Contracts with a contract level\",\"Contracts\",412,"), csv);
        assertTrue(csv.contains("\"Documents by level\",\"On legal hold\",\"Documents stored\",4,"), csv);
        assertTrue(csv.contains("\"Requests\",\"Download\",\"Waiting\",21,\"2026-10-01\",\"2026-10-31\","), csv);
        assertTrue(csv.contains("\"Requests\",\"Lower level (whole contract)\",\"Waiting\",1,"), csv);
        assertTrue(csv.contains("\"Level changes\",\"Set or raised by a sending system (document or contract level)\",\"Changes\",118,"), csv);
        assertTrue(csv.contains("\"Attempts to open restricted documents\",\"Business user\",\"People\",96,"), csv);
        assertTrue(csv.contains("\"Attempts to open restricted documents\",\"Role not recorded\",\"Attempts\",3,"), csv);
        assertFalse(csv.contains(",=") || csv.contains("\"=") , "no cell starts with a formula");
        assertEquals("vero-sensitivity-2026-10-01-to-2026-10-31.csv", export.filename());

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        AuditEvent e = event.getValue();
        assertEquals("SENSITIVITY_REPORT_EXPORTED", e.action());
        assertEquals("priya", e.actorId());
        assertEquals("REPORT", e.targetType());
        assertEquals("SENSITIVITY", e.targetId());
        assertEquals("corr-1", e.correlationId());
        assertEquals(AuditOutcome.SUCCESS, e.outcome());
        assertEquals("{\"from\":\"2026-10-01\",\"to\":\"2026-10-31\",\"sourceId\":\"" + LOS + "\",\"timeZone\":\"America/New_York\",\"rowCount\":30}",
                e.safeDetailsJson());
    }

    @Test
    void wordsFollowThePrimaryIdentifierAndTheRoles() {
        assertEquals("Contracts", SensitivityReportService.recordWords("Contract number"));
        assertEquals("Patients", SensitivityReportService.recordWords("Patient ID"));
        assertEquals("Records", SensitivityReportService.recordWords(null));
        assertEquals("Records", SensitivityReportService.recordWords("Reference"));
        assertEquals("Lower level (whole patient)", SensitivityReportService.kindWords("LOWER_RECORD_LEVEL", "Patients"));
        assertEquals("Business user and Document steward",
                SensitivityReportService.roleWords(List.of("BUSINESS_USER", "DOCUMENT_STEWARD")));
        assertEquals("Role not recorded", SensitivityReportService.roleWords(List.of()));
    }

    private static String code(Executable call) {
        Throwable t = assertThrows(RuntimeException.class, call);
        assertInstanceOf(CodedRefusal.class, t, t.toString());
        return ((CodedRefusal) t).errorCode().code();
    }

    /** Records what it was asked and answers what it was given. */
    static final class FakeSensitivity implements SensitivityReportRepository {
        DocumentLevels levels = new DocumentLevels(0, 0, 0, 0, 0);
        final List<RequestCount> requests = new ArrayList<>();
        LevelChanges changes = new LevelChanges(0, 0, 0);
        final List<AttemptCount> attempts = new ArrayList<>();
        String label;
        Instant from;
        Instant to;
        Instant now;
        UUID source;

        @Override public DocumentLevels countStoredDocuments(UUID tenantId, UUID sourceId) {
            source = sourceId;
            return levels;
        }

        @Override public List<RequestCount> countRequests(UUID tenantId, UUID sourceId, Instant f, Instant t, Instant n) {
            from = f;
            to = t;
            now = n;
            return List.copyOf(requests);
        }

        @Override public LevelChanges countLevelChanges(UUID tenantId, UUID sourceId, Instant f, Instant t) {
            assertEquals(from, f);
            assertEquals(to, t);
            return changes;
        }

        @Override public List<AttemptCount> countRestrictedAttempts(UUID tenantId, UUID sourceId, Instant f, Instant t) {
            assertEquals(from, f);
            assertEquals(to, t);
            return List.copyOf(attempts);
        }

        @Override public Optional<String> findPrimaryIdentifierLabel(UUID tenantId, UUID sourceId) {
            return Optional.ofNullable(label);
        }
    }

    static final class FakeTenant implements IntakeReportRepository {
        String zone;
        String customerZone;
        final List<SendingSystem> systems = new ArrayList<>();

        @Override public Optional<String> findTenantTimeZone(UUID tenantId) { return Optional.ofNullable(zone); }
        @Override public Optional<String> findCustomerTimeZone(UUID tenantId) { return Optional.ofNullable(customerZone); }
        @Override public List<SendingSystem> findSendingSystems(UUID tenantId) { return List.copyOf(systems); }
        @Override public List<Item> findItems(UUID tenantId, UUID sourceId, Instant from, Instant to, int limit) { throw new AssertionError(); }
        @Override public java.util.Map<UUID, PrimaryIdentifier> findPrimaryIdentifiers(UUID tenantId, java.util.Collection<UUID> ids) { throw new AssertionError(); }
        @Override public java.util.Set<String> findValuesKnownBefore(UUID tenantId, java.util.Collection<String> keys, java.util.Collection<String> values, Instant before) { throw new AssertionError(); }
    }
}
