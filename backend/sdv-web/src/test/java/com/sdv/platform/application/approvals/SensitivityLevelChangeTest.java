package com.sdv.platform.application.approvals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.sensitivity.SensitivityLevelService.LevelChange;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;

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
        assertEquals("STANDARD", details(event).get("from").asText(), "from");
        assertEquals("CONFIDENTIAL", details(event).get("to").asText(), "to");
        assertEquals("Contains salary details", details(event).get("reason").asText(), "the reason, trimmed");
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
        assertEquals("LOWER_LEVEL", details(created).get("kind").asText());
        assertEquals("CONFIDENTIAL", details(created).get("level").asText());
        assertEquals("STANDARD", details(created).get("requestedLevel").asText());
        assertFalse(created.safeDetailsJson().contains("mistake"), "the request's reason stays in access_requests");

        assertEquals("SDV-APR-004", code(() -> w.levels.requestLowerDocument(TENANT, STEWARD_ID, null, STEWARD,
                C1.toString(), "STANDARD", "Twice", "c")), "one waiting request per person and document");
        assertEquals("SDV-SEN-014", code(() -> w.levels.requestLowerDocument(TENANT, STEWARD_ID, null, STEWARD,
                S1.toString(), "STANDARD", "Already Standard", "c")));
        assertEquals("SDV-SEN-014", code(() -> w.levels.requestLowerDocument(TENANT, RS, null, RESTRICTED,
                C1.toString(), "HIGHLY_CONFIDENTIAL", "Up is not down", "c")));

        // The Business Admin approves: now it applies, audited with who, when, from, to and the reason.
        w.clock.advance(Duration.ofHours(2));
        var decided = w.approvals.decide(TENANT, ADMIN, "Bea Admin", asked.requestId().toString(),
                AccessRequestService.Decision.APPROVE, "corr-3");
        assertEquals(AccessRequestStatus.APPROVED, decided.status());
        assertEquals(SensitivityLevel.STANDARD, w.documents.get(C1).sensitivityLevel(), "lowered on approval");
        AuditEvent lowered = w.last("DOCUMENT_SENSITIVITY_LOWERED");
        assertEquals(ADMIN, lowered.actorId());
        assertEquals("Bea Admin", lowered.actorDisplayName());
        assertEquals(START.plus(Duration.ofHours(2)), lowered.occurredAt());
        assertEquals("CONFIDENTIAL", details(lowered).get("from").asText());
        assertEquals("STANDARD", details(lowered).get("to").asText());
        assertEquals("Was marked by mistake", details(lowered).get("reason").asText());
        assertEquals(STEWARD_ID, details(lowered).get("requestedBy").asText());
        assertEquals(ADMIN, w.last("ACCESS_REQUEST_APPROVED").actorId());
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
        assertEquals("CONFIDENTIAL", details(event).get("to").asText());
        assertEquals("Litigation", details(event).get("reason").asText());
        assertEquals(SUB_B.toString(), details(event).get("submissionId").asText(), "so the audit list finds the record");
        // Its documents are now Confidential: a Business User is locked out of S2.
        assertEquals("SDV-SEN-008", code(() -> w.guard.requireOpenable(w.documents.get(S2), USER,
                DocumentAccessGuard.Attempt.OPEN, "user-1", "c")));

        assertEquals("SDV-SEN-013", code(() -> w.levels.raiseRecord(TENANT, STEWARD_ID, null, STEWARD, ref, "CONFIDENTIAL",
                "Again", "c")));
        w.levels.raiseRecord(TENANT, STEWARD_ID, null, STEWARD, ref, "HIGHLY_CONFIDENTIAL", "Worse", "c");
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, w.recordLevels.level(SOURCE, recordKey(CONTRACT_B)).orElseThrow());
        assertEquals("CONFIDENTIAL", details(w.last("RECORD_SENSITIVITY_RAISED")).get("from").asText());

        // Lowering the record: a request, approved one at a time.
        LevelChange asked = w.levels.requestLowerRecord(TENANT, RS, null, RESTRICTED, ref, "STANDARD", "Case closed", "c");
        w.approvals.decide(TENANT, ADMIN, null, asked.requestId().toString(), AccessRequestService.Decision.APPROVE, "c");
        assertEquals(SensitivityLevel.STANDARD, w.recordLevels.level(SOURCE, recordKey(CONTRACT_B)).orElseThrow());
        AuditEvent lowered = w.last("RECORD_SENSITIVITY_LOWERED");
        assertEquals("HIGHLY_CONFIDENTIAL", details(lowered).get("from").asText());
        assertEquals("STANDARD", details(lowered).get("to").asText());
        assertEquals("Case closed", details(lowered).get("reason").asText());

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
