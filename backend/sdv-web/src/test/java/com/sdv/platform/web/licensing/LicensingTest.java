package com.sdv.platform.web.licensing;

import com.documentplatform.licensing.*;
import com.documentplatform.licensing.usage.*;
import com.sdv.platform.application.identity.DevelopmentLicences;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.web.foundation.ErrorReplies;
import com.sdv.platform.web.ingestion.DocumentIntakeController;
import com.sdv.platform.web.ingestion.SubmissionController;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.security.KeyPair;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0075: what the licence stops, in every state, and the counts that go into the ledger. */
class LicensingTest {
    static final KeyPair KEY = DevelopmentLicences.newKey();
    static final String SLOT = "ENT-LIC-TEST-0000001";

    static final class Licences implements LicenceStore {
        final Map<Slot, Stored> rows = new EnumMap<>(Slot.class);
        @Override public Optional<Stored> get(Slot slot) { return Optional.ofNullable(rows.get(slot)); }
        @Override public void put(Slot slot, Stored stored) { rows.put(slot, stored); }
        @Override public void remove(Slot slot) { rows.remove(slot); }
        @Override public long highestVersion(Slot slot) { return 0; }
        @Override public void promoteWaiting(String id) { }
    }

    static final class Seen implements LastSeenStore {
        Instant last;
        @Override public Optional<Instant> lastSeen() { return Optional.ofNullable(last); }
        @Override public void advanceTo(Instant t) { if (last == null || t.isAfter(last)) last = t; }
        @Override public void resetTo(Instant t, String id) { last = t; }
        @Override public boolean resetApplied(String id) { return false; }
    }

    static final class Ledger implements UsageLedger {
        final List<UsageRecord> records = new ArrayList<>();
        @Override public UsageRecord append(UsageEntry e) {
            UsageRecord r = UsageChain.link(records.isEmpty() ? null : records.getLast(), e);
            records.add(r);
            return r;
        }
        @Override public List<UsageRecord> records() { return List.copyOf(records); }
        @Override public Optional<ChainHead> head() { return records.isEmpty() ? Optional.empty() : Optional.of(ChainHead.of(records.getLast())); }
        @Override public Snapshot snapshot() { return new Snapshot(records(), head()); }
    }

