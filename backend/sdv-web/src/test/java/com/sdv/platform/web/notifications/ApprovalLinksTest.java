package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.ActionLink;
import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.approval.ActionTokens;
import com.documentplatform.notifications.approval.ApprovalAudit;
import com.documentplatform.notifications.approval.ApprovalLinks;
import com.documentplatform.notifications.approval.ApprovalRefusal;
import com.documentplatform.notifications.approval.ApprovalResult;
import com.documentplatform.notifications.approval.Approvals;
import com.documentplatform.notifications.approval.SignedInPerson;
import com.documentplatform.notifications.inmemory.InMemoryApprovalStore;
import com.documentplatform.notifications.inmemory.InMemoryConditionStore;
import com.documentplatform.notifications.inmemory.InMemoryOutboxStore;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.inmemory.InMemorySummaryStore;
import com.documentplatform.notifications.secret.SecretRef;
import com.sdv.platform.application.approvals.AccessRequestKind;
import com.sdv.platform.application.approvals.AccessRequestStatus;
import com.sdv.platform.application.port.AccessRequestRepository;
import com.sdv.platform.application.port.AccessRequestRepository.AccessRequest;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.NotificationPort;
import com.sdv.platform.domain.audit.AuditEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Task 0082: one-click links for the requests decided on Vero's link page. Links go to each Business Admin on their own,
 * never to the requester; a link only opens (decides nothing); a decision in Vero closes the link's request, after which
 * every link says "already decided"; refusals are audited without the token.
 */
class ApprovalLinksTest {
    static final String KEY = "notifications/approval-link-key";
    static final String RECORD = "LN 12345";

    final NotificationKit kit = new NotificationKit();
    final InMemoryApprovalStore store = new InMemoryApprovalStore();
    final ActionTokens tokens = new ActionTokens(new InMemorySecretResolver().put(SecretRef.of(KEY), "k".repeat(48)), SecretRef.of(KEY));
    final Notifier notifier = Notifier.builder(kit.settings, new ListRecipients(kit.lists, kit.lists.holders))
            .channels(List.of(kit.email, kit.teams, kit.slack))
            .outbox(new InMemoryOutboxStore()).summaries(new InMemorySummaryStore()).conditions(new InMemoryConditionStore())
            .actionLinks(new ApprovalLinks(store, tokens, AppLink.of("/approvals/link"))).clock(kit.clock).build();
    final List<ApprovalAudit.Entry> audited = new ArrayList<>();
    final Approvals approvals = Approvals.builder(List.of(
                    VeroNotificationsConfiguration.approvalType("print", 7),
                    VeroNotificationsConfiguration.approvalType("download", 7),
                    VeroNotificationsConfiguration.approvalType("lower-level", 7)),
            kit.settings.catalogue(), store, tokens, notifier).approvers(com.documentplatform.notifications.approval.ApproverCheck.from(new ListRecipients(kit.lists, kit.lists.holders)))
            .audit(audited::add).clock(kit.clock).build();
    final Repo requests = new Repo();
    final NotificationPort plain = mock(NotificationPort.class);
    final VeroApprovalLinks links = new VeroApprovalLinks(approvals, requests.port, kit.lists, e -> Optional.of(NotificationKit.CUSTOMER), plain);

    private AccessRequest request(AccessRequestKind kind, String requester) {
        Instant now = kit.clock.instant();
        return new AccessRequest(UUID.randomUUID(), NotificationKit.ENTITY, kind, UUID.randomUUID(), null, null, null, "why",
                requester, "Rae Steward", now, AccessRequestStatus.PENDING, null, null, null, now.plusSeconds(7 * 86400), null);
    }

    private List<String> tokensOf(OutgoingMessage m) {
        return m.message().actions().stream().map(ActionLink::url).map(u -> u.getPath().substring(u.getPath().lastIndexOf('/') + 1)).toList();
    }

