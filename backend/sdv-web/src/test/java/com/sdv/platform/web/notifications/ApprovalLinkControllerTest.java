package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.ActionLink;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.approval.ApprovalAudit;
import com.documentplatform.notifications.approval.ApprovalRefusal;
import com.sdv.platform.application.approvals.AccessRequestKind;
import com.sdv.platform.application.approvals.AccessRequestService;
import com.sdv.platform.application.approvals.AccessRequestStatus;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 0082: Vero's link page. Opening decides nothing; the button decides through Vero; refusals are audited. */
class ApprovalLinkControllerTest {
    final ApprovalLinksTest links = new ApprovalLinksTest();
    final SecurityContextFacade security = mock(SecurityContextFacade.class);
    final AuthorizationService authorization = mock(AuthorizationService.class);
    final AccessRequestService requests = mock(AccessRequestService.class);
    final PlatformIdentityReads identity = mock(PlatformIdentityReads.class);
    @SuppressWarnings("unchecked")
    final ObjectProvider<com.documentplatform.notifications.approval.Approvals> approvals = mock(ObjectProvider.class);
    final ApprovalAudit audit = mock(ApprovalAudit.class);
    final ApprovalLinkController page;
    final UUID veroRequest = UUID.randomUUID();

    ApprovalLinkControllerTest() {
        when(approvals.getIfAvailable()).thenReturn(links.approvals);
        when(identity.slotOf(NotificationKit.ENTITY)).thenReturn(Optional.of(new PlatformIdentityReads.EntitySlot(NotificationKit.CUSTOMER, "ENT-A", "ACTIVE", "A")));
        when(links.requests.port.findByLinkRequest(any(), any())).thenAnswer(c -> Optional.of(veroRequest));
        page = new ApprovalLinkController(security, authorization, approvals, audit, links.requests.port, requests, identity);
    }

