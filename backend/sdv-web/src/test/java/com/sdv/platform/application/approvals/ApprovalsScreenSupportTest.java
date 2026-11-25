package com.sdv.platform.application.approvals;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.approvals.AccessRequestService.Decision;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.document.SensitivityLevel;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.sdv.platform.application.approvals.ApprovalsFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0030: what the Approvals screen and the requester's notices rely on. Fifty waiting requests approved in one
 * bulk decision; any Business admin of the tenant decides (nothing assumes a single one); and (0.3) the requester's
 * own list names the record of each request, a whole-record request included.
 */
class ApprovalsScreenSupportTest {
    private static final ObjectMapper JSON = com.sdv.platform.application.json.VeroJson.mapper();
    private final ApprovalsFixture w = new ApprovalsFixture();

    private UUID ask(String actor, UUID document, String kind) {
        return w.approvals.requestPrintOrDownload(TENANT, actor, "Person " + actor, RESTRICTED, document.toString(), kind,
                "Hearing bundle", "c").requestId();
    }

    /** 25 requesters, each asking to print H1 and to download H2: 50 waiting requests. */
    private List<String> fiftyWaiting() {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            ids.add(ask("rs-" + (100 + i), H1, "PRINT").toString());
            w.clock.advance(Duration.ofSeconds(1));
            ids.add(ask("rs-" + (100 + i), H2, "DOWNLOAD").toString());
            w.clock.advance(Duration.ofSeconds(1));
        }
        return ids;
    }

    @Test
    void fiftyWaitingRequestsAreApprovedInOneBulkDecision() {
        List<String> ids = fiftyWaiting();
        assertEquals(50, w.approvals.pending(TENANT, null, null, "c").requestCount());

        var result = w.approvals.decideMany(TENANT, ADMIN, "Bea Admin", "APPROVE", ids, "c");
        assertEquals(50, result.decided());
        assertEquals(0, result.notDecided());
        assertTrue(result.results().stream().allMatch(r -> "APPROVED".equals(r.outcome())));
        assertEquals(0, w.approvals.pending(TENANT, null, null, "c").requestCount(), "nothing left waiting");
        assertEquals(50, w.events(AccessRequestService.APPROVED_ACTION).size(), "each audited on its own");
        assertEquals(50, ids.stream().map(UUID::fromString).map(w.requests::get)
                .filter(r -> r.status() == AccessRequestStatus.APPROVED
                        && r.expiresAt().equals(w.clock.instant().plus(Duration.ofHours(24)))).count(),
                "each is a grant with its own use-by time");
    }

    @Test
    void anyBusinessAdminOfTheTenantDecidesAndEachDecisionNamesItsOwnApprover() {
        List<String> ids = fiftyWaiting();
        // Two admins work through the same list at the same time: each decides what is still waiting.
        var first = w.approvals.decideMany(TENANT, ADMIN, "Bea Admin", "APPROVE", ids.subList(0, 30), "c");
        assertEquals(30, first.decided());
        assertEquals(20, w.approvals.pending(TENANT, null, null, "c").requestCount(), "the second admin sees what is left");
        var second = w.approvals.decideMany(TENANT, ADMIN_2, "Ben Admin", "DECLINE", ids, "c");
        assertEquals(20, second.decided(), "the other 20");
        assertEquals(30, second.notDecided());
        assertTrue(second.results().stream().filter(r -> "REFUSED".equals(r.outcome()))
                .allMatch(r -> "SDV-APR-008".equals(r.code())), "already decided by the first admin: no longer waiting");

        Set<String> approvers = w.events(AccessRequestService.APPROVED_ACTION).stream().map(AuditEvent::actorId)
                .collect(Collectors.toSet());
        Set<String> decliners = w.events(AccessRequestService.DECLINED_ACTION).stream().map(AuditEvent::actorId)
                .collect(Collectors.toSet());
        assertEquals(Set.of(ADMIN), approvers);
        assertEquals(Set.of(ADMIN_2), decliners);
        assertEquals(0, w.approvals.pending(TENANT, null, null, "c").requestCount());
    }

    @Test
    void theRequestersOwnListNamesTheRecordOfEachRequest() throws Exception {
        UUID print = ask(RS, H1, "PRINT");
        w.recordLevels.put(recordId(CONTRACT_B), SensitivityLevel.CONFIDENTIAL);
        w.clock.advance(Duration.ofMinutes(1));
        UUID record = w.levels.requestLowerRecord(TENANT, RS, "Rae Restricted", RESTRICTED, recordRef(CONTRACT_B), "STANDARD",
                "Closed", "c").requestId();

        var mine = w.approvals.mine(TENANT, RS, "c");
        var whole = mine.stream().filter(r -> r.requestId().equals(record)).findFirst().orElseThrow();
        assertEquals(AccessRequestKind.Scope.RECORD, whole.scope());
        assertNull(whole.documentId());
        assertEquals(CONTRACT_B, whole.primaryIdentifier(), "the whole-record request names its record");
        assertEquals("Contract number", whole.primaryIdentifierLabel());
        assertEquals(SOURCE, whole.sourceId());
        var one = mine.stream().filter(r -> r.requestId().equals(print)).findFirst().orElseThrow();
        assertEquals(CONTRACT_A, one.primaryIdentifier(), "a document request names its document's record");
        assertEquals(SOURCE, one.sourceId());

        JsonNode row = JSON.readTree(JSON.writeValueAsString(mine)).get(0);
        Set<String> keys = new TreeSet<>();
        for (Iterator<String> it = row.propertyNames().iterator(); it.hasNext(); ) keys.add(it.next());
        assertEquals(new TreeSet<>(List.of("requestId", "kind", "scope", "status", "documentId", "requestedLevel", "reason",
                "requestedAt", "decidedAt", "expiresAt", "usedAt", "sourceId", "primaryIdentifier", "primaryIdentifierLabel")),
                keys, "exactly these fields: no title, file name or content");
        String json = JSON.writeValueAsString(mine).toLowerCase();
        assertFalse(json.contains(".pdf"));
        assertFalse(json.contains("secret-name"));

        // The decision shows up in the list the requester's notice is built from.
        w.approvals.decide(TENANT, ADMIN, "Bea Admin", print.toString(), Decision.APPROVE, "c");
        var after = w.approvals.mine(TENANT, RS, "c").stream().filter(r -> r.requestId().equals(print)).findFirst().orElseThrow();
        assertEquals(AccessRequestStatus.APPROVED, after.status());
        assertEquals(w.clock.instant(), after.decidedAt());
    }

    /** Task 0031 (0.2): "Decided recently" lists every Business admin's decisions, newest first, without reasons. */
    @Test
    void decidedRecentlyListsTheDecisionsOfEveryBusinessAdminWithoutReasons() throws Exception {
        UUID old = ask(RS, H1, "DOWNLOAD");
        w.approvals.decide(TENANT, ADMIN, "Bea Admin", old.toString(), Decision.DECLINE, "c");
        w.clock.advance(Duration.ofDays(31));
        UUID print = ask(RS, H1, "PRINT");
        UUID download = ask("rs-2", H2, "DOWNLOAD");
        w.recordLevels.put(recordId(CONTRACT_B), SensitivityLevel.CONFIDENTIAL);
        UUID record = w.levels.requestLowerRecord(TENANT, RS, "Rae Restricted", RESTRICTED, recordRef(CONTRACT_B), "STANDARD",
                "Closed", "c").requestId();
        UUID waiting = ask("rs-3", H2, "PRINT");
        w.approvals.decide(TENANT, ADMIN, "Bea Admin", print.toString(), Decision.APPROVE, "c");
        w.clock.advance(Duration.ofMinutes(1));
        w.approvals.decide(TENANT, ADMIN_2, "Ben Admin", download.toString(), Decision.DECLINE, "c");
        w.clock.advance(Duration.ofMinutes(1));
        w.approvals.decide(TENANT, ADMIN_2, null, record.toString(), Decision.APPROVE, "c");

        var decided = w.approvals.decidedRecently(TENANT);
        assertEquals(List.of(record, download, print), decided.stream().map(AccessRequestService.DecidedView::requestId).toList(),
                "newest first; the one decided 31 days ago and the one still waiting are not listed");
        assertFalse(decided.stream().anyMatch(d -> d.requestId().equals(old) || d.requestId().equals(waiting)));
        var wholeRecord = decided.getFirst();
        assertEquals(AccessRequestKind.Scope.RECORD, wholeRecord.scope());
        assertEquals(CONTRACT_B, wholeRecord.primaryIdentifier());
        assertEquals(Decision.APPROVE, wholeRecord.decision());
        assertEquals(ADMIN_2, wholeRecord.decidedBy(), "the sign-in subject when no name was known");
        assertEquals(SensitivityLevel.STANDARD, wholeRecord.requestedLevel());
        assertEquals("Ben Admin", decided.get(1).decidedBy());
        assertEquals(Decision.DECLINE, decided.get(1).decision());
        assertEquals(CONTRACT_A, decided.get(2).primaryIdentifier());
        assertEquals("Bea Admin", decided.get(2).decidedBy(), "each admin's own decisions");

        // A used or expired approval is still an approval in this list.
        w.clock.advance(Duration.ofHours(25));
        assertEquals(Decision.APPROVE, w.approvals.decidedRecently(TENANT).stream()
                .filter(d -> d.requestId().equals(print)).findFirst().orElseThrow().decision());

        JsonNode row = JSON.readTree(JSON.writeValueAsString(decided)).get(0);
        Set<String> keys = new TreeSet<>();
        for (Iterator<String> it = row.propertyNames().iterator(); it.hasNext(); ) keys.add(it.next());
        assertEquals(new TreeSet<>(List.of("requestId", "kind", "scope", "requestedLevel", "primaryIdentifier",
                "primaryIdentifierLabel", "decision", "decidedBy", "decidedAt")), keys, "no reason, requester or file name");
        String json = JSON.writeValueAsString(decided);
        assertFalse(json.contains("Hearing bundle") || json.contains("Closed") || json.contains("Rae") || json.contains(".pdf"), json);
    }

    /** Task 0031 (0.3): the approvals list carries the tenant's own pending-expiry window. */
    @Test
    void theApprovalsListCarriesTheTenantsPendingExpiryWindow() {
        assertEquals(7, w.approvals.pending(TENANT, null, null, "c").pendingExpiryDays(), "the default");
        w.settings = new ApprovalSettings("24", "7", "", TENANT + "=3");
        w.build();
        assertEquals(3, w.approvals.pending(TENANT, null, null, "c").pendingExpiryDays());
    }
}