    @Test
    void aPrintRequestSendsEachBusinessAdminTheirOwnLinksWithoutTheRecord() {
        AccessRequest r = request(AccessRequestKind.PRINT, "rs-1");
        assertTrue(links.offer(r), "links go, so no plain message");
        notifier.tick(kit.clock.instant());
        assertEquals(2, kit.email.sent().size(), "one e-mail per Business Admin");
        assertTrue(kit.teams.sent().isEmpty() && kit.slack.sent().isEmpty(), "links go by e-mail only");
        for (OutgoingMessage m : kit.email.sent()) {
            assertEquals(1, m.recipients().size());
            assertFalse(m.recipients().iterator().next().userId().equals("rs-1"), "never to the requester");
            assertEquals(List.of("Approve", "Decline"), m.message().actions().stream().map(ActionLink::label).toList());
            assertTrue(m.message().actions().stream().allMatch(a -> a.url().toString().startsWith("https://vero.example.test/approvals/link/")));
            assertFalse(m.message().text().contains(RECORD));
            assertFalse(m.message().text().toLowerCase().contains("confidential"));
        }
        assertTrue(requests.links.containsKey(r.requestId()), "Vero keeps the pair");
    }

    @Test
    void openingDecidesNothingAndALinkWorksOnlyForItsPerson() {
        AccessRequest r = request(AccessRequestKind.DOWNLOAD, "rs-1");
        links.offer(r);
        notifier.tick(kit.clock.instant());
        OutgoingMessage toFirst = kit.email.sent().stream().filter(m -> m.recipients().iterator().next().userId().equals("ba-1")).findFirst().orElseThrow();
        String approve = tokensOf(toFirst).getFirst();
        SignedInPerson first = new SignedInPerson(NotificationKit.TENANT, "ba-1", "Bea Admin");
        assertInstanceOf(ApprovalResult.Ready.class, approvals.open(approve, first));
        assertInstanceOf(ApprovalResult.Ready.class, approvals.open(approve, first), "opening twice still decides nothing");
        ApprovalResult other = approvals.open(approve, new SignedInPerson(NotificationKit.TENANT, "ba-2", "Ben Admin"));
        assertEquals(ApprovalRefusal.LINK_NOT_FOR_YOU, ((ApprovalResult.Refused) other).refusal());
        ApprovalResult own = approvals.open(approve, new SignedInPerson(NotificationKit.TENANT, "rs-1", "Rae Steward"));
        assertInstanceOf(ApprovalResult.Refused.class, own, "the requester can't use a link");
        assertTrue(audited.isEmpty(), "opening is not a decision");
    }

