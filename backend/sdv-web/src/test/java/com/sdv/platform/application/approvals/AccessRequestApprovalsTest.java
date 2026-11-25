package com.sdv.platform.application.approvals;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.access.SensitiveAccessGrants;
import com.sdv.platform.application.approvals.AccessRequestService.Decision;
import com.sdv.platform.application.port.AccessRequestRepository.AccessRequest;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.sdv.platform.application.approvals.ApprovalsFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0028, sections 3 to 5: Highly Confidential print and download grants (one document, one use, 24 hours),
 * nobody approving their own request, the approvals list (only what the task lists), bulk decisions, and pending
 * requests expiring after 7 days. Through the real services, guard, grant store and print path.
 */
class AccessRequestApprovalsTest {
    private static final ObjectMapper JSON = com.sdv.platform.application.json.VeroJson.mapper();
    private final ApprovalsFixture w = new ApprovalsFixture();

    private static String code(Executable call) {
        Throwable thrown = assertThrows(RuntimeException.class, call);
        assertInstanceOf(CodedRefusal.class, thrown, thrown.toString());
        return ((CodedRefusal) thrown).errorCode().code();
    }

    private UUID ask(String actor, UUID document, String kind) {
        return w.approvals.requestPrintOrDownload(TENANT, actor, "Rae Restricted", RESTRICTED, document.toString(), kind,
                "Court bundle", "c").requestId();
    }

    private AccessRequestService.DecisionView approve(String admin, UUID request) {
        return w.approvals.decide(TENANT, admin, "Bea Admin", request.toString(), Decision.APPROVE, "c");
    }

    private void print(String actor, UUID document) {
        w.actions.recordPrint(TENANT, document, RESTRICTED, actor, "c");
    }

    // ---------------------------------------------------------------------------------- grants

    @Test
    void anApprovalIsAGrantForOneDocumentAndOneUse() throws Exception {
        assertEquals("SDV-SEN-009", code(() -> print(RS, H1)), "no grant yet");
        UUID request = ask(RS, H1, "PRINT");
        assertEquals("SDV-SEN-009", code(() -> print(RS, H1)), "a waiting request is no grant");

        var decided = approve(ADMIN, request);
        assertEquals(AccessRequestStatus.APPROVED, decided.status());
        assertEquals(START.plus(Duration.ofHours(24)), decided.grantExpiresAt(), "24 hours after approval");

        print(RS, H1);
        AccessRequest used = w.requests.get(request);
        assertEquals(AccessRequestStatus.USED, used.status(), "marked used");
        assertEquals(START, used.usedAt());
        AuditEvent printed = w.last("DOCUMENT_PRINTED");
        assertTrue(printed.safeDetailsJson().contains(request.toString()), "the print names the grant it used");
        assertTrue(printed.safeDetailsJson().contains("HIGHLY_CONFIDENTIAL"));
        AuditEvent grantUsed = w.last(SensitiveAccessGrantStore.GRANT_USED_ACTION);
        assertEquals(RS, grantUsed.actorId());
        assertEquals(H1.toString(), grantUsed.targetId());

        assertEquals("SDV-SEN-009", code(() -> print(RS, H1)), "a second use is refused");
        assertEquals(1, w.events("DOCUMENT_PRINTED").size());
        AuditEvent refused = w.last(DocumentAccessGuard.REFUSED_ACTION);
        assertTrue(refused.safeDetailsJson().contains("SDV-SEN-009"));
        assertEquals("SDV-SEN-009", code(() -> print(RS, H2)), "the grant was for one document only");
    }