    private String approveToken(String forUser) {
        var r = new com.sdv.platform.application.port.AccessRequestRepository.AccessRequest(UUID.randomUUID(), NotificationKit.ENTITY,
                AccessRequestKind.PRINT, UUID.randomUUID(), null, null, null, "why", "rs-1", "Rae", Instant.now(), AccessRequestStatus.PENDING,
                null, null, null, Instant.now().plusSeconds(86400), null);
        links.links.offer(r);
        links.notifier.tick(links.kit.clock.instant());
        OutgoingMessage m = links.kit.email.sent().stream().filter(x -> x.recipients().iterator().next().userId().equals(forUser)).findFirst().orElseThrow();
        ActionLink approve = m.message().actions().getFirst();
        String path = approve.url().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void signedIn(String subject, UUID entity) {
        when(security.requirePrincipal()).thenReturn(new SdvPrincipal(entity, subject, Set.of(SdvRole.BUSINESS_ADMIN), "Bea Admin", null));
        when(authorization.has(any(), eq(com.sdv.platform.security.Capability.APPROVE_SENSITIVE_ACCESS))).thenReturn(true);
    }

    private void waiting() {
        var view = new AccessRequestService.PendingRequestView(veroRequest, AccessRequestKind.PRINT, AccessRequestKind.Scope.DOCUMENT, "LN 1", "Loan",
                null, 3, null, null, null, null, "rs-1", "why", Instant.now(), UUID.randomUUID(), null, null, null, null);
        when(requests.pending(eq(NotificationKit.ENTITY), any(), any(), any())).thenReturn(new AccessRequestService.PendingList(
                List.of(new AccessRequestService.PendingGroup("LN 1", "Loan", List.of(view))), 1, 7));
    }

    @Test
    void openingShowsTheRequestAndDecidesNothing() {
        String token = approveToken("ba-1");
        signedIn("ba-1", NotificationKit.ENTITY);
        waiting();
        var v = page.open(new ApprovalLinkController.LinkRequest(token)).getBody();
        assertEquals("READY", v.state());
        assertEquals("APPROVE", v.action());
        assertEquals(veroRequest, v.request().requestId());
        verify(requests, never()).decide(any(), any(), any(), any(), any(), any());
    }

    @Test
    void theButtonDecidesThroughVero() {
        String token = approveToken("ba-1");
        signedIn("ba-1", NotificationKit.ENTITY);
        waiting();
        when(requests.decide(any(), any(), any(), any(), any(), any())).thenReturn(new AccessRequestService.DecisionView(veroRequest, AccessRequestStatus.APPROVED, null));
        var v = page.decide(new ApprovalLinkController.LinkRequest(token)).getBody();
        assertEquals("DECIDED", v.state());
        verify(requests).decide(NotificationKit.ENTITY, "ba-1", "Bea Admin", veroRequest.toString(), AccessRequestService.Decision.APPROVE, null);
    }

    @Test
    void decidedInVeroFirstSaysSo() {
        String token = approveToken("ba-1");
        signedIn("ba-1", NotificationKit.ENTITY);
        when(requests.pending(any(), any(), any(), any())).thenReturn(new AccessRequestService.PendingList(List.of(), 0, 7));
        var v = page.decide(new ApprovalLinkController.LinkRequest(token)).getBody();
        assertEquals("ALREADY_DECIDED", v.state());
        verify(requests, never()).decide(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRefusedLinkIsAuditedWithoutTheToken() {
        String token = approveToken("ba-1");
        signedIn("ba-2", NotificationKit.ENTITY);
        var v = page.decide(new ApprovalLinkController.LinkRequest(token)).getBody();
        assertEquals("REFUSED", v.state());
        assertEquals(ApprovalRefusal.LINK_NOT_FOR_YOU.name(), v.code());
        verify(audit).record(any());
        assertFalse(v.toString().contains(token));
        var bad = page.open(new ApprovalLinkController.LinkRequest("not-a-token")).getBody();
        assertEquals("REFUSED", bad.state());
        assertTrue(page.open(null).getBody().state().equals("REFUSED"));
    }

    @Test
    void someoneWhoMayNotDecideSeesNothingOfTheRequest() {
        // Task 0100 (VSEC-201): on the list, but without the approver's permission now.
        String token = approveToken("ba-1");
        when(security.requirePrincipal()).thenReturn(new SdvPrincipal(NotificationKit.ENTITY, "ba-1", Set.of(SdvRole.BUSINESS_USER), "Bea", null));
        when(authorization.has(any(), any())).thenReturn(false);
        waiting();
        for (var v : List.of(page.open(new ApprovalLinkController.LinkRequest(token)).getBody(),
                page.decide(new ApprovalLinkController.LinkRequest(token)).getBody())) {
            assertEquals("REFUSED", v.state());
            assertEquals(ApprovalRefusal.NOT_AN_APPROVER.name(), v.code());
            assertEquals("This request isn't yours to decide.", v.message());
            assertEquals(null, v.request());
            assertEquals(null, v.action());
            assertEquals(null, v.decidedByName());
        }
        verify(requests, never()).pending(any(), any(), any(), any());
        verify(requests, never()).decide(any(), any(), any(), any(), any(), any());
        var entries = org.mockito.ArgumentCaptor.forClass(ApprovalAudit.Entry.class);
        verify(audit, org.mockito.Mockito.times(2)).record(entries.capture());
        for (var e : entries.getAllValues()) {
            assertEquals(ApprovalRefusal.NOT_AN_APPROVER, e.refusal());
            assertFalse(e.toString().contains(token));
        }
    }

    @Test
    void aLinkOfAnotherEntityIsRefused() {
        String token = approveToken("ba-1");
        UUID other = UUID.randomUUID();
        when(identity.slotOf(other)).thenReturn(Optional.of(new PlatformIdentityReads.EntitySlot(NotificationKit.CUSTOMER, "ENT-B", "ACTIVE", "B")));
        signedIn("ba-1", other);
        CodedRefusal refusal = (CodedRefusal) assertThrows(RuntimeException.class, () -> page.open(new ApprovalLinkController.LinkRequest(token)));
        assertEquals("SDV-NTF-008", refusal.errorCode().code());
    }
}