    @Test
    void withVerosApproverCheckAListedPersonWithoutTheRoleNowIsRefused() {
        // Task 0100 (VSEC-201): platform-core asks Vero's check, not the list.
        var policies = mock(com.sdv.platform.application.port.RoleCapabilityPolicyPort.class);
        org.mockito.Mockito.when(policies.findOverrides(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Map.of());
        var signedIn = new java.util.concurrent.atomic.AtomicReference<com.sdv.platform.security.auth.SdvPrincipal>();
        Approvals checked = Approvals.builder(List.of(VeroNotificationsConfiguration.approvalType("print", 7)),
                        kit.settings.catalogue(), store, tokens, notifier)
                .approvers(new SignedInApprover(() -> Optional.ofNullable(signedIn.get()),
                        new com.sdv.platform.security.auth.CapabilityPolicyService(policies), e -> Optional.of(NotificationKit.CUSTOMER)))
                .audit(audited::add).clock(kit.clock).build();
        links.offer(request(AccessRequestKind.PRINT, "rs-1"));
        notifier.tick(kit.clock.instant());
        OutgoingMessage toFirst = kit.email.sent().stream().filter(m -> m.recipients().iterator().next().userId().equals("ba-1")).findFirst().orElseThrow();
        String approve = tokensOf(toFirst).getFirst();
        SignedInPerson first = new SignedInPerson(NotificationKit.TENANT, "ba-1", "Bea Admin");
        signedIn.set(new com.sdv.platform.security.auth.SdvPrincipal(NotificationKit.ENTITY, "ba-1", java.util.Set.of(com.sdv.platform.security.SdvRole.BUSINESS_USER)));
        assertEquals(ApprovalRefusal.NOT_AN_APPROVER, ((ApprovalResult.Refused) checked.open(approve, first)).refusal());
        signedIn.set(new com.sdv.platform.security.auth.SdvPrincipal(NotificationKit.ENTITY, "ba-1", java.util.Set.of(com.sdv.platform.security.SdvRole.BUSINESS_ADMIN)));
        assertInstanceOf(ApprovalResult.Ready.class, checked.open(approve, first));
    }

    @Test
    void aDecisionInVeroClosesTheLinksAndIsAuditedWithoutTheToken() {
        AccessRequest r = request(AccessRequestKind.LOWER_LEVEL, "rs-1");
        links.offer(r);
        notifier.tick(kit.clock.instant());
        String token = tokensOf(kit.email.sent().getFirst()).getFirst();
        SignedInPerson person = new SignedInPerson(NotificationKit.TENANT, kit.email.sent().getFirst().recipients().iterator().next().userId(), "Bea");
        links.decided(NotificationKit.ENTITY, r.requestId(), false, "ba-2", "Ben Admin", kit.clock.instant());
        ApprovalResult after = approvals.open(token, person);
        ApprovalResult.AlreadyDecided done = assertInstanceOf(ApprovalResult.AlreadyDecided.class, after);
        assertEquals("Ben Admin", done.request().decision().byName());
        assertEquals(1, audited.size());
        assertEquals(ApprovalAudit.Outcome.DECIDED, audited.getFirst().outcome());

        AuditEventPort port = mock(AuditEventPort.class);
        new LinkAudit(port).recordElsewhere(audited.getFirst(), VeroApprovalLinks.IN_VERO);
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(port).append(event.capture());
        assertEquals(LinkAudit.ACTION, event.getValue().action());
        assertFalse(event.getValue().safeDetailsJson().contains(token), "never the token");
        assertFalse(event.getValue().safeDetailsJson().contains(VeroApprovalLinks.DECLINED_IN_VERO), "never the reason");
    }

    @Test
    void requestsDecidedInTheirReviewAndARequesterWhoApprovesGetThePlainMessage() {
        for (AccessRequestKind kind : List.of(AccessRequestKind.LOWER_RECORD_LEVEL, AccessRequestKind.REDACT_HELD, AccessRequestKind.RELEASE_HOLD)) {
            assertFalse(links.offer(request(kind, "rs-1")), kind + " is decided in its review");
        }
        assertFalse(links.offer(request(AccessRequestKind.PRINT, "ba-1")), "the requester is a Business Admin: no links");
        notifier.tick(kit.clock.instant());
        assertTrue(kit.all().isEmpty(), "offer sends nothing when it says no; the caller sends the plain message");
        assertTrue(store.find(NotificationKit.TENANT, UUID.randomUUID()).isEmpty());
    }

    /** Q-4: a link lives as long as a request waits (Vero's pending days), at most the module's 30 days. */
    @Test
    void aLinkLivesAsLongAsTheRequestWaitsAtMostThirtyDays() {
        assertEquals(java.time.Duration.ofDays(7), VeroNotificationsConfiguration.approvalType("print", 7).validFor());
        assertEquals(java.time.Duration.ofDays(30), VeroNotificationsConfiguration.approvalType("print", 90).validFor());
        assertEquals(java.time.Duration.ofDays(1), VeroNotificationsConfiguration.approvalType("print", 0).validFor());
    }

    @Test
    void aDecisionOfARequestWithoutLinksChangesNothing() {
        links.decided(NotificationKit.ENTITY, UUID.randomUUID(), true, "ba-1", "Bea", kit.clock.instant());
        assertTrue(audited.isEmpty());
    }

    /** The pairs Vero keeps (changeset 050), over a mock of the rest. */
    static final class Repo {
        final Map<UUID, UUID> links = new ConcurrentHashMap<>();
        final AccessRequestRepository port = mock(AccessRequestRepository.class);

        Repo() {
            org.mockito.Mockito.when(port.setLinkRequest(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(c -> links.putIfAbsent(c.getArgument(1), c.getArgument(2)) == null);
            org.mockito.Mockito.when(port.findLinkRequest(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(c -> Optional.ofNullable(links.get(c.<UUID>getArgument(1))));
        }
    }
}
