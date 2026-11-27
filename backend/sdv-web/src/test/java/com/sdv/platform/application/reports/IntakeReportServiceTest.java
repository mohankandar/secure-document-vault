package com.sdv.platform.application.reports;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IntakeReportRepository;
import com.sdv.platform.application.port.IntakeReportRepository.Item.Kind;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Task 0032: the Daily intake report's counting, on a seeded day with every outcome, against an in-memory repository
 * (the SQL is run on PostgreSQL by IntakeReportPostgresTest).
 * <p>
 * The day: Friday 20 November 2026 in the tenant's time zone, America/New_York (the install default here), which is
 * 05:00 UTC on the 20th to 05:00 UTC on the 21st. Two sending systems, Redaction Service and the Loan Origination System. The
 * same contract (300000000001) comes from both and counts once in the overall total.
 */
class IntakeReportServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RS = UUID.fromString("22222222-0000-0000-0000-000000000001");
    private static final UUID LOS = UUID.fromString("22222222-0000-0000-0000-000000000002");
    private static final UUID A1 = UUID.fromString("33333333-0000-0000-0000-0000000000a1");
    private static final UUID A2 = UUID.fromString("33333333-0000-0000-0000-0000000000a2");
    private static final UUID B1 = UUID.fromString("33333333-0000-0000-0000-0000000000b1");
    /** Mid-morning on the 20th in New York. */
    private static final Instant NOW = Instant.parse("2026-11-20T15:00:00Z");
    private static final String NY = "America/New_York";

    private final FakeReports repo = new FakeReports();
    private final AuditEventPort audit = mock(AuditEventPort.class);

    /** Task 0081: the customer's zone in identity (was the install default, gone). */
    private IntakeReportService service(String customerZone) {
        repo.customerZone = customerZone;
        return new IntakeReportService(repo, audit, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String sha(char c) {
        return String.valueOf(c).repeat(64);
    }

    /** The seeded day (see the class comment); returns nothing, fills the fake. */
    private void seedDay() {
        repo.system(RS, "Redaction Service");
        repo.system(LOS, "Loan Origination System");
        // A1: a new contract today. Two documents, a duplicate, a failure, and a document at 23:59 New York time.
        repo.submission(RS, A1, "req-a1", "2026-11-20T14:00:00Z", "300000000001");
        repo.document(RS, A1, sha('1'), 5, "2026-11-20T14:01:00Z", "AVAILABLE");
        repo.document(RS, A1, sha('2'), 7, "2026-11-20T14:02:00Z", "AVAILABLE");
        repo.duplicate(RS, A1, sha('1'), 5, "2026-11-20T14:03:00Z");
        repo.document(RS, A1, sha('3'), null, "2026-11-20T14:04:00Z", "PROCESSING_FAILED");
        repo.document(RS, A1, sha('4'), 1, "2026-11-21T04:59:00Z", "AVAILABLE");
        // 23:59:59 on the 19th in New York: another day.
        repo.document(RS, A1, sha('5'), 9, "2026-11-20T04:59:59Z", "AVAILABLE");
        // A2: a contract first delivered yesterday, updated today, with a refused upload.
        repo.submission(RS, A2, "req-a2", "2026-11-19T20:00:00Z", "300000000002");
        repo.document(RS, A2, sha('6'), 3, "2026-11-20T16:00:00Z", "AVAILABLE");
        repo.refusal(RS, A2, null, "SDV-SEN-001", "2026-11-20T16:05:00Z");
        // B1: the same contract as A1, from the other sending system. Its request ID would be a spreadsheet formula.
        repo.submission(LOS, B1, "=HYPERLINK(\"x\")", "2026-11-20T17:00:00Z", "300000000001");
        repo.document(LOS, B1, sha('7'), 4, "2026-11-20T17:01:00Z", "AVAILABLE");
        // Refused before any submission existed.
        repo.refusal(LOS, null, "req-bad", "SDV-SEN-006", "2026-11-20T18:00:00Z");
    }

    @Test
    void aSeededDayWithEveryOutcome() {
        seedDay();
        IntakeReportService.IntakeReport r = service(NY).intake(TENANT, null, null, null);

        assertEquals("2026-11-20", r.today());
        assertEquals("2026-11-20", r.from());
        assertEquals("2026-11-20", r.to());
        assertEquals(NY, r.timeZone());
        assertEquals("CUSTOMER", r.timeZoneSource());
        assertEquals("Contract number", r.primaryIdentifierLabel());
        assertEquals(List.of("Redaction Service", "Loan Origination System"), r.sendingSystems().stream().map(IntakeReportRepository.SendingSystem::displayName).toList());

        IntakeReportService.Totals t = r.totals();
        assertEquals(4, t.deliveries(), "A1, A2, B1 and the refusal before a submission");
        assertEquals(2, t.sendingSystems());
        assertEquals(5, t.documents(), "accepted only; the failure and the other day's document are not");
        assertEquals(20, t.pages());
        assertEquals(1, t.duplicates());
        assertEquals(2, t.contracts(), "300000000001 from both systems counts once");
        assertEquals(1, t.newContracts());
        assertEquals(1, t.updatedContracts());
        assertEquals(2, t.refused());
        assertEquals(List.of(new IntakeReportService.CodeCount("SDV-SEN-001", 1), new IntakeReportService.CodeCount("SDV-SEN-006", 1)), t.refusedCodes());
        assertEquals(1, t.failed());

        // Both systems have two deliveries, so they are listed by name.
        assertEquals(List.of("Loan Origination System", "Redaction Service"), r.systems().stream().map(IntakeReportService.SystemBlock::displayName).toList());
        IntakeReportService.SystemBlock rs = r.systems().get(1);
        assertEquals(new IntakeReportService.SystemTotals(2, 2, 4, 16, 1, 1, 1), rs.totals());
        IntakeReportService.DeliveryLine a1 = rs.deliveries().get(0);
        assertEquals("req-a1", a1.requestId());
        assertEquals("2026-11-20T09:00", a1.receivedLocal(), "the delivery's first time, in New York time");
        assertEquals("300000000001", a1.primaryIdentifier());
        assertEquals(List.of(3, 13L, 1, 0, 1), List.of(a1.documents(), a1.pages(), a1.duplicates(), a1.refused(), a1.failed()));
        IntakeReportService.DeliveryLine a2 = rs.deliveries().get(1);
        assertEquals("req-a2", a2.requestId());
        assertEquals("2026-11-20T11:00", a2.receivedLocal(), "created yesterday: listed from its first item today");
        assertEquals(List.of("SDV-SEN-001"), a2.refusedCodes());

        IntakeReportService.SystemBlock los = r.systems().get(0);
        assertEquals(new IntakeReportService.SystemTotals(2, 1, 1, 4, 0, 1, 0), los.totals());
        IntakeReportService.DeliveryLine refusedFirst = los.deliveries().get(1);
        assertEquals("req-bad", refusedFirst.requestId(), "refused before a submission existed, still listed");
        assertNull(refusedFirst.primaryIdentifier());
        assertEquals(List.of("SDV-SEN-006"), refusedFirst.refusedCodes());
        assertEquals(0, los.moreDeliveries());
        verifyNoInteractions(audit);
    }

    /**
     * Task 0039: an intake request of a known sending system that failed on Vero's side is counted under that system as
     * failed, with the code it got; with the documents that failed after receipt in the totals, apart in failedAtIntake.
     * In the CSV it is a FAILED_AT_INTAKE line with its code.
     */
    @Test
    void intakeFailuresAreCountedUnderTheirSystemWithTheirCode() {
        seedDay();
        repo.failure(RS, A2, null, "SDV-SYS-014", "2026-11-20T16:06:00Z");
        repo.failure(LOS, null, "req-down", "SDV-INT-010", "2026-11-20T18:30:00Z");
        IntakeReportService.IntakeReport r = service(NY).intake(TENANT, null, null, null);

        IntakeReportService.Totals t = r.totals();
        assertEquals(3, t.failed(), "one after receipt, two at intake");
        assertEquals(2, t.failedAtIntake());
        assertEquals(List.of(new IntakeReportService.CodeCount("SDV-INT-010", 1), new IntakeReportService.CodeCount("SDV-SYS-014", 1)), t.failedCodes());
        assertEquals(2, t.refused(), "failures are not refusals");
        assertEquals(5, t.deliveries(), "the failure before a submission is a delivery line of its own");

        IntakeReportService.DeliveryLine a2 = block(r, "Redaction Service").deliveries().get(1);
        assertEquals(1, a2.failed());
        assertEquals(List.of("SDV-SYS-014"), a2.failedCodes());
        assertEquals(List.of("SDV-SEN-001"), a2.refusedCodes());
        IntakeReportService.DeliveryLine down = block(r, "Loan Origination System").deliveries().getLast();
        assertEquals("req-down", down.requestId());
        assertEquals(List.of("SDV-INT-010"), down.failedCodes());

        String csv = new String(service(NY).export(TENANT, "admin", "corr", null, null, null).csv(), StandardCharsets.UTF_8);
        assertTrue(csv.lines().anyMatch(l -> l.endsWith("\"FAILED_AT_INTAKE\",\"SDV-SYS-014\"")), csv);
        assertTrue(csv.lines().anyMatch(l -> l.contains("req-down") && l.endsWith("\"FAILED_AT_INTAKE\",\"SDV-INT-010\"")), csv);
    }

    private static IntakeReportService.SystemBlock block(IntakeReportService.IntakeReport r, String name) {
        return r.systems().stream().filter(b -> b.displayName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theDayBoundaryIsTheTenantsTimeZone() {
        seedDay();
        // Same data counted in UTC: the document at 04:59:59 UTC on the 20th is in, the one at 04:59 on the 21st is out.
        IntakeReportService.IntakeReport utc = service("UTC").intake(TENANT, "2026-11-20", "2026-11-20", RS.toString());
        IntakeReportService.DeliveryLine a1 = utc.systems().get(0).deliveries().stream()
                .filter(l -> "req-a1".equals(l.requestId())).findFirst().orElseThrow();
        assertEquals(3, a1.documents());
        assertEquals(5 + 7 + 9, a1.pages());
        assertEquals("2026-11-20T04:59", a1.receivedLocal());

        // The entity's own zone wins over the customer's.
        repo.timeZone = NY;
        IntakeReportService.IntakeReport own = service("UTC").intake(TENANT, "2026-11-20", "2026-11-20", RS.toString());
        assertEquals("ENTITY", own.timeZoneSource());
        assertEquals(NY, own.timeZone());
        assertEquals(5 + 7 + 1, own.systems().get(0).deliveries().get(0).pages());

        // The 21st in New York starts at 05:00 UTC: the 23:59 document is not on it.
        IntakeReportService.IntakeReport next = service(NY).intake(TENANT, "2026-11-21", "2026-11-21", null);
        assertEquals(0, next.totals().deliveries());
        // Today in New York at 02:00 UTC on the 21st is still the 20th.
        repo.customerZone = NY;
        IntakeReportService late = new IntakeReportService(repo, audit, Clock.fixed(Instant.parse("2026-11-21T02:00:00Z"), ZoneOffset.UTC));
        assertEquals("2026-11-20", late.intake(TENANT, null, null, null).today());
    }

    @Test
    void oneSendingSystemCountsItsOwnContractsAndNewIsFirstEverAcrossSystems() {
        seedDay();
        IntakeReportService.IntakeReport los = service(NY).intake(TENANT, null, null, LOS.toString());
        assertEquals(LOS, los.sourceId());
        assertEquals(List.of("Loan Origination System"), los.systems().stream().map(IntakeReportService.SystemBlock::displayName).toList());
        assertEquals(1, los.totals().contracts());
        assertEquals(1, los.totals().newContracts());
        // A date range: the 19th and the 20th. A2 was created on the 19th, so 300000000002 is new in this range.
        IntakeReportService.IntakeReport two = service(NY).intake(TENANT, "2026-11-19", "2026-11-20", null);
        assertEquals(2, two.totals().contracts());
        assertEquals(2, two.totals().newContracts());
        assertEquals(0, two.totals().updatedContracts());
        assertEquals("2026-11-19", two.from());
    }

    @Test
    void longListsShowFiftyLinesPerSystemAndTheRestAreInTheCsv() {
        repo.system(RS, "Redaction Service");
        for (int i = 0; i < 53; i++) {
            UUID s = UUID.nameUUIDFromBytes(("s" + i).getBytes(StandardCharsets.UTF_8));
            repo.submission(RS, s, "req-" + i, "2026-11-20T14:%02d:00Z".formatted(i), "4000000000" + (10 + i));
        }
        IntakeReportService.SystemBlock rs = service(NY).intake(TENANT, null, null, null).systems().get(0);
        assertEquals(53, rs.totals().deliveries());
        assertEquals(50, rs.deliveries().size());
        assertEquals(3, rs.moreDeliveries());
        assertEquals("req-0", rs.deliveries().get(0).requestId(), "oldest first");
    }

    @Test
    void theCsvHasOneLinePerDocumentWithTheFormulaGuardAndIsAudited() {
        seedDay();
        IntakeReportService.Export export = service(NY).export(TENANT, "priya", "corr-1", null, null, null);
        String csv = new String(export.csv(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("﻿"), "a byte-order mark for spreadsheets");
        List<String> lines = List.of(csv.substring(1).split("\r\n"));
        assertEquals("SHA-256 checksum,Request ID,Sending system,Primary identifier,Pages,\"Received (America/New_York)\",Received (UTC),Outcome,Refusal code", lines.get(0));
        assertEquals(9, export.rowCount(), "7 from Redaction Service (incl. the failure, the duplicate, the refusal), 2 from the other");
        assertEquals(10, lines.size());
        assertEquals("\"" + sha('1') + "\",\"req-a1\",\"Redaction Service\",\"300000000001\",\"5\",\"2026-11-20T09:01-05:00\",\"2026-11-20T14:01:00Z\",\"ACCEPTED\",\"\"", lines.get(1));
        assertTrue(lines.contains("\"" + sha('1') + "\",\"req-a1\",\"Redaction Service\",\"300000000001\",\"5\",\"2026-11-20T09:03-05:00\",\"2026-11-20T14:03:00Z\",\"DUPLICATE_ALREADY_STORED\",\"\""));
        assertTrue(lines.contains("\"" + sha('3') + "\",\"req-a1\",\"Redaction Service\",\"300000000001\",\"\",\"2026-11-20T09:04-05:00\",\"2026-11-20T14:04:00Z\",\"FAILED\",\"\""));
        assertTrue(lines.contains("\"\",\"req-a2\",\"Redaction Service\",\"300000000002\",\"\",\"2026-11-20T11:05-05:00\",\"2026-11-20T16:05:00Z\",\"REFUSED\",\"SDV-SEN-001\""));
        assertTrue(lines.contains("\"\",\"req-bad\",\"Loan Origination System\",\"\",\"\",\"2026-11-20T13:00-05:00\",\"2026-11-20T18:00:00Z\",\"REFUSED\",\"SDV-SEN-006\""));
        // The formula guard: a cell starting with = + - @ is prefixed with an apostrophe; quotes are doubled.
        assertTrue(lines.stream().anyMatch(l -> l.contains(",\"'=HYPERLINK(\"\"x\"\")\",")), csv);
        assertFalse(lines.stream().skip(1).anyMatch(l -> l.contains(sha('5'))), "the other day's document is not in it");
        assertEquals("vero-daily-intake-2026-11-20.csv", export.filename());

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        AuditEvent e = event.getValue();
        assertEquals("INTAKE_REPORT_EXPORTED", e.action());
        assertEquals(AuditOutcome.SUCCESS, e.outcome());
        assertEquals("priya", e.actorId());
        assertEquals("{\"from\":\"2026-11-20\",\"to\":\"2026-11-20\",\"sourceId\":null,\"timeZone\":\"America/New_York\",\"rowCount\":9}", e.safeDetailsJson());
        assertEquals("vero-daily-intake-2026-11-01-to-2026-11-20.csv",
                service(NY).export(TENANT, "priya", "corr-2", "2026-11-01", "2026-11-20", null).filename());
    }

    @Test
    void theFormulaGuardCoversEveryLeadingCharacter() {
        for (String s : List.of("=1", "+1", "-1", "@SUM(A1)", "\tx", "\rx")) {
            assertEquals("\"'" + s + "\"", com.sdv.platform.application.audit.AuditCsv.cell(s), s);
        }
        assertEquals("\"300000000001\"", com.sdv.platform.application.audit.AuditCsv.cell("300000000001"));
    }

    private static String code(Executable call) {
        Throwable thrown = assertThrows(RuntimeException.class, call);
        assertInstanceOf(CodedRefusal.class, thrown, thrown.toString());
        return ((CodedRefusal) thrown).errorCode().code();
    }

    @Test
    void unclearFiltersAreRefusedWithTheirCodes() {
        seedDay();
        IntakeReportService s = service(NY);
        assertEquals("SDV-RPT-002", code(() -> s.intake(TENANT, "2026-11-20", null, null)), "only one end");
        assertEquals("SDV-RPT-002", code(() -> s.intake(TENANT, null, "2026-11-20", null)));
        assertEquals("SDV-RPT-002", code(() -> s.intake(TENANT, "20/11/2026", "2026-11-20", null)), "not YYYY-MM-DD");
        assertEquals("SDV-RPT-002", code(() -> s.intake(TENANT, "2026-11-21", "2026-11-20", null)), "the end before the start");
        assertEquals("SDV-RPT-002", code(() -> s.intake(TENANT, "2026-10-20", "2026-11-20", null)), "32 days");
        assertDoesNotThrow(() -> s.intake(TENANT, "2026-10-21", "2026-11-20", null), "31 days");
        assertEquals("SDV-RPT-003", code(() -> s.intake(TENANT, null, null, "not-a-uuid")));
        assertEquals("SDV-RPT-003", code(() -> s.intake(TENANT, null, null, UUID.randomUUID().toString())), "another tenant's");
        assertEquals("SDV-RPT-002", code(() -> s.export(TENANT, "priya", null, "2026-11-21", "2026-11-20", null)));
        verifyNoInteractions(audit);

        repo.timeZone = "Mars/Olympus_Mons";
        assertEquals("SDV-RPT-004", code(() -> s.intake(TENANT, null, null, null)), "never guessed");
        // Task 0081: a customer's zone that is not a zone refuses the report too; with none at all, UTC.
        repo.timeZone = null;
        assertEquals("SDV-RPT-004", code(() -> service("Eastern").intake(TENANT, null, null, null)), "never guessed");
        assertEquals("NONE", service(null).intake(TENANT, null, null, null).timeZoneSource());
        assertEquals("UTC", service(null).intake(TENANT, null, null, null).timeZone());
    }

    @Test
    void tooManyLinesAreRefused() {
        seedDay();
        repo.overflow = true;
        assertEquals("SDV-RPT-005", code(() -> service(NY).intake(TENANT, null, null, null)));
        assertEquals("SDV-RPT-005", code(() -> service(NY).export(TENANT, "priya", null, null, null, null)));
        verifyNoInteractions(audit);
    }

    /** The repository in memory: the same filters as the SQL (tenant implied, range, sending system, order, limit). */
    static final class FakeReports implements IntakeReportRepository {
        String timeZone;
        String customerZone;
        boolean overflow;
        final List<SendingSystem> systems = new ArrayList<>();
        final List<Item> items = new ArrayList<>();
        final Map<UUID, PrimaryIdentifier> pids = new HashMap<>();
        final Map<UUID, Instant> created = new LinkedHashMap<>();
        final Map<UUID, String> requestIds = new HashMap<>();

        void system(UUID id, String name) { systems.add(new SendingSystem(id, name)); }

        void submission(UUID source, UUID id, String requestId, String at, String contract) {
            created.put(id, Instant.parse(at));
            requestIds.put(id, requestId);
            pids.put(id, new PrimaryIdentifier("contractNumber", contract, contract, "Contract number"));
            items.add(new Item(Kind.DELIVERY, id, source, id, requestId, null, null, Instant.parse(at), null, null));
        }

        void document(UUID source, UUID submission, String sha, Integer pages, String at, String status) {
            items.add(new Item(Kind.DOCUMENT, UUID.randomUUID(), source, submission, requestIds.get(submission), sha, pages,
                    Instant.parse(at), status, null));
        }

        void duplicate(UUID source, UUID submission, String sha, Integer pages, String at) {
            items.add(new Item(Kind.DUPLICATE, UUID.randomUUID(), source, submission, requestIds.get(submission), sha, pages,
                    Instant.parse(at), null, null));
        }

        void refusal(UUID source, UUID submission, String requestId, String code, String at) {
            items.add(new Item(Kind.REFUSED, UUID.randomUUID(), source, submission,
                    submission != null ? requestIds.get(submission) : requestId, null, null, Instant.parse(at), null, code));
        }

        /** Task 0039: an intake request that failed on Vero's side (INTAKE_FAILED). */
        void failure(UUID source, UUID submission, String requestId, String code, String at) {
            items.add(new Item(Kind.FAILED, UUID.randomUUID(), source, submission,
                    submission != null ? requestIds.get(submission) : requestId, null, null, Instant.parse(at), null, code));
        }

        @Override public Optional<String> findTenantTimeZone(UUID tenantId) { return Optional.ofNullable(timeZone); }

        @Override public Optional<String> findCustomerTimeZone(UUID tenantId) { return Optional.ofNullable(customerZone); }

        @Override public List<SendingSystem> findSendingSystems(UUID tenantId) { return List.copyOf(systems); }

        @Override
        public List<Item> findItems(UUID tenantId, UUID sourceId, Instant from, Instant to, int limit) {
            if (overflow) {
                List<Item> many = new ArrayList<>();
                Item one = items.get(0);
                for (int i = 0; i < limit; i++) many.add(one);
                return many;
            }
            return items.stream()
                    .filter(i -> !i.at().isBefore(from) && i.at().isBefore(to))
                    .filter(i -> sourceId == null || sourceId.equals(i.sourceId()))
                    .sorted(Comparator.comparing(Item::at).thenComparing(i -> i.kind().name()))
                    .limit(limit).toList();
        }

        @Override
        public Map<UUID, PrimaryIdentifier> findPrimaryIdentifiers(UUID tenantId, Collection<UUID> submissionIds) {
            Map<UUID, PrimaryIdentifier> out = new HashMap<>();
            for (UUID id : submissionIds) if (pids.containsKey(id)) out.put(id, pids.get(id));
            return out;
        }

        @Override
        public Set<String> findValuesKnownBefore(UUID tenantId, Collection<String> fieldKeys, Collection<String> values, Instant before) {
            Set<String> known = new HashSet<>();
            created.forEach((id, at) -> {
                PrimaryIdentifier p = pids.get(id);
                if (at.isBefore(before) && fieldKeys.contains(p.fieldKey()) && values.contains(p.normalizedValue())) known.add(p.normalizedValue());
            });
            return known;
        }
    }
}
