package com.sdv.platform.application.approvals;

import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.sdv.platform.application.approvals.ApprovalsFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Task 0066: the approval of a redaction of a document on legal hold. */
class HeldRedactionApprovalTest {
    private static final String REASON = "Account number 4711 visible";
    private final ApprovalsFixture f = new ApprovalsFixture();

    private AccessRequestService.RequestView ask() {
        return f.approvals.requestHeldRedaction(TENANT, STEWARD_ID, "Daniel Reyes", f.documents.get(C1), SensitivityLevel.CONFIDENTIAL, REASON, "[{}]", 1, "c");
    }

    private static String code(org.junit.jupiter.api.function.Executable call) {
        return ((CodedRefusal) assertThrows(RuntimeException.class, call)).errorCode().code();
    }

    private void decide(String actor, AccessRequestService.RequestView r, AccessRequestService.Decision d) {
        f.approvals.decide(TENANT, actor, "Priya Natarajan", r.requestId().toString(), d, "c");
    }

    @Test
    void itWaitsForABusinessAdminAndTheReasonStaysOutOfTheAudit() {
        var asked = ask();
        assertEquals(AccessRequestKind.REDACT_HELD, asked.kind());
        assertEquals(AccessRequestStatus.PENDING, asked.status());
        assertEquals("SDV-APR-004", code(this::ask), "the same request only once");
        var waiting = f.approvals.pending(TENANT, "REDACT_HELD", null, "c").groups().getFirst().requests().getFirst();
        assertEquals(REASON, waiting.reason());
        assertTrue(f.requests.heldApproved.isEmpty());
        decide(ADMIN, asked, AccessRequestService.Decision.APPROVE);
        assertTrue(f.requests.heldApproved.contains(asked.requestId()));
        assertEquals(1, f.events(AccessRequestService.APPROVED_ACTION).size());
        assertTrue(f.events.stream().noneMatch(e -> e.safeDetailsJson().contains("4711")), "the reason stays in the request");
    }

    @Test
    void nobodyApprovesTheirOwnAndNoneIsDecidedInBulk() {
        var asked = ask();
        assertEquals("SDV-APR-006", code(() -> decide(STEWARD_ID, asked, AccessRequestService.Decision.APPROVE)));
        var bulk = f.approvals.decideMany(TENANT, ADMIN, null, "APPROVE", List.of(asked.requestId().toString()), "c");
        assertEquals("SDV-APR-009", bulk.results().getFirst().code());
        assertTrue(f.requests.heldApproved.isEmpty());
    }

    @Test
    void aDocumentThatChangedIsNotApprovedAndADeclineOrExpiryNeverIs() {
        var asked = ask();
        f.requests.heldStillApplies = false;
        assertEquals("SDV-APR-017", code(() -> decide(ADMIN, asked, AccessRequestService.Decision.APPROVE)));
        decide(ADMIN, asked, AccessRequestService.Decision.DECLINE);
        f.requests.heldStillApplies = true;
        assertEquals("SDV-APR-008", code(() -> decide(ADMIN, asked, AccessRequestService.Decision.APPROVE)));
        var again = ask();
        f.clock.advance(Duration.ofDays(8));
        assertEquals("SDV-APR-008", code(() -> decide(ADMIN, again, AccessRequestService.Decision.APPROVE)), "expired");
        assertTrue(f.requests.heldApproved.isEmpty());
    }

    @Test
    void theReasonHasARequestsLimits() {
        assertEquals("SDV-APR-002", code(() -> f.approvals.requestHeldRedaction(TENANT, STEWARD_ID, null, f.documents.get(C1), SensitivityLevel.CONFIDENTIAL, "x".repeat(501), "[{}]", 1, "c")));
    }
}