    static LicenceService service(LocalDate today, String product, Licences store) {
        Licence l = new Licence("LIC-LIC-TEST-0000001", 1, "Test", "TEN-LIC-TEST-0000001", List.of(product),
                List.of(new EntitySlot(SLOT, List.of(new Allowance(product, AllowanceType.STORAGE, 10)))), LicenceType.SUBSCRIPTION, LicenceKind.PRODUCTION,
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31), null, Deployment.ON_PREM, "test-1", Instant.parse("2026-01-01T00:00:00Z"));
        Instant now = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        store.put(LicenceStore.Slot.CURRENT, LicenceStore.Stored.of(DevelopmentLicences.sign(l, KEY.getPrivate()), l, now));
        return new LicenceService(store, new LicenceVerifier(TrustedKeys.of(Map.of("test-1", KEY.getPublic()))),
                new ClockGuard(Clock.fixed(now, ZoneOffset.UTC), new Seen()), new Ledger(), "SDV", "TEN-LIC-TEST-0000001");
    }

    private static Object bean(Class<?> c) {
        return c == SubmissionController.class ? new SubmissionController(null, null) : new DocumentIntakeController(null, null);
    }

    static String answer(LicenceService licence, Class<?> controller, String method) {
        Method m = Arrays.stream(controller.getDeclaredMethods()).filter(x -> x.getName().equals(method)).findFirst().orElseThrow();
        try {
            new LicenceGate(licence).preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new HandlerMethod(bean(controller), m));
            return "taken";
        } catch (IntakeStopped ex) {
            ErrorReplies.Reply reply = ErrorReplies.failure(ex);
            assertEquals(503, reply.status().value());
            return reply.code();
        }
    }

    @Test
    void everyState() {
        Map<LocalDate, String> expected = new LinkedHashMap<>();
        expected.put(LocalDate.of(2026, 12, 31), "NOT_STARTED SDV-LIC-001");
        expected.put(LocalDate.of(2027, 1, 1), "VALID taken");
        expected.put(LocalDate.of(2027, 12, 20), "EXPIRING taken");
        expected.put(LocalDate.of(2027, 12, 31), "EXPIRING taken");
        expected.put(LocalDate.of(2028, 1, 1), "EXPIRED_IN_GRACE taken");
        expected.put(LocalDate.of(2028, 1, 30), "EXPIRED_IN_GRACE taken");
        expected.put(LocalDate.of(2028, 1, 31), "EXPIRED_GRACE_OVER SDV-LIC-001");
        expected.forEach((day, what) -> {
            LicenceService licence = service(day, "SDV", new Licences());
            for (String[] m : new String[][]{{"s", "create"}, {"d", "upload"}, {"d", "retryFinalization"}}) {
                assertEquals(what, licence.status().state() + " " + answer(licence, m[0].equals("s") ? SubmissionController.class : DocumentIntakeController.class, m[1]), day + " " + m[1]);
            }
            // Status queries of a sending system go on in every state.
            assertEquals("taken", answer(licence, SubmissionController.class, "get"));
            assertEquals("taken", answer(licence, DocumentIntakeController.class, "list"));
        });
    }

    @Test
    void aLicenceWithoutVeroStopsIntakeAndAChangedOneToo() {
        assertEquals("SDV-LIC-001", answer(service(LocalDate.of(2027, 6, 1), "OTHER", new Licences()), SubmissionController.class, "create"));
        Licences store = new Licences();
        LicenceService licence = service(LocalDate.of(2027, 6, 1), "SDV", store);
        LicenceStore.Stored s = store.rows.get(LicenceStore.Slot.CURRENT);
        store.rows.put(LicenceStore.Slot.CURRENT, new LicenceStore.Stored(s.file().replace("\"version\":1", "\"version\":9"), s.licenceId(), s.version(), s.tenantId(), s.startsOn(), s.storedAt()));
        assertEquals("SDV-LIC-002", answer(licence, SubmissionController.class, "create"));
        store.rows.clear();
        assertEquals("SDV-LIC-002", answer(licence, SubmissionController.class, "create"));
        assertEquals("taken", answer(licence, SubmissionController.class, "get"));
    }

    /** A new intake address must be marked too: every POST of the two controllers is. */
    @Test
    void everyIntakePostIsMarked() {
        int posts = 0;
        for (Class<?> c : List.of(SubmissionController.class, DocumentIntakeController.class)) {
            for (Method m : c.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(m, PostMapping.class)) {
                    posts++;
                    assertTrue(m.isAnnotationPresent(LicensedIntake.class), m.getName());
                } else assertFalse(m.isAnnotationPresent(LicensedIntake.class), m.getName());
            }
        }
        assertEquals(3, posts);
    }

    static final class Moving extends Clock {
        Instant now = Instant.parse("2027-03-30T09:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void seats() {
        Moving clock = new Moving();
        SeatMeter meter = new SeatMeter(clock);
        UUID e = UUID.randomUUID();
        meter.seen(e, "a", Set.of(SdvRole.BUSINESS_USER));
        meter.seen(e, "b", Set.of(SdvRole.DOCUMENT_STEWARD));
        meter.seen(e, "b", Set.of(SdvRole.DOCUMENT_STEWARD));
        assertEquals(2, meter.counts().get(e).concurrentPeak());
        assertEquals(Map.of(new SeatMeter.Month(e, YearMonth.of(2027, 3)), Set.of("b")), meter.takePending(), "a business user is no named seat");
        clock.now = clock.now.plus(Duration.ofMinutes(16));
        meter.seen(e, "c", Set.of(SdvRole.BUSINESS_ADMIN));
        assertEquals(2, meter.counts().get(e).concurrentPeak(), "the day's peak stays");
        assertEquals(Set.of("c"), meter.takePending().get(new SeatMeter.Month(e, YearMonth.of(2027, 3))), "each once");
        meter.giveBack(new SeatMeter.Month(e, YearMonth.of(2027, 3)), Set.of("c"));
        clock.now = Instant.parse("2027-04-01T09:00:00Z");
        meter.seen(e, "a", Set.of(SdvRole.BUSINESS_USER));
        assertEquals(1, meter.counts().get(e).concurrentPeak(), "a new day");
        meter.seen(e, "b", Set.of(SdvRole.DOCUMENT_STEWARD));
        assertEquals(Map.of(new SeatMeter.Month(e, YearMonth.of(2027, 3)), Set.of("c"), new SeatMeter.Month(e, YearMonth.of(2027, 4)), Set.of("b")), meter.takePending(), "a new month; what was not written yet stays the old one's");
    }

    @Test
    void theLedgerGetsNumbersWhenTheyGrow() {
        List<String> rows = new ArrayList<>();
        UsageJob.Ledger ledger = (slot, type, month, amount) -> rows.add(slot + " " + type + " " + amount);
        YearMonth march = YearMonth.of(2027, 3);
        SeatMeter.Counts first = new SeatMeter.Counts(LocalDate.of(2027, 3, 1), 2), same = first, more = new SeatMeter.Counts(LocalDate.of(2027, 3, 1), 3);
        UsageJob.seats(SLOT, first, null, march, ledger);
        UsageJob.seats(SLOT, same, first, march, ledger);
        UsageJob.seats(SLOT, more, first, march, ledger);
        UsageJob.seats(SLOT, new SeatMeter.Counts(LocalDate.of(2027, 2, 28), 9), null, march, ledger);
        assertEquals(List.of(SLOT + " CONCURRENT_SEATS 2", SLOT + " CONCURRENT_SEATS 3"), rows);
        assertEquals(0, UsageJob.gigabytes(0));
        assertEquals(1, UsageJob.gigabytes(1));
        assertEquals(1, UsageJob.gigabytes(1_000_000_000L));
        assertEquals(2, UsageJob.gigabytes(1_000_000_001L));
    }

    @Test
    void overTheAllowanceIsRecordedAndNothingIsRefused() {
        LicenceService licence = service(LocalDate.of(2027, 6, 1), "SDV", new Licences());
        assertEquals(UsageLevel.OVER_LIMIT, licence.recordUsage(SLOT, AllowanceType.STORAGE, 11, true).level());
        assertEquals("taken", answer(licence, SubmissionController.class, "create"));
        String file = UsageExport.create(licence.status().licence(), licence.ledger(), YearMonth.of(2027, 6), YearMonth.of(2027, 6), ExportAnchor.first(), Instant.now()).text();
        assertTrue(file.contains(SLOT));
        assertTrue(UsageExportVerifier.verify(file, ExportAnchor.first()).ok());
        assertEquals("SDV-LIC-001", SdvErrorCode.LIC_001.code());
    }
}
