package com.sdv.platform.application.approvals;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.port.AccessRequestRepository;
import com.sdv.platform.application.sensitivity.SensitivityLevelService;
import com.sdv.platform.application.sensitivity.SensitivityLevelService.LevelChange;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.List;

import static com.sdv.platform.application.approvals.ApprovalsFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0028, section 2: raise applies at once and only upwards; lowering is a request until a Business Admin approves
 * it; a Steward asks to lower only from Confidential; the same rules for the record level. Every change is audited
 * with who, when, from, to and the reason.
 */
class SensitivityLevelChangeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ApprovalsFixture w = new ApprovalsFixture();

    private static String code(Executable call) {
        Throwable thrown = assertThrows(RuntimeException.class, call);
        assertInstanceOf(CodedRefusal.class, thrown, thrown.toString());
        return ((CodedRefusal) thrown).errorCode().code();
    }

    private static JsonNode details(AuditEvent e) throws Exception {
        return JSON.readTree(e.safeDetailsJson());
    }

    @Test
    void aRaiseAppliesAtOnceOnlyUpwardsAndIsAudited() throws Exception {
        LevelChange raised = w.levels.raiseDocument(TENANT, STEWARD_ID, "Sam Steward", STEWARD, S1.toString(),
                "CONFIDENTIAL", "  Contains salary details  ", "corr-1");
        assertEquals(LevelChange.Outcome.APPLIED, raised.outcome());
        assertEquals(SensitivityLevel.CONFIDENTIAL, raised.level());
        assertTrue(raised.canStillOpen());
        assertEquals(SensitivityLevel.CONFIDENTIAL, w.documents.get(S1).sensitivityLevel(), "applied at once");

        AuditEvent event = w.last("DOCUMENT_SENSITIVITY_RAISED");
        assertEquals(STEWARD_ID, event.actorId(), "who");
        assertEquals("Sam Steward", event.actorDisplayName());
        assertEquals(START, event.occurredAt(), "when");
        assertEquals(S1.toString(), event.targetId());
        assertEquals("STANDARD", details(event).get("from").asString(), "from");
        assertEquals("CONFIDENTIAL", details(event).get("to").asString(), "to");
        // Task 0029 (0.4): the reason is in the level-change history, not in the audit; the event names the row.
        assertFalse(event.safeDetailsJson().contains("salary"), "no reason in the audit");
        assertEquals("DOCUMENT", details(event).get("scope").asString(), "scope");
        var history = w.requests.changes.getLast();
        assertEquals(history.changeId().toString(), details(event).get("levelChangeId").asString(), "names its history row");
        assertEquals("Contains salary details", history.reason(), "the reason, trimmed");
        assertEquals(AccessRequestRepository.LevelChange.Direction.RAISED, history.direction());
        assertEquals(SensitivityLevel.STANDARD, history.from());
        assertEquals(SensitivityLevel.CONFIDENTIAL, history.to());
        assertEquals(STEWARD_ID, history.changedBy());
        assertEquals("Sam Steward", history.changedByName());
        assertEquals(START, history.changedAt());
        assertNull(history.accessRequestId());
        assertEquals(AuditOutcome.SUCCESS, event.outcome());

        // Only higher than now: the same level or a lower one is refused, and nothing changes.
        int before = w.events.size();
        assertEquals("SDV-SEN-013", code(() -> w.levels.raiseDocument(TENANT, STEWARD_ID, null, STEWARD, S1.toString(),
                "CONFIDENTIAL", "Again", "c")));
        assertEquals("SDV-SEN-013", code(() -> w.levels.raiseDocument(TENANT, STEWARD_ID, null, STEWARD, S1.toString(),
                "STANDARD", "Lower by raise", "c")));
        assertEquals(SensitivityLevel.CONFIDENTIAL, w.documents.get(S1).sensitivityLevel());
        assertEquals(before, w.events.size(), "a refused raise changes nothing and writes no change event");
    }

    @Test
    void aStewardMayRaiseToHighlyConfidentialAndThenCanNoLongerOpenIt() {
        LevelChange raised = w.levels.raiseDocument(TENANT, STEWARD_ID, null, STEWARD, C1.toString(),
                "HIGHLY_CONFIDENTIAL", "Board material", "c");
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, raised.level());
        assertFalse(raised.canStillOpen(), "the Steward is told they can no longer open it");
        assertEquals("SDV-SEN-008", code(() -> w.guard.requireOpenable(w.documents.get(C1), STEWARD,
                DocumentAccessGuard.Attempt.OPEN, STEWARD_ID, "c")), "and opening it is now refused");
        // Nor can they raise or lower it any more: they can't open it.
        assertEquals("SDV-SEN-008", code(() -> w.levels.requestLowerDocument(TENANT, STEWARD_ID, null, STEWARD,
                C1.toString(), "CONFIDENTIAL", "Undo", "c")));
    }

    @Test
    void aRaiseIsComparedWithTheEffectiveLevelThroughTheRecord() {
        w.recordLevels.put(recordKey(CONTRACT_A), SensitivityLevel.CONFIDENTIAL);
        // S1 is Standard itself but Confidential through its record: raising it to Confidential is no raise.
        assertEquals("SDV-SEN-013", code(() -> w.levels.raiseDocument(TENANT, STEWARD_ID, null, STEWARD, S1.toString(),
                "CONFIDENTIAL", "Same as the record", "c")));
        LevelChange raised = w.levels.raiseDocument(TENANT, RS, null, RESTRICTED, S1.toString(), "HIGHLY_CONFIDENTIAL",
                "Above the record", "c");
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, raised.level());
    }

    @Test
    void levelChangesAreRefusedWithCodesBeforeAnythingChanges() {
        assertEquals("SDV-SEN-011", code(() -> w.levels.raiseDocument(TENANT, STEWARD_ID, null, STEWARD, S1.toString(),
                "confidential", "Lower case", "c")));
        assertEquals("SDV-SEN-011", code(() -> w.levels.raiseDocument(TENANT, STEWARD_ID, null, STEWARD, S1.toString(),
                null, "Missing", "c")));
        assertEquals("SDV-SEN-012", code(() -> w.levels.raiseDocument(TENANT, STEWARD_ID, null, STEWARD, S1.toString(),
                "CONFIDENTIAL", " ab ", "c")));
        assertEquals("SDV-SEN-012", code(() -> w.levels.raiseDocument(TENANT, STEWARD_ID, null, STEWARD, S1.toString(),
                "CONFIDENTIAL", "x".repeat(501), "c")));
        assertEquals("SDV-SEN-016", code(() -> w.levels.raiseDocument(TENANT, STEWARD_ID, null, STEWARD, "nope",
                "CONFIDENTIAL", "Reason", "c")));
        assertEquals("SDV-SEN-016", code(() -> w.levels.raiseDocument(OTHER_TENANT, STEWARD_ID, null, STEWARD,
                S1.toString(), "CONFIDENTIAL", "Reason", "c")), "another tenant's document is unknown");
        // A person who can't open the document is refused as restricted, and that is audited.
        assertEquals("SDV-SEN-008", code(() -> w.levels.raiseDocument(TENANT, "user-1", null, USER, C1.toString(),
                "HIGHLY_CONFIDENTIAL", "Reason", "c")));
        AuditEvent refused = w.last(DocumentAccessGuard.REFUSED_ACTION);
        assertEquals("user-1", refused.actorId());
        assertTrue(refused.safeDetailsJson().contains("\"attempt\":\"CHANGE_LEVEL\""), refused.safeDetailsJson());
        w.withStatus(S2, DocumentStatus.PROCESSING);
        assertEquals("SDV-SEN-017", code(() -> w.levels.raiseDocument(TENANT, STEWARD_ID, null, STEWARD, S2.toString(),
                "CONFIDENTIAL", "Reason", "c")));
        assertTrue(w.events("DOCUMENT_SENSITIVITY_RAISED").isEmpty());
        assertEquals(SensitivityLevel.STANDARD, w.documents.get(S1).sensitivityLevel());
    }

    @Test
    void loweringIsOnlyARequestUntilABusinessAdminApprovesIt() throws Exception {
        LevelChange asked = w.levels.requestLowerDocument(TENANT, STEWARD_ID, "Sam Steward", STEWARD, C1.toString(),
                "STANDARD", "Was marked by mistake", "corr-2");
        assertEquals(LevelChange.Outcome.REQUESTED, asked.outcome());
        assertNotNull(asked.requestId());
        assertEquals(START.plus(Duration.ofDays(7)), asked.requestExpiresAt(), "waits 7 days at most");
        assertEquals(SensitivityLevel.CONFIDENTIAL, w.documents.get(C1).sensitivityLevel(), "not lowered yet");
        AuditEvent created = w.last("ACCESS_REQUEST_CREATED");
        assertEquals(STEWARD_ID, created.actorId());
        assertEquals("LOWER_LEVEL", details(created).get("kind").asString());
        assertEquals("CONFIDENTIAL", details(created).get("level").asString());
        assertEquals("STANDARD", details(created).get("requestedLevel").asString());
        assertFalse(created.safeDetailsJson().contains("mistake"), "the request's reason stays in access_requests");

        assertEquals("SDV-APR-004", code(() -> w.levels.requestLowerDocument(TENANT, STEWARD_ID, null, STEWARD,
                C1.toString(), "STANDARD", "Twice", "c")), "one waiting request per person and document");
        assertEquals("SDV-SEN-014", code(() -> w.levels.requestLowerDocument(TENANT, STEWARD_ID, null, STEWARD,
                S1.toString(), "STANDARD", "Already Standard", "c")));
        assertEquals("SDV-SEN-014", code(() -> w.levels.requestLowerDocument(TENANT, RS, null, RESTRICTED,
                C1.toString(), "HIGHLY_CONFIDENTIAL", "Up is not down", "c")));

        // The Business Admin approves: now it applies, audited with who, when, from and to; the reason is in the history.
        w.clock.advance(Duration.ofHours(2));
        var decided = w.approvals.decide(TENANT, ADMIN, "Bea Admin", asked.requestId().toString(),
                AccessRequestService.Decision.APPROVE, "corr-3");
        assertEquals(AccessRequestStatus.APPROVED, decided.status());
        assertEquals(SensitivityLevel.STANDARD, w.documents.get(C1).sensitivityLevel(), "lowered on approval");
        AuditEvent lowered = w.last("DOCUMENT_SENSITIVITY_LOWERED");
        assertEquals(ADMIN, lowered.actorId());
        assertEquals("Bea Admin", lowered.actorDisplayName());
        assertEquals(START.plus(Duration.ofHours(2)), lowered.occurredAt());
        assertEquals("CONFIDENTIAL", details(lowered).get("from").asString());
        assertEquals("STANDARD", details(lowered).get("to").asString());
        assertFalse(lowered.safeDetailsJson().contains("mistake"), "no reason in the audit");
        var loweredRow = w.requests.changes.getLast();
        assertEquals(loweredRow.changeId().toString(), details(lowered).get("levelChangeId").asString());
        assertEquals("Was marked by mistake", loweredRow.reason());
        assertEquals(AccessRequestRepository.LevelChange.Direction.LOWERED, loweredRow.direction());
        assertEquals(ADMIN, loweredRow.changedBy(), "the approver made it apply");
        assertEquals(asked.requestId(), loweredRow.accessRequestId(), "the request it came from");
        // Stewards and Business Admins read the history, newest first.
        var read = w.levels.documentHistory(TENANT, STEWARD_ID, STEWARD, false, C1.toString(), "c");
        assertEquals(1, read.size());
        assertEquals("Was marked by mistake", read.getFirst().reason());
        assertEquals("Bea Admin", read.getFirst().changedBy(), "the name when known");
        assertEquals(1, w.levels.documentHistory(TENANT, ADMIN, new Clearance(false, false), true, C1.toString(), "c").size(),
                "a Business Admin reads it without opening the document");
        assertEquals(STEWARD_ID, details(lowered).get("requestedBy").asString());
        assertEquals(ADMIN, w.last("ACCESS_REQUEST_APPROVED").actorId());
    }

    @Test
    void theDocumentHistoryNeedsTheDocumentOpenableForAStewardAndAKnownDocument() {
        // Task 0029 (0.4): a Steward who can't open a Highly Confidential document can't read its reasons either.
        int before = w.events.size();
        assertEquals("SDV-SEN-008", code(() -> w.levels.documentHistory(TENANT, STEWARD_ID, STEWARD, false, H1.toString(), "c")));
        assertEquals("DOCUMENT_ACCESS_REFUSED", w.events.get(before).action(), "the refusal is audited");
        assertTrue(w.levels.documentHistory(TENANT, RS, RESTRICTED, false, H1.toString(), "c").isEmpty(),
                "no change yet");
        assertEquals("SDV-SEN-016", code(() -> w.levels.documentHistory(TENANT, ADMIN, USER, true, "nope", "c")));
        assertEquals("SDV-SEN-016", code(() -> w.levels.documentHistory(OTHER_TENANT, ADMIN, USER, true, H1.toString(), "c")),
                "another tenant's document is unknown");
    }

    @Test
    void aStewardAsksToLowerOnlyFromConfidentialARestrictedStewardFromHigher() {
        String ref = recordRef(CONTRACT_A);
        w.recordLevels.put(recordKey(CONTRACT_A), SensitivityLevel.HIGHLY_CONFIDENTIAL);
        assertEquals("SDV-SEN-015", code(() -> w.levels.requestLowerRecord(TENANT, STEWARD_ID, null, STEWARD, ref,
                "CONFIDENTIAL", "Not that sensitive", "c")));
        LevelChange asked = w.levels.requestLowerRecord(TENANT, RS, null, RESTRICTED, ref, "CONFIDENTIAL",
                "Not that sensitive", "c");
        assertEquals(LevelChange.Outcome.REQUESTED, asked.outcome());
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, w.recordLevels.level(SOURCE, recordKey(CONTRACT_A)).orElseThrow(),
                "not lowered yet");

        // From Confidential, a Steward may ask.
        String refB = recordRef(CONTRACT_B);
        w.recordLevels.put(recordKey(CONTRACT_B), SensitivityLevel.CONFIDENTIAL);
        assertEquals(LevelChange.Outcome.REQUESTED, w.levels.requestLowerRecord(TENANT, STEWARD_ID, null, STEWARD, refB,
                "STANDARD", "Back to normal", "c").outcome());
    }

    @Test
    void theRecordLevelFollowsTheSameRules() throws Exception {
        String ref = recordRef(CONTRACT_B);
        assertEquals("SDV-SEN-013", code(() -> w.levels.raiseRecord(TENANT, STEWARD_ID, null, STEWARD, ref, "STANDARD",
                "No level yet", "c")), "a record without a level: Standard is no raise");
        assertEquals("SDV-SEN-014", code(() -> w.levels.requestLowerRecord(TENANT, STEWARD_ID, null, STEWARD, ref,
                "STANDARD", "Nothing to lower", "c")), "a record without a level can't be lowered");

        LevelChange raised = w.levels.raiseRecord(TENANT, STEWARD_ID, "Sam Steward", STEWARD, ref, "CONFIDENTIAL",
                "Litigation", "corr-4");
        assertEquals(LevelChange.Outcome.APPLIED, raised.outcome());
        assertEquals(SensitivityLevel.CONFIDENTIAL, w.recordLevels.level(SOURCE, recordKey(CONTRACT_B)).orElseThrow(),
                "applied at once");
        AuditEvent event = w.last("RECORD_SENSITIVITY_RAISED");
        assertEquals("USER", event.actorType());
        assertEquals(STEWARD_ID, event.actorId());
        assertTrue(details(event).get("from").isNull(), "from no level");
        assertEquals("CONFIDENTIAL", details(event).get("to").asString());
        assertFalse(event.safeDetailsJson().contains("Litigation"), "no reason in the audit");
        assertEquals("RECORD", details(event).get("scope").asString());
        assertEquals("Litigation", w.requests.changes.getLast().reason());
        assertNull(w.requests.changes.getLast().from(), "a first record level comes from none");
        assertEquals(SUB_B.toString(), details(event).get("submissionId").asString(), "so the audit list finds the record");
        // Its documents are now Confidential: a Business User is locked out of S2.
        assertEquals("SDV-SEN-008", code(() -> w.guard.requireOpenable(w.documents.get(S2), USER,
                DocumentAccessGuard.Attempt.OPEN, "user-1", "c")));

        assertEquals("SDV-SEN-013", code(() -> w.levels.raiseRecord(TENANT, STEWARD_ID, null, STEWARD, ref, "CONFIDENTIAL",
                "Again", "c")));
        w.levels.raiseRecord(TENANT, STEWARD_ID, null, STEWARD, ref, "HIGHLY_CONFIDENTIAL", "Worse", "c");
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, w.recordLevels.level(SOURCE, recordKey(CONTRACT_B)).orElseThrow());
        assertEquals("CONFIDENTIAL", details(w.last("RECORD_SENSITIVITY_RAISED")).get("from").asString());

        // Lowering the record: a request, approved one at a time.
        LevelChange asked = w.levels.requestLowerRecord(TENANT, RS, null, RESTRICTED, ref, "STANDARD", "Case closed", "c");
        w.approvals.decide(TENANT, ADMIN, null, asked.requestId().toString(), AccessRequestService.Decision.APPROVE, "c");
        assertEquals(SensitivityLevel.STANDARD, w.recordLevels.level(SOURCE, recordKey(CONTRACT_B)).orElseThrow());
        AuditEvent lowered = w.last("RECORD_SENSITIVITY_LOWERED");
        assertEquals("HIGHLY_CONFIDENTIAL", details(lowered).get("from").asString());
        assertEquals("STANDARD", details(lowered).get("to").asString());
        assertFalse(lowered.safeDetailsJson().contains("Case closed"), "no reason in the audit");
        var recordHistory = w.levels.recordHistory(TENANT, ref);
        assertEquals(List.of("Case closed", "Worse", "Litigation"),
                recordHistory.stream().map(SensitivityLevelService.HistoryEntry::reason).toList(), "newest first");
        assertEquals(details(lowered).get("levelChangeId").asString(), recordHistory.getFirst().changeId().toString());

        assertEquals("SDV-SEN-016", code(() -> w.levels.raiseRecord(TENANT, STEWARD_ID, null, STEWARD, "not-a-ref",
                "CONFIDENTIAL", "Reason", "c")));
        assertEquals("SDV-SEN-016", code(() -> w.levels.raiseRecord(TENANT, STEWARD_ID, null, STEWARD,
                recordRef("399999999999"), "CONFIDENTIAL", "Reason", "c")), "a record with no submissions here");
    }

    @Test
    void anApprovedLoweringThatNoLongerAppliesIsRefused() {
        LevelChange asked = w.levels.requestLowerDocument(TENANT, STEWARD_ID, null, STEWARD, C1.toString(), "STANDARD",
                "Mistake", "c");
        // Someone else lowers it first (another request), so this one would do nothing or the wrong thing.
        w.withLevel(C1, SensitivityLevel.STANDARD);
        assertEquals("SDV-APR-011", code(() -> w.approvals.decide(TENANT, ADMIN, null, asked.requestId().toString(),
                AccessRequestService.Decision.APPROVE, "c")));
        assertEquals(AccessRequestStatus.PENDING, w.requests.get(asked.requestId()).status(), "still waiting, to be declined");
        assertEquals(AuditOutcome.DENIED, w.last("ACCESS_REQUEST_DECISION_REFUSED").outcome());
        assertTrue(w.events("DOCUMENT_SENSITIVITY_LOWERED").isEmpty());
        assertEquals(AccessRequestStatus.DECLINED, w.approvals.decide(TENANT, ADMIN, null, asked.requestId().toString(),
                AccessRequestService.Decision.DECLINE, "c").status());
    }
}
