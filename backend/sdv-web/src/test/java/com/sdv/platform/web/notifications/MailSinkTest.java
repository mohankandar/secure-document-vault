package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.TenantSettings;
import com.documentplatform.notifications.email.EmailChannel;
import com.documentplatform.notifications.email.MailSecurity;
import com.documentplatform.notifications.email.MailServer;
import com.documentplatform.notifications.email.MailServerDirectory;
import com.documentplatform.notifications.inmemory.InMemoryConditionStore;
import com.documentplatform.notifications.inmemory.InMemoryOutboxStore;
import com.documentplatform.notifications.inmemory.InMemorySettingsStore;
import com.documentplatform.notifications.inmemory.InMemorySummaryStore;
import com.sdv.platform.application.notifications.VeroEvent;
import com.sdv.platform.application.notifications.VeroNotice;
import com.sdv.platform.domain.document.SensitivityLevel;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0081: a run against a local mail sink. Platform-core's real e-mail sender, over SMTP on this computer, to a
 * sink in this test that keeps what it is given and sends nothing on (no message ever goes to a real address). Each
 * Business Admin gets their own e-mail; the text is the event, the record's identifier, the count, the time and the
 * link; never another person's address.
 */
class MailSinkTest {

    @Test
    void approvalWaitingReachesTheMailSinkOncePerBusinessAdmin() throws Exception {
        try (SmtpSink sink = new SmtpSink()) {
            NotificationKit.Lists lists = new NotificationKit.Lists();
            lists.add(NotificationKit.ENTITY, "BUSINESS_ADMIN", "ba-1", "business.admin@example.test");
            lists.add(NotificationKit.ENTITY, "BUSINESS_ADMIN", "ba-2", "second.admin@example.test");
            NotificationSettings settings = new NotificationSettings(VeroCatalogue.build(), new InMemorySettingsStore());
            DestinationId email = NotificationSettingsService.EMAIL;
            settings.connectChannels(NotificationKit.TENANT, new TenantSettings(URI.create("https://vero.example.test"),
                    ZoneId.of("America/New_York"), Map.of(email, ChannelKind.EMAIL)));
            MailServer server = MailServer.relay("127.0.0.1", sink.port(), MailSecurity.NONE, "vero@example.test");
            EmailChannel channel = new EmailChannel(MailServerDirectory.of(Map.of(email, server)), ref -> Optional.empty());
            Notifier notifier = Notifier.builder(settings, new ListRecipients(lists, lists.holders, NotificationKit.WINDOW)).channel(channel)
                    .outbox(new InMemoryOutboxStore()).summaries(new InMemorySummaryStore()).conditions(new InMemoryConditionStore())
                    .build();
            VeroNotifications port = new VeroNotifications(notifier, settings, e -> Optional.of(NotificationKit.CUSTOMER), NotificationKit.CUSTOMER);
            // Immediately (the default is every 4 hours).
            new NotificationSettingsService(settings, new com.documentplatform.notifications.spring.NotificationsProperties(), Map::of, PublicUrl.of("https://vero.example.test", false))
                    .change(NotificationKit.TENANT, com.documentplatform.notifications.EntityId.of(NotificationKit.ENTITY.toString()),
                            VeroEvent.APPROVAL_WAITING.id(), new NotificationSettingsService.Change(true, "IMMEDIATELY", null, true, List.of()));

            port.publish(VeroNotice.of(VeroEvent.APPROVAL_WAITING, NotificationKit.ENTITY).record("LN 12345", SensitivityLevel.STANDARD)
                    .time(VeroEvent.TIME_EXPIRES, Instant.parse("2026-12-04T15:00:00Z")).build());
            notifier.tick(Instant.now());

            List<SmtpSink.Mail> mails = sink.mails();
            assertEquals(2, mails.size(), "one e-mail per Business Admin");
            assertEquals(List.of("<business.admin@example.test>", "<second.admin@example.test>"),
                    mails.stream().map(m -> String.join(",", m.to())).sorted().toList());
            for (SmtpSink.Mail m : mails) {
                assertEquals(1, m.to().size());
                String other = m.to().getFirst().contains("second") ? "business.admin@" : "second.admin@";
                assertFalse(m.data().contains(other), "never another recipient's address");
                assertTrue(m.data().contains("Approval waiting"), m.data());
                assertTrue(m.data().contains("LN 12345"), m.data());
                assertTrue(m.data().contains("https://vero.example.test/governance/approvals"), m.data());
                assertTrue(m.data().contains("Auto-Submitted: auto-generated"), "out-of-office replies are held back");
                assertFalse(m.data().toLowerCase(java.util.Locale.ROOT).contains("confidential"));
            }
        }
    }