    @Test
    void theWrongPersonOrTheWrongKindGetsNothingFromAGrant() {
        approve(ADMIN, ask(RS, H1, "DOWNLOAD"));
        assertEquals("SDV-SEN-009", code(() -> print(RS, H1)), "a download grant doesn't print");
        assertEquals("SDV-SEN-009", code(() -> w.guard.requirePrintOrDownload(w.documents.get(H1), RESTRICTED,
                DocumentAccessGuard.Attempt.DOWNLOAD, RS_2, "c", SensitiveAccessGrants.Use.USE)), "nor serves another person");
        // The download's own check doesn't use the grant up; only the use just before decrypting does.
        var checked = w.guard.requirePrintOrDownload(w.documents.get(H1), RESTRICTED, DocumentAccessGuard.Attempt.DOWNLOAD,
                RS, "c", SensitiveAccessGrants.Use.CHECK);
        assertNotNull(checked.grantId());
        assertEquals(AccessRequestStatus.APPROVED, w.requests.get(checked.grantId()).status(), "a check uses nothing");
        var used = w.guard.requirePrintOrDownload(w.documents.get(H1), RESTRICTED, DocumentAccessGuard.Attempt.DOWNLOAD,
                RS, "c", SensitiveAccessGrants.Use.USE);
        assertEquals(checked.grantId(), used.grantId());
        assertEquals(AccessRequestStatus.USED, w.requests.get(used.grantId()).status());
        assertEquals("SDV-SEN-009", code(() -> w.guard.requirePrintOrDownload(w.documents.get(H1), RESTRICTED,
                DocumentAccessGuard.Attempt.DOWNLOAD, RS, "c", SensitiveAccessGrants.Use.CHECK)), "then it is gone");
    }

    @Test
    void anUnusedGrantExpiresAfterItsWindowWhichIsConfigurablePerTenant() {
        UUID request = ask(RS, H1, "PRINT");
        approve(ADMIN, request);
        w.clock.advance(Duration.ofHours(24));
        assertEquals("SDV-SEN-009", code(() -> print(RS, H1)), "at 24 hours it is no longer usable");
        w.grants.expireDue(TENANT, "c");
        assertEquals(AccessRequestStatus.EXPIRED, w.requests.get(request).status());
        AuditEvent expired = w.last(SensitiveAccessGrantStore.EXPIRED_ACTION);
        assertEquals("SYSTEM", expired.actorType());
        assertTrue(expired.safeDetailsJson().contains("\"was\":\"APPROVED\""));

        // Another tenant setting: 2 hours.
        w.settings = new ApprovalSettings("24", "7", TENANT + "=2", "");
        w.build();
        UUID second = ask(RS, H2, "PRINT");
        assertEquals(w.clock.instant().plus(Duration.ofHours(2)), approve(ADMIN, second).grantExpiresAt());
        w.clock.advance(Duration.ofMinutes(119));
        print(RS, H2);
        assertEquals(AccessRequestStatus.USED, w.requests.get(second).status(), "inside the window it works");
    }

    @Test
    void onlyHighlyConfidentialAvailableDocumentsTheRequesterCanOpenAreAskedFor() {
        assertEquals("SDV-APR-003", code(() -> ask(RS, C1, "PRINT")), "Confidential needs no approval");
        assertEquals("SDV-APR-012", code(() -> ask(RS, H1, "LOWER_LEVEL")));
        assertEquals("SDV-APR-012", code(() -> ask(RS, H1, "print")));
        assertEquals("SDV-APR-013", code(() -> w.approvals.requestPrintOrDownload(TENANT, RS, null, RESTRICTED, "nope",
                "PRINT", "Court bundle", "c")));
        assertEquals("SDV-APR-002", code(() -> w.approvals.requestPrintOrDownload(TENANT, RS, null, RESTRICTED,
                H1.toString(), "PRINT", "ab", "c")));
        assertEquals("SDV-SEN-008", code(() -> w.approvals.requestPrintOrDownload(TENANT, STEWARD_ID, null, STEWARD,
                H1.toString(), "PRINT", "Court bundle", "c")), "a Steward can't even open it");
        ask(RS, H1, "PRINT");
        assertEquals("SDV-APR-004", code(() -> ask(RS, H1, "PRINT")), "one waiting request per person, document and kind");
        ask(RS, H1, "DOWNLOAD");
        w.withStatus(H2, DocumentStatus.PROCESSING);
        assertEquals("SDV-APR-014", code(() -> ask(RS, H2, "PRINT")));
        assertEquals(2, w.events("ACCESS_REQUEST_CREATED").size());
        assertFalse(w.last("ACCESS_REQUEST_CREATED").safeDetailsJson().contains("Court bundle"),
                "the reason isn't copied into the audit");
    }

