package com.sdv.platform.application.quarantine;

import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.approvals.ApprovalSettings;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.QuarantineRepository.DocumentFacts;
import com.sdv.platform.application.port.QuarantineRepository.Exposure;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0048: the quarantine rules, with the store in memory and the levels as the guard gives them. Each test names the
 * rule it holds.
 */
class QuarantineServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SOURCE = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID STD = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID CONF = UUID.fromString("40000000-0000-0000-0000-000000000002");
    private static final UUID HIGH = UUID.fromString("40000000-0000-0000-0000-000000000003");
    private static final UUID OLD = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID DELETED = UUID.fromString("40000000-0000-0000-0000-000000000005");
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private static final Clearance STEWARD = new Clearance(true, false, List.of("DOCUMENT_STEWARD"), true);
    private static final Clearance RESTRICTED = new Clearance(true, true, List.of("RESTRICTED_STEWARD"), true);
    private static final Clearance ADMIN = new Clearance(false, false, List.of("BUSINESS_ADMIN"), false);

    private final InMemoryQuarantineRepository store = new InMemoryQuarantineRepository();
    private final DocumentAccessGuard access = mock(DocumentAccessGuard.class);
    private final MetadataFieldDefinitionRepository definitions = mock(MetadataFieldDefinitionRepository.class);
    private final List<AuditEvent> events = new ArrayList<>();
    private final AuditEventPort audit = events::add;
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final MutableClock clock = new MutableClock(NOW);
    private QuarantineService service;

    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(access.effectiveLevels(any(), any())).thenAnswer(inv -> {
            Map<UUID, SensitivityLevel> out = new LinkedHashMap<>();
            for (DocumentAccessGuard.DocumentLevelRef r : inv.<List<DocumentAccessGuard.DocumentLevelRef>>getArgument(1)) {
                out.put(r.documentId(), r.ownLevel());
            }
            return out;
        });
        when(definitions.findCurrent(any(), any(), anyString())).thenReturn(Optional.empty());
        when(definitions.findCurrent(TENANT, SOURCE, "contractNumber")).thenReturn(Optional.of(new MetadataFieldDefinition(
                UUID.randomUUID(), TENANT, SOURCE, "contractNumber", 1, "Contract number", MetadataFieldType.TEXT, true, true,
                true, MetadataNormalization.DIGITS_ONLY, MetadataSearchMode.EXACT, null, true, NOW, null, NOW)));
        service = new QuarantineService(store, access, definitions, audit, ApprovalSettings.defaults(), tx, clock);
        add(STD, SensitivityLevel.STANDARD, "2026-11-02T09:00:00Z", "AVAILABLE", "300000000001");
        add(CONF, SensitivityLevel.CONFIDENTIAL, "2026-11-03T09:00:00Z", "AVAILABLE", "300000000001");
        add(HIGH, SensitivityLevel.HIGHLY_CONFIDENTIAL, "2026-11-04T09:00:00Z", "AVAILABLE", "300000000001");
        add(OLD, SensitivityLevel.STANDARD, "2026-10-01T09:00:00Z", "AVAILABLE", "300000000001");
        add(DELETED, SensitivityLevel.STANDARD, "2026-11-05T09:00:00Z", "EXPIRED_DELETED", "300000000001");
    }

    private void add(UUID id, SensitivityLevel level, String received, String status, String contract) {
        store.add(TENANT, new DocumentFacts(id, SOURCE, UUID.randomUUID(), status, level, Instant.parse(received), 4,
                contract, "Contract number"), Map.of("contractNumber", contract));
    }

    private static QuarantineService.Actor as(String who, Clearance clearance) {
        return new QuarantineService.Actor(TENANT, who, who + " Person", clearance, "corr-" + who);
    }

    private static void refused(String code, Executable call) {
        CodedRefusal refusal = (CodedRefusal) assertThrows(RuntimeException.class, call);
        assertEquals(code, refusal.errorCode().code());
    }

    private QuarantineService.FilterRequest november() {
        return new QuarantineService.FilterRequest("2026-11-01", "2026-11-30", SOURCE.toString(), Map.of("contractNumber", "3000-0000-0001"));
    }

    private long count(String action) {
        return events.stream().filter(e -> e.action().equals(action)).count();
    }

    // ------------------------------------------------------------------ who quarantines

    @Test
    void aStewardQuarantinesLevelsTheyCanOpenAndNoOther() {
        QuarantineService.IncidentView one = service.quarantineDocument(as("steward", STEWARD), CONF.toString(), null,
                "  Wrong delivery  ", "Sent to the wrong branch");
        assertEquals("Wrong delivery", one.name(), "trimmed");
        assertEquals("DOCUMENT", one.scope());
        assertEquals(1, one.documentsInForce());
        assertTrue(store.isQuarantined(TENANT, CONF));

        refused("SDV-QUA-002", () -> service.quarantineDocument(as("steward", STEWARD), HIGH.toString(), null, "Leak", "Suspected leak"));
        assertFalse(store.isQuarantined(TENANT, HIGH), "a Steward can't quarantine Highly Confidential");
        service.quarantineDocument(as("restricted", RESTRICTED), HIGH.toString(), null, "Leak", "Suspected leak");
        assertTrue(store.isQuarantined(TENANT, HIGH), "a Restricted Steward can");

        // Someone without review rights (the web layer refuses them first; the service checks the level too).
        refused("SDV-QUA-002", () -> service.quarantineDocument(as("admin", ADMIN), STD.toString(), null, "No", "Not allowed"));
    }

    @Test
    void aDocumentIsInOneQuarantineAtATimeAndCanJoinAnOpenIncident() {
        QuarantineService.IncidentView incident = service.quarantineDocument(as("steward", STEWARD), STD.toString(), null, "Leak", "Suspected leak");
        refused("SDV-QUA-006", () -> service.quarantineDocument(as("steward", STEWARD), STD.toString(), null, "Again", "Again please"));
        QuarantineService.IncidentView joined = service.quarantineDocument(as("steward", STEWARD), CONF.toString(),
                incident.incidentId().toString(), null, null);
        assertEquals(incident.incidentId(), joined.incidentId());
        assertEquals(2, joined.documentsInForce());
        refused("SDV-QUA-006", () -> service.quarantineDocument(as("steward", STEWARD), DELETED.toString(), null, "Gone", "Deleted one"));
        refused("SDV-QUA-005", () -> service.quarantineDocument(as("steward", STEWARD), UUID.randomUUID().toString(), null, "X", "Unknown"));
        refused("SDV-QUA-005", () -> service.quarantineDocument(as("steward", STEWARD), "not-a-reference", null, "X", "Unknown"));
        refused("SDV-QUA-007", () -> service.quarantineDocument(as("steward", STEWARD), OLD.toString(), UUID.randomUUID().toString(), null, null));
        refused("SDV-QUA-004", () -> service.quarantineDocument(as("steward", STEWARD), OLD.toString(), null, " ", "Reason ok"));
        refused("SDV-QUA-004", () -> service.quarantineDocument(as("steward", STEWARD), OLD.toString(), null, "Name", "no"));
        refused("SDV-QUA-004", () -> service.quarantineDocument(as("steward", STEWARD), OLD.toString(), null, "x".repeat(201), "Reason ok"));

        // Another entity's document is not found, whatever its reference.
        QuarantineService.Actor elsewhere = new QuarantineService.Actor(OTHER_TENANT, "steward", null, STEWARD, "c");
        refused("SDV-QUA-005", () -> service.quarantineDocument(elsewhere, OLD.toString(), null, "Name", "Reason ok"));
    }

    // ------------------------------------------------------------------ by filter

    @Test
    void theFilterCountIsWhatGetsQuarantined() {
        QuarantineService.Actor restricted = as("restricted", RESTRICTED);
        QuarantineService.MatchCount count = service.countMatching(restricted, november());
        assertEquals(3, count.count(), "STD, CONF, HIGH: received in November, same contract; OLD is October, DELETED is gone");

        refused("SDV-QUA-009", () -> service.quarantineByFilter(restricted, november(), 2L, "Leak", "Suspected leak"));
        assertTrue(store.quarantines.isEmpty(), "a count that doesn't match quarantines nothing");

        QuarantineService.IncidentView incident = service.quarantineByFilter(restricted, november(), 3L, "Leak", "Suspected leak");
        assertEquals("FILTER", incident.scope());
        assertEquals(3, incident.documentsInForce());
        assertEquals(3, count(QuarantineService.QUARANTINED_ACTION));
        assertEquals(0, service.countMatching(restricted, november()).count(), "already quarantined ones no longer match");
    }

    @Test
    void theFilterIsCheckedBeforeAnythingIsCounted() {
        QuarantineService.Actor restricted = as("restricted", RESTRICTED);
        refused("SDV-QUA-004", () -> service.countMatching(restricted, new QuarantineService.FilterRequest(null, "2026-11-30", null, null)));
        refused("SDV-QUA-004", () -> service.countMatching(restricted, new QuarantineService.FilterRequest("2026-11-30", "2026-11-01", null, null)));
        refused("SDV-QUA-004", () -> service.countMatching(restricted, new QuarantineService.FilterRequest("1 Nov", "2026-11-30", null, null)));
        refused("SDV-QUA-004", () -> service.countMatching(restricted,
                new QuarantineService.FilterRequest("2026-11-01", "2026-11-30", null, Map.of("contractNumber", "1"))));
        refused("SDV-QUA-004", () -> service.countMatching(restricted,
                new QuarantineService.FilterRequest("2026-11-01", "2026-11-30", SOURCE.toString(), Map.of("originalFilename", "a.pdf"))));
        refused("SDV-QUA-004", () -> service.quarantineByFilter(restricted, november(), null, "Leak", "Suspected leak"));
        // Task 0051: no limit; a count that doesn't match is still refused (nothing is quarantined).
        refused("SDV-QUA-009", () -> service.quarantineByFilter(restricted, november(), 1_000_001L, "Leak", "Suspected leak"));
        assertTrue(store.preparations.isEmpty());
        assertEquals(4, service.countMatching(restricted,
                new QuarantineService.FilterRequest("2026-10-01", "2026-11-30", null, null)).count(), "dates alone");
    }

    @Test
    void aFilterQuarantineNeedsEveryLevelToBeOpenable() {
        refused("SDV-QUA-002", () -> service.quarantineByFilter(as("steward", STEWARD), november(), 3L, "Leak", "Suspected leak"));
        assertTrue(store.quarantines.isEmpty());
    }

    // ------------------------------------------------------------------ review and single release

    @Test
    void reviewersSeeOnlyTheLevelsTheyCanOpenAndReleaseOneDocument() {
        QuarantineService.IncidentView incident = service.quarantineByFilter(as("restricted", RESTRICTED), november(), 3L, "Leak", "Suspected leak");
        QuarantineService.IncidentView seenBySteward = service.incident(as("steward", STEWARD), incident.incidentId().toString());
        assertEquals(2, seenBySteward.documents().size());
        assertEquals(1, seenBySteward.hiddenCount(), "the Highly Confidential one is counted, not shown");
        assertTrue(seenBySteward.documents().stream().noneMatch(d -> d.documentId().equals(HIGH)));

        refused("SDV-QUA-002", () -> service.releaseDocument(as("steward", STEWARD), HIGH.toString(), "Checked, fine"));
        refused("SDV-QUA-004", () -> service.releaseDocument(as("steward", STEWARD), STD.toString(), ""));
        QuarantineService.QuarantinedDocumentView released = service.releaseDocument(as("steward", STEWARD), STD.toString(), "Checked, fine");
        assertFalse(released.inForce());
        assertFalse(store.isQuarantined(TENANT, STD));
        refused("SDV-QUA-011", () -> service.releaseDocument(as("steward", STEWARD), STD.toString(), "Checked again"));

        service.releaseDocument(as("steward", STEWARD), CONF.toString(), "Checked, fine");
        assertEquals("OPEN", store.incidents.get(incident.incidentId()).status(), "HIGH is still in force");
        service.releaseDocument(as("restricted", RESTRICTED), HIGH.toString(), "Checked, fine");
        assertEquals("RELEASED", store.incidents.get(incident.incidentId()).status(), "closed when nothing is left");
        assertEquals(1, count(QuarantineService.INCIDENT_CLOSED_ACTION));
        refused("SDV-QUA-008", () -> service.quarantineDocument(as("steward", STEWARD), OLD.toString(),
                incident.incidentId().toString(), null, null));
    }

    // ------------------------------------------------------------------ whole-incident release

    @Test
    void aWholeIncidentIsReleasedByRequestAndAnotherPersonsApproval() {
        QuarantineService.IncidentView incident = service.quarantineDocument(as("steward", STEWARD), STD.toString(), null, "Leak", "Suspected leak");
        service.quarantineDocument(as("steward", STEWARD), CONF.toString(), incident.incidentId().toString(), null, null);

        service.requestIncidentRelease(as("steward", STEWARD), incident.incidentId().toString(), "Investigation closed");
        refused("SDV-QUA-010", () -> service.requestIncidentRelease(as("steward2", STEWARD), incident.incidentId().toString(), "Again"));

        List<QuarantineService.ReleaseRequestView> pending = service.pendingReleaseRequests(as("admin", ADMIN));
        assertEquals(1, pending.size());
        QuarantineService.ReleaseRequestView view = pending.getFirst();
        assertEquals("Leak", view.incidentName());
        assertEquals(2, view.documentCount());
        assertEquals(1, view.standard());
        assertEquals(1, view.confidential());
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), view.firstReceivedAt());
        assertFalse(view.toString().contains("Investigation closed"), "the Business Admin's view carries no reason");
        assertFalse(view.toString().contains("Suspected leak"));

        // Nobody decides their own request (a Steward who is also asked can't approve their own).
        refused("SDV-QUA-010", () -> service.decide(as("steward", ADMIN), view.requestId().toString(), true));

        QuarantineService.ReleaseRequestView approved = service.decide(as("admin", ADMIN), view.requestId().toString(), true);
        assertEquals("APPROVED", approved.status());
        assertFalse(store.isQuarantined(TENANT, STD));
        assertFalse(store.isQuarantined(TENANT, CONF));
        assertEquals("RELEASED", store.incidents.get(incident.incidentId()).status());
        assertTrue(store.quarantines.values().stream().allMatch(r -> view.requestId().equals(r.requestId)),
                "each release names the request that approved it");
        refused("SDV-QUA-010", () -> service.decide(as("admin2", ADMIN), view.requestId().toString(), false));
        assertEquals(1, count(QuarantineService.RELEASE_APPROVED_ACTION));
        assertEquals(2, count(QuarantineService.RELEASED_ACTION));
    }

    @Test
    void aStewardCantAskToReleaseAnIncidentWithLevelsTheyCantOpen() {
        QuarantineService.IncidentView incident = service.quarantineByFilter(as("restricted", RESTRICTED), november(), 3L, "Leak", "Suspected leak");
        refused("SDV-QUA-002", () -> service.requestIncidentRelease(as("steward", STEWARD), incident.incidentId().toString(), "Done"));
        service.requestIncidentRelease(as("restricted", RESTRICTED), incident.incidentId().toString(), "Done here");
    }

    @Test
    void aDeclinedRequestReleasesNothingAndAnExpiredOneCantBeDecided() {
        QuarantineService.IncidentView incident = service.quarantineDocument(as("steward", STEWARD), STD.toString(), null, "Leak", "Suspected leak");
        service.requestIncidentRelease(as("steward", STEWARD), incident.incidentId().toString(), "Investigation closed");
        UUID first = service.pendingReleaseRequests(as("admin", ADMIN)).getFirst().requestId();
        assertEquals("DECLINED", service.decide(as("admin", ADMIN), first.toString(), false).status());
        assertTrue(store.isQuarantined(TENANT, STD));
        assertEquals(1, count(QuarantineService.RELEASE_DECLINED_ACTION));

        service.requestIncidentRelease(as("steward", STEWARD), incident.incidentId().toString(), "Asking again");
        UUID second = service.pendingReleaseRequests(as("admin", ADMIN)).getFirst().requestId();
        clock.now = NOW.plus(Duration.ofDays(7)).plusSeconds(1);
        assertTrue(service.pendingReleaseRequests(as("admin", ADMIN)).isEmpty(), "expired after 7 days");
        assertEquals(1, count(QuarantineService.RELEASE_EXPIRED_ACTION));
        refused("SDV-QUA-010", () -> service.decide(as("admin", ADMIN), second.toString(), true));
        assertTrue(store.isQuarantined(TENANT, STD));
        refused("SDV-QUA-010", () -> service.decide(as("admin", ADMIN), UUID.randomUUID().toString(), true));
    }

    @Test
    void aWaitingRequestEndsWhenItsIncidentIsReleasedDocumentByDocument() {
        QuarantineService.IncidentView incident = service.quarantineDocument(as("steward", STEWARD), STD.toString(), null, "Leak", "Suspected leak");
        service.requestIncidentRelease(as("steward", STEWARD), incident.incidentId().toString(), "Investigation closed");
        service.releaseDocument(as("steward2", STEWARD), STD.toString(), "Checked, fine");
        assertTrue(service.pendingReleaseRequests(as("admin", ADMIN)).isEmpty());
        assertEquals(1, count(QuarantineService.RELEASE_EXPIRED_ACTION));
    }

    // ------------------------------------------------------------------ exposure

    @Test
    void theExposureReportListsWhoOpenedPrintedOrDownloadedBeforeTheQuarantineAndNeverContent() throws Exception {
        store.exposure.add(new Exposure(STD, "u1", "Uma User", "DOCUMENT_VIEW_OPENED", NOW.minusSeconds(7200)));
        store.exposure.add(new Exposure(STD, "u2", "Ola Other", "DOCUMENT_PRINTED", NOW.minusSeconds(3600)));
        store.exposure.add(new Exposure(STD, "u3", "Late Person", "DOCUMENT_DOWNLOADED", NOW.plusSeconds(60)));
        store.exposure.add(new Exposure(HIGH, "u4", "Hidden Person", "DOCUMENT_VIEW_OPENED", NOW.minusSeconds(60)));
        QuarantineService.IncidentView incident = service.quarantineByFilter(as("restricted", RESTRICTED), november(), 3L,
                "Leak", "Suspected leak: secret reason");

        QuarantineService.ExposureReport bySteward = service.exposure(as("steward", STEWARD), incident.incidentId().toString());
        assertEquals(1, bySteward.standard());
        assertEquals(1, bySteward.confidential());
        assertEquals(0, bySteward.highlyConfidential(), "a Steward sees only the levels they can open");
        assertEquals(1, bySteward.hiddenCount());
        QuarantineService.ExposureDocument std = bySteward.documents().stream().filter(d -> d.documentId().equals(STD)).findFirst().orElseThrow();
        assertEquals("300000000001", std.primaryIdentifier());
        assertEquals(4, std.pageCount());
        assertEquals(List.of("Opened", "Printed"), std.exposure().stream().map(QuarantineService.ExposureEntry::what).toList(),
                "only what happened before the quarantine");

        QuarantineService.ExposureReport all = service.exposure(as("restricted", RESTRICTED), incident.incidentId().toString());
        assertEquals(1, all.highlyConfidential());
        assertEquals(0, all.hiddenCount());

        QuarantineService.Export export = service.exportExposure(as("restricted", RESTRICTED), incident.incidentId().toString());
        String csv = new String(export.csv(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("﻿" + QuarantineService.CSV_HEADER));
        assertTrue(csv.contains("Uma User") && csv.contains("Hidden Person"));
        assertFalse(csv.contains("Late Person"));
        assertFalse(csv.contains("secret reason"), "no reason in the report");
        assertFalse(csv.contains(".pdf"), "no file name");
        assertEquals(4, export.rowCount(), "STD twice, CONF once (nothing happened), HIGH once");
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(export.csv())), export.fingerprint());
        assertTrue(export.filename().startsWith("vero-quarantine-exposure-2026-11-20-"));
        AuditEvent exported = events.stream().filter(e -> e.action().equals(QuarantineService.EXPOSURE_EXPORTED_ACTION)).findFirst().orElseThrow();
        assertTrue(exported.safeDetailsJson().contains(export.fingerprint()));
        assertTrue(exported.safeDetailsJson().contains(export.reference().toString()));
    }

    // ------------------------------------------------------------------ task 0051: no limit

    /** {@code n} Standard documents received in November (one a minute from 2 Nov), same contract. */
    private List<UUID> many(int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID id = new UUID(0x5100_0000_0000_0000L, i + 1);
            add(id, SensitivityLevel.STANDARD, Instant.parse("2026-11-02T00:00:00Z").plusSeconds(60L * i).toString(),
                    "AVAILABLE", "300000000001");
            ids.add(id);
        }
        return ids;
    }

    @Test
    void everythingMatchingIsQuarantinedAtOnceAndTheRowsFollowInBatches() {
        List<UUID> ids = many(QuarantineService.BATCH_SIZE + 500);
        QuarantineService.Actor restricted = as("restricted", RESTRICTED);
        long expected = ids.size() + 3L; // and STD, CONF, HIGH
        assertEquals(expected, service.countMatching(restricted, november()).count(), "no limit on the count");

        QuarantineService.IncidentView incident = service.quarantineByFilter(restricted, november(), expected, "Leak", "Suspected leak");
        // One batch is written at once; every matching document is quarantined already, with or without its row.
        assertTrue(incident.preparing(), "being prepared");
        assertEquals(QuarantineService.BATCH_SIZE, incident.preparedCount());
        assertEquals(expected, incident.expectedCount());
        assertEquals(QuarantineService.BATCH_SIZE, store.quarantines.size());
        assertTrue(ids.stream().allMatch(id -> store.isQuarantined(TENANT, id)), "blocked before its row exists");
        assertEquals(ids.size(), store.quarantined(TENANT, ids).size(), "record views see them all as quarantined");
        assertFalse(store.isQuarantined(TENANT, OLD), "October is outside the filter");
        assertEquals(0, service.countMatching(restricted, november()).count(), "nothing left to quarantine");

        // A whole release waits until the incident is prepared; the incident never closes while it is prepared.
        refused("SDV-QUA-010", () -> service.requestIncidentRelease(restricted, incident.incidentId().toString(), "Done here"));

        assertTrue(service.prepare(10) >= 1);
        QuarantineService.IncidentView ready = service.incident(restricted, incident.incidentId().toString());
        assertFalse(ready.preparing());
        assertEquals(expected, ready.preparedCount());
        assertEquals(expected, ready.documentsInForce());
        assertEquals(expected, store.quarantines.size(), "each document has one row");
        assertEquals(QuarantineService.REVIEW_LIMIT, ready.documents().size(), "the list is limited; the counts are not");
        assertEquals(expected, count(QuarantineService.QUARANTINED_ACTION), "each row audited once");
        assertEquals(0, service.prepare(10), "nothing left to do");
    }

    @Test
    void aDocumentReceivedAfterTheCutOffIsNotCaught() {
        QuarantineService.Actor restricted = as("restricted", RESTRICTED);
        service.quarantineByFilter(restricted, november(), 3L, "Leak", "Suspected leak");
        // Received later in November: it matches the filter but arrived after the confirmation.
        UUID late = UUID.fromString("40000000-0000-0000-0000-000000000099");
        add(late, SensitivityLevel.STANDARD, "2026-11-25T09:00:00Z", "AVAILABLE", "300000000001");
        assertFalse(store.isQuarantined(TENANT, late));
        service.prepare(10);
        assertFalse(store.isQuarantined(TENANT, late), "the job doesn't write it either");
        assertEquals(1, service.countMatching(restricted, november()).count(), "it can be quarantined on its own");
    }

    @Test
    void theJobContinuesAfterARestartAndABatchRunTwiceWritesNothingTwice() {
        List<UUID> ids = many(2 * QuarantineService.BATCH_SIZE + 10);
        QuarantineService.Actor restricted = as("restricted", RESTRICTED);
        long expected = ids.size() + 3L;
        UUID incident = service.quarantineByFilter(restricted, november(), expected, "Leak", "Suspected leak").incidentId();
        var p = store.findPreparation(TENANT, incident).orElseThrow();

        // The same batch again (as if its commit had been lost after writing): nothing more is written.
        var again = store.prepareBatch(new com.sdv.platform.application.port.QuarantineRepository.Preparation(p.incidentId(),
                p.tenantId(), p.filter(), p.cutoffReceivedAt(), p.expectedCount(), 0, null, null, null, p.createdBy(),
                p.createdByName(), p.createdAt(), p.status()), QuarantineService.BATCH_SIZE, "x", "x", NOW);
        assertEquals(QuarantineService.BATCH_SIZE, again.scanned());
        assertTrue(again.placed().isEmpty(), "every one already has its row");

        // A restart: a new service on the same store continues after the last batch.
        QuarantineService restarted = new QuarantineService(store, access, definitions, audit, ApprovalSettings.defaults(), tx, clock);
        while (restarted.prepare(1) > 0) { /* one batch per run */ }
        assertEquals(expected, store.quarantines.size());
        assertEquals(expected, store.quarantines.values().stream().map(r -> r.documentId).distinct().count(), "no duplicates");
        assertFalse(store.findPreparation(TENANT, incident).orElseThrow().preparing());
    }

    @Test
    void aSingleReleaseFreesOneDocumentAndAWholeReleaseEndsTheFilter() {
        List<UUID> ids = many(QuarantineService.BATCH_SIZE + 5);
        QuarantineService.Actor restricted = as("restricted", RESTRICTED);
        long expected = ids.size() + 3L;
        UUID incident = service.quarantineByFilter(restricted, november(), expected, "Leak", "Suspected leak").incidentId();

        // A document without its row yet: released, it is free even though the filter still matches it.
        UUID noRowYet = ids.getLast();
        assertTrue(store.findActive(TENANT, noRowYet).isEmpty());
        service.releaseDocument(as("steward", STEWARD), noRowYet.toString(), "Checked, fine");
        assertFalse(store.isQuarantined(TENANT, noRowYet));
        // One with its row.
        service.releaseDocument(as("steward", STEWARD), ids.getFirst().toString(), "Checked, fine");
        assertFalse(store.isQuarantined(TENANT, ids.getFirst()));
        assertEquals("OPEN", store.incidents.get(incident).status());

        service.prepare(10);
        assertFalse(store.isQuarantined(TENANT, noRowYet), "the job doesn't quarantine it again");
        assertFalse(store.isQuarantined(TENANT, ids.getFirst()));

        // The whole release: every row in force, and the filter no longer blocks anything.
        service.requestIncidentRelease(restricted, incident.toString(), "Investigation closed");
        UUID request = service.pendingReleaseRequests(as("admin", ADMIN)).getFirst().requestId();
        assertEquals(expected - 2, service.pendingReleaseRequests(as("admin", ADMIN)).getFirst().documentCount());
        service.decide(as("admin", ADMIN), request.toString(), true);
        assertEquals("RELEASED", store.incidents.get(incident).status());
        assertTrue(ids.stream().noneMatch(id -> store.isQuarantined(TENANT, id)));
        assertFalse(store.isQuarantined(TENANT, HIGH));
        assertEquals(expected, count(QuarantineService.RELEASED_ACTION), "each release audited: two single, the rest together");
    }

    @Test
    void releasingEveryWrittenRowDoesNotEndAnIncidentStillBeingPrepared() {
        List<UUID> ids = many(QuarantineService.BATCH_SIZE + 1);
        QuarantineService.Actor restricted = as("restricted", RESTRICTED);
        UUID incident = service.quarantineByFilter(restricted, november(), ids.size() + 3L, "Leak", "Suspected leak").incidentId();
        for (var r : List.copyOf(store.quarantines.values())) {
            service.releaseDocument(restricted, r.documentId.toString(), "Checked, fine");
        }
        assertEquals("OPEN", store.incidents.get(incident).status(), "the documents not yet written are still quarantined");
        assertTrue(store.isQuarantined(TENANT, ids.getLast()));
        service.prepare(10);
        assertEquals("OPEN", store.incidents.get(incident).status(), "its last rows are in force");
    }

    // ------------------------------------------------------------------ audit

    @Test
    void everyChangeIsAuditedWithoutItsReason() {
        QuarantineService.IncidentView incident = service.quarantineDocument(as("steward", STEWARD), STD.toString(), null,
                "Leak", "Reason that must not be in the audit");
        service.requestIncidentRelease(as("steward", STEWARD), incident.incidentId().toString(), "Request reason not in audit");
        service.releaseDocument(as("steward2", STEWARD), STD.toString(), "Release reason not in audit");
        assertEquals(1, count(QuarantineService.INCIDENT_CREATED_ACTION));
        assertEquals(1, count(QuarantineService.QUARANTINED_ACTION));
        assertEquals(1, count(QuarantineService.RELEASE_REQUESTED_ACTION));
        assertEquals(1, count(QuarantineService.RELEASED_ACTION));
        assertTrue(events.stream().noneMatch(e -> e.safeDetailsJson().contains("audit")), "no reason in any event");
        AuditEvent placed = events.stream().filter(e -> e.action().equals(QuarantineService.QUARANTINED_ACTION)).findFirst().orElseThrow();
        assertEquals("DOCUMENT", placed.targetType());
        assertEquals(STD.toString(), placed.targetId());
        assertTrue(placed.safeDetailsJson().contains(incident.incidentId().toString()), "the incident ID is recorded");
        assertEquals("corr-steward", placed.correlationId());
    }
}