    /** Task 0082: a print request's e-mail with its two links, through the real sender to the sink. */
    @Test
    void aLinkEmailReachesTheMailSinkWithItsOwnLinksAndNoRecord() throws Exception {
        try (SmtpSink sink = new SmtpSink()) {
            NotificationKit.Lists lists = new NotificationKit.Lists();
            lists.add(NotificationKit.ENTITY, "BUSINESS_ADMIN", "ba-1", "business.admin@example.test");
            lists.add(NotificationKit.ENTITY, "BUSINESS_ADMIN", "ba-2", "second.admin@example.test");
            NotificationSettings settings = new NotificationSettings(VeroCatalogue.build(), new InMemorySettingsStore());
            DestinationId email = NotificationSettingsService.EMAIL;
            settings.connectChannels(NotificationKit.TENANT, new TenantSettings(URI.create("https://vero.example.test"),
                    ZoneId.of("America/New_York"), Map.of(email, ChannelKind.EMAIL)));
            var store = new com.documentplatform.notifications.inmemory.InMemoryApprovalStore();
            var key = com.documentplatform.notifications.secret.SecretRef.of("notifications/approval-link-key");
            var tokens = new com.documentplatform.notifications.approval.ActionTokens(
                    new com.documentplatform.notifications.inmemory.InMemorySecretResolver().put(key, "k".repeat(48)), key);
            EmailChannel channel = new EmailChannel(MailServerDirectory.of(Map.of(email,
                    MailServer.relay("127.0.0.1", sink.port(), MailSecurity.NONE, "vero@example.test"))), ref -> Optional.empty());
            Notifier notifier = Notifier.builder(settings, new ListRecipients(lists, lists.holders, NotificationKit.WINDOW)).channel(channel)
                    .outbox(new InMemoryOutboxStore()).summaries(new InMemorySummaryStore()).conditions(new InMemoryConditionStore())
                    .actionLinks(new com.documentplatform.notifications.approval.ApprovalLinks(store, tokens,
                            com.documentplatform.notifications.AppLink.of("/approvals/link")))
                    .build();
            var approvals = com.documentplatform.notifications.approval.Approvals.builder(
                            List.of(VeroNotificationsConfiguration.approvalType("print", 7)), settings.catalogue(), store, tokens, notifier)
                    .approvers(com.documentplatform.notifications.approval.ApproverCheck.from(new ListRecipients(lists, lists.holders, NotificationKit.WINDOW))).audit(e -> { }).build();
            var requests = new ApprovalLinksTest.Repo();
            var links = new VeroApprovalLinks(approvals, requests.port, lists, e -> Optional.of(NotificationKit.CUSTOMER),
                    org.mockito.Mockito.mock(com.sdv.platform.application.port.NotificationPort.class));
            Instant now = Instant.now();
            links.offer(new com.sdv.platform.application.port.AccessRequestRepository.AccessRequest(java.util.UUID.randomUUID(),
                    NotificationKit.ENTITY, com.sdv.platform.application.approvals.AccessRequestKind.PRINT, java.util.UUID.randomUUID(),
                    null, null, null, "why", "rs-1", "Rae", now, com.sdv.platform.application.approvals.AccessRequestStatus.PENDING,
                    null, null, null, now.plusSeconds(86400), null));
            notifier.tick(Instant.now());

            List<SmtpSink.Mail> mails = sink.mails();
            assertEquals(2, mails.size(), "one e-mail per Business Admin");
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (SmtpSink.Mail m : mails) {
                String text = m.data().replace("=\n", "");
                assertTrue(text.contains("Approval waiting"), text);
                assertTrue(text.contains("https://vero.example.test/approvals/link/"), "the links: " + text);
                java.util.regex.Matcher link = java.util.regex.Pattern.compile("/approvals/link/([A-Za-z0-9_.=-]+)").matcher(text);
                while (link.find()) assertTrue(seen.add(link.group(1)), "every person has their own links");
                assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("confidential"));
            }
            assertEquals(4, seen.size(), "Approve and Decline, for each of the two");
        }
    }

    /** The smallest SMTP server that takes mail on 127.0.0.1 and keeps it in memory. */
    static final class SmtpSink implements AutoCloseable {
        record Mail(String from, List<String> to, String data) { }

        private final ServerSocket socket = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        private final List<Mail> mails = new CopyOnWriteArrayList<>();
        private final Thread thread;

        SmtpSink() throws IOException {
            thread = new Thread(this::serve, "smtp-sink");
            thread.setDaemon(true);
            thread.start();
        }

        int port() { return socket.getLocalPort(); }

        List<Mail> mails() { return List.copyOf(mails); }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket s = socket.accept()) {
                    s.setSoTimeout(10_000);
                    talk(s);
                } catch (IOException ex) {
                    // closed, or a client gone: take the next one
                }
            }
        }

        private void talk(Socket s) throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.getOutputStream();
            reply(out, "220 sink ready");
            String from = null;
            List<String> to = new ArrayList<>();
            String line;
            while ((line = in.readLine()) != null) {
                String upper = line.toUpperCase(java.util.Locale.ROOT);
                if (upper.startsWith("EHLO") || upper.startsWith("HELO")) reply(out, "250 sink");
                else if (upper.startsWith("MAIL FROM:")) { from = line.substring(10).trim(); to = new ArrayList<>(); reply(out, "250 ok"); }
                else if (upper.startsWith("RCPT TO:")) { to.add(line.substring(8).trim()); reply(out, "250 ok"); }
                else if (upper.startsWith("DATA")) {
                    reply(out, "354 go on");
                    StringBuilder data = new StringBuilder();
                    String d;
                    while ((d = in.readLine()) != null && !d.equals(".")) data.append(d).append('\n');
                    mails.add(new Mail(from, List.copyOf(to), data.toString()));
                    reply(out, "250 kept");
                } else if (upper.startsWith("RSET") || upper.startsWith("NOOP")) reply(out, "250 ok");
                else if (upper.startsWith("QUIT")) { reply(out, "221 bye"); return; }
                else reply(out, "502 not here");
            }
        }

        private static void reply(OutputStream out, String text) throws IOException {
            out.write((text + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