    // -------------------------------------------------------------------------- own requests

    @Test
    void nobodyApprovesOrDeclinesTheirOwnRequest() {
        // The service rule holds whatever the roles: the requester's subject can never decide the request.
        UUID request = ask(RS, H1, "PRINT");
        for (Decision decision : Decision.values()) {
            assertEquals("SDV-APR-006", code(() -> w.approvals.decide(TENANT, RS, null, request.toString(), decision, "c")));
        }
        assertEquals(AccessRequestStatus.PENDING, w.requests.get(request).status());
        List<AuditEvent> refused = w.events(AccessRequestService.DECISION_REFUSED_ACTION);
        assertEquals(2, refused.size());
        assertTrue(refused.stream().allMatch(e -> e.outcome() == AuditOutcome.DENIED && e.actorId().equals(RS)
                && e.safeDetailsJson().contains("SDV-APR-006")));
        var bulk = w.approvals.decideMany(TENANT, RS, null, "APPROVE", List.of(request.toString()), "c");
        assertEquals("SDV-APR-006", bulk.results().getFirst().code(), "nor in bulk");
        assertEquals(AccessRequestStatus.APPROVED, approve(ADMIN, request).status(), "another person may");
        assertEquals("SDV-APR-008", code(() -> approve(ADMIN_2, request)), "decided once");
    }

    /**
     * Task 0037 (owner answer 2 to report 0036): two Business Admins decide the same request at the same moment. The
     * second finds it pending when it reads it, but its decision changes no row. That is a refusal, not an internal
     * fault: 409 SDV-APR-008 "Someone else already decided this request.", audited, and nothing of the second decision
     * stays (no grant, no ACCESS_REQUEST_DECLINED event).
     */
    @Test
    void twoDecisionsAtTheSameMomentGiveTheSecondApr008Audited() {
        UUID request = ask(RS, H1, "PRINT");
        w.requests.decideMeanwhile(request, AccessRequestStatus.APPROVED, ADMIN_2);
        Throwable thrown = assertThrows(CodedRefusal.Conflict.class,
                () -> w.approvals.decide(TENANT, ADMIN, "Bea Admin", request.toString(), Decision.DECLINE, "corr-race"));
        assertEquals("SDV-APR-008", ((CodedRefusal) thrown).errorCode().code());
        assertEquals("Someone else already decided this request.", thrown.getMessage());
        assertEquals(AccessRequestStatus.APPROVED, w.requests.get(request).status(), "the first decision stands");
        assertEquals(ADMIN_2, w.requests.get(request).decidedBy());
        assertTrue(w.events("ACCESS_REQUEST_DECLINED").isEmpty(), "the refused decision wrote no event of its own");
        AuditEvent refused = w.last(AccessRequestService.DECISION_REFUSED_ACTION);
        assertEquals(AuditOutcome.DENIED, refused.outcome());
        assertEquals(ADMIN, refused.actorId());
        assertEquals("corr-race", refused.correlationId());
        assertTrue(refused.safeDetailsJson().contains("SDV-APR-008"));

        // In a bulk decision the same is one refused line; the others still go through.
        UUID other = ask(RS, H2, "PRINT");
        UUID raced = ask(RS, H2, "DOWNLOAD");
        w.requests.decideMeanwhile(raced, AccessRequestStatus.DECLINED, ADMIN_2);
        var bulk = w.approvals.decideMany(TENANT, ADMIN, null, "APPROVE", List.of(raced.toString(), other.toString()), "c");
        assertEquals("SDV-APR-008", bulk.results().get(0).code());
        assertEquals("REFUSED", bulk.results().get(0).outcome());
        assertEquals("APPROVED", bulk.results().get(1).outcome());
    }

    // -------------------------------------------------------------------------------- bulk

    @Test
    void aBulkDecisionDecidesEachOnItsOwnAndRefusesWholeRecordRequests() throws Exception {
        UUID p1 = ask(RS, H1, "PRINT");
        UUID p2 = ask(RS, H2, "DOWNLOAD");
        UUID lower = w.levels.requestLowerDocument(TENANT, STEWARD_ID, null, STEWARD, C1.toString(), "STANDARD", "Mistake",
                "c").requestId();
        w.recordLevels.put(recordId(CONTRACT_B), SensitivityLevel.CONFIDENTIAL);
        UUID record = w.levels.requestLowerRecord(TENANT, STEWARD_ID, null, STEWARD, recordRef(CONTRACT_B), "STANDARD",
                "Closed", "c").requestId();

        var result = w.approvals.decideMany(TENANT, ADMIN, "Bea Admin", "APPROVE",
                List.of(p1.toString(), p2.toString(), lower.toString(), record.toString(), "nope", p1.toString()), "c");
        assertEquals(3, result.decided());
        assertEquals(2, result.notDecided());
        assertEquals(5, result.results().size(), "a repeated reference is decided once");
        assertEquals("APPROVED", result.results().get(0).outcome());
        assertEquals("APPROVED", result.results().get(1).outcome());
        assertEquals("APPROVED", result.results().get(2).outcome());
        assertEquals("REFUSED", result.results().get(3).outcome());
        assertEquals("SDV-APR-009", result.results().get(3).code(), "whole-record requests are decided one at a time");
        assertEquals("SDV-APR-007", result.results().get(4).code());
        assertEquals(SensitivityLevel.STANDARD, w.documents.get(C1).sensitivityLevel(), "the lowering applied");
        assertEquals(AccessRequestStatus.PENDING, w.requests.get(record).status());
        assertEquals(3, w.events(AccessRequestService.APPROVED_ACTION).size(), "each audited on its own");
        assertEquals(1, w.events(AccessRequestService.DECISION_REFUSED_ACTION).size(),
                "the whole-record refusal is audited; \"nope\" names no request, so there is nothing to audit it against");

        // Declining in bulk follows the same rule; on its own the record request is decided.
        assertEquals("SDV-APR-009", w.approvals.decideMany(TENANT, ADMIN, null, "DECLINE", List.of(record.toString()), "c")
                .results().getFirst().code());
        assertEquals(AccessRequestStatus.APPROVED, approve(ADMIN, record).status());
        assertEquals(SensitivityLevel.STANDARD, w.recordLevels.level(SOURCE, recordId(CONTRACT_B)).orElseThrow());

        assertEquals("SDV-APR-010", code(() -> w.approvals.decideMany(TENANT, ADMIN, null, "approve", List.of(p1.toString()), "c")));
        assertEquals("SDV-APR-010", code(() -> w.approvals.decideMany(TENANT, ADMIN, null, "APPROVE", List.of(), "c")));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) many.add(UUID.randomUUID().toString());
        assertEquals("SDV-APR-010", code(() -> w.approvals.decideMany(TENANT, ADMIN, null, "APPROVE", many, "c")));
    }

    // ------------------------------------------------------------------------------ expiry

    @Test
    void aPendingRequestNobodyDecidesExpiresAfterSevenDays() {
        UUID request = ask(RS, H1, "PRINT");
        w.clock.advance(Duration.ofDays(7).minusSeconds(1));
        assertEquals(1, w.approvals.pending(TENANT, null, null, "c").requestCount(), "still waiting just before");
        w.clock.advance(Duration.ofSeconds(1));
        assertEquals(0, w.approvals.pending(TENANT, null, null, "c").requestCount(), "gone from the approvals list");
        var mine = w.approvals.mine(TENANT, RS, "c");
        assertEquals(AccessRequestStatus.EXPIRED, mine.getFirst().status(), "the requester sees it as expired");
        assertEquals("SDV-APR-008", code(() -> approve(ADMIN, request)), "and it can't be approved any more");
        AuditEvent expired = w.last(SensitiveAccessGrantStore.EXPIRED_ACTION);
        assertEquals("SYSTEM", expired.actorType());
        assertTrue(expired.safeDetailsJson().contains("\"was\":\"PENDING\""));
        assertEquals(1, w.events(SensitiveAccessGrantStore.EXPIRED_ACTION).size(), "expired once");
        ask(RS, H1, "PRINT");
        assertEquals(1, w.approvals.pending(TENANT, null, null, "c").requestCount(), "a new request can be made");

        w.settings = new ApprovalSettings("24", "7", "", TENANT + "=1");
        w.build();
        UUID shortOne = ask(RS, H2, "PRINT");
        assertEquals(w.clock.instant().plus(Duration.ofDays(1)), w.requests.get(shortOne).expiresAt(),
                "per tenant: 1 day");
    }

    // ------------------------------------------------------------------------- approvals list

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        for (Iterator<String> it = node.propertyNames().iterator(); it.hasNext(); ) keys.add(it.next());
        return keys;
    }

    @Test
    void aLoweringUnderAHigherRecordLevelSaysWhoCanOpenItWontChange() {
        // Task 0029 (0.5): C1 is Confidential; its contract is then raised to Confidential too. Lowering C1 to Standard
        // changes only its own level: the contract keeps it Confidential.
        UUID lowerC = w.levels.requestLowerDocument(TENANT, STEWARD_ID, null, STEWARD, C1.toString(), "STANDARD",
                "Mistake", "c").requestId();
        w.recordLevels.put(recordId(CONTRACT_A), SensitivityLevel.CONFIDENTIAL);
        var row = w.approvals.pending(TENANT, "LOWER_LEVEL", null, "c").groups().getFirst().requests().getFirst();
        assertEquals(lowerC, row.requestId());
        assertEquals(SensitivityLevel.CONFIDENTIAL, row.level(), "effective level");
        assertEquals(SensitivityLevel.CONFIDENTIAL, row.ownLevel(), "the document's own level");
        assertEquals(SensitivityLevel.CONFIDENTIAL, row.recordLevel());
        assertEquals(SensitivityLevel.CONFIDENTIAL, row.levelAfter(), "the contract stays at Confidential");
        assertEquals(Boolean.TRUE, row.accessUnchanged(), "so who can open it won't change");
    }

    @Test
    void theApprovalsListRevealsNothingBeyondWhatTheTaskLists() throws Exception {
        UUID p1 = ask(RS, H1, "PRINT");
        w.clock.advance(Duration.ofMinutes(1));
        UUID lowerB;
        w.recordLevels.put(recordId(CONTRACT_B), SensitivityLevel.CONFIDENTIAL);
        lowerB = w.levels.requestLowerRecord(TENANT, STEWARD_ID, "Sam Steward", STEWARD, recordRef(CONTRACT_B), "STANDARD",
                "Closed", "c").requestId();
        w.clock.advance(Duration.ofMinutes(1));
        UUID p2 = ask(RS, H2, "DOWNLOAD");
        w.clock.advance(Duration.ofMinutes(1));
        UUID lowerC = w.levels.requestLowerDocument(TENANT, STEWARD_ID, "Sam Steward", STEWARD, C1.toString(), "STANDARD",
                "Mistake", "c").requestId();

        var list = w.approvals.pending(TENANT, null, null, "c");
        assertEquals(4, list.requestCount());
        assertEquals(2, list.groups().size(), "grouped by the record's primary identifier");
        assertEquals(CONTRACT_A, list.groups().get(0).primaryIdentifier(), "the group with the oldest request first");
        assertEquals(List.of(p1, p2, lowerC), list.groups().get(0).requests().stream()
                .map(AccessRequestService.PendingRequestView::requestId).toList(), "oldest first");
        assertEquals(CONTRACT_B, list.groups().get(1).primaryIdentifier());

        var h1 = list.groups().get(0).requests().get(0);
        assertEquals(AccessRequestKind.PRINT, h1.kind());
        assertEquals(AccessRequestKind.Scope.DOCUMENT, h1.scope());
        assertEquals(9, h1.pageCount());
        assertEquals(w.documents.get(H1).ingestedAt(), h1.receivedAt());
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, h1.level());
        assertEquals("Rae Restricted", h1.requester());
        assertEquals("Court bundle", h1.reason());
        assertEquals(new AccessRequestService.Position(1, 4), h1.position(),
                "H1 and H2 share received date and page count: told apart by position");
        assertEquals(new AccessRequestService.Position(2, 4), list.groups().get(0).requests().get(1).position());
        var c1 = list.groups().get(0).requests().get(2);
        assertNull(c1.position(), "C1 can be told apart without it");
        assertEquals(SensitivityLevel.CONFIDENTIAL, c1.level());
        assertEquals(SensitivityLevel.STANDARD, c1.requestedLevel());
        // Task 0029 (0.5): both levels. C1 is Confidential on its own and its record has no level, so lowering it
        // changes who can open it.
        assertEquals(SensitivityLevel.CONFIDENTIAL, c1.ownLevel());
        assertNull(c1.recordLevel());
        assertEquals(SensitivityLevel.STANDARD, c1.levelAfter());
        assertEquals(Boolean.FALSE, c1.accessUnchanged());
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, h1.ownLevel());
        assertNull(h1.levelAfter(), "a print request changes no level");
        assertNull(h1.accessUnchanged());

        var record = list.groups().get(1).requests().getFirst();
        assertEquals(lowerB, record.requestId());
        assertEquals(AccessRequestKind.Scope.RECORD, record.scope());
        assertEquals(1, record.documentCount(), "the record's document count");
        assertNull(record.receivedAt());
        assertNull(record.documentId());
        assertEquals(SensitivityLevel.CONFIDENTIAL, record.level());

        String json = JSON.writeValueAsString(list);
        JsonNode first = JSON.readTree(json).at("/groups/0/requests/0");
        assertEquals(new TreeSet<>(List.of("requestId", "kind", "scope", "primaryIdentifier", "primaryIdentifierLabel",
                "receivedAt", "pageCount", "documentCount", "position", "level", "requestedLevel", "requester", "reason",
                "requestedAt", "documentId", "ownLevel", "recordLevel", "levelAfter", "accessUnchanged")), keys(first),
                "exactly these fields (task 0029 0.5 adds the four level fields)");
        assertEquals(new TreeSet<>(List.of("primaryIdentifier", "primaryIdentifierLabel", "requests")),
                keys(JSON.readTree(json).at("/groups/0")));
        String lower = json.toLowerCase();
        assertFalse(lower.contains("secret-name"), "no file name");
        assertFalse(lower.contains(".pdf"));
        assertFalse(lower.contains("file"));
        assertFalse(lower.contains("title"));
        assertFalse(json.contains("Patel"), "no other case fields");
        assertFalse(json.contains("src-"), "no sending system's document IDs");

        // Filters: by kind and by requester.
        assertEquals(1, w.approvals.pending(TENANT, "DOWNLOAD", null, "c").requestCount());
        assertEquals(2, w.approvals.pending(TENANT, "LOWER_LEVEL,LOWER_RECORD_LEVEL", null, "c").requestCount());
        assertEquals(2, w.approvals.pending(TENANT, null, "Sam Steward", "c").requestCount());
        assertEquals(2, w.approvals.pending(TENANT, null, RS, "c").requestCount(), "the subject works too");
        assertEquals(0, w.approvals.pending(OTHER_TENANT, null, null, "c").requestCount(), "tenants are apart");
        assertEquals("SDV-APR-012", code(() -> w.approvals.pending(TENANT, "EVERYTHING", null, "c")));
    }
}
