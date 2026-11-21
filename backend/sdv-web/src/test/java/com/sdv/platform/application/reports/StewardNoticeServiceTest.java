package com.sdv.platform.application.reports;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.StewardNoticeRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.error.CodedRefusal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Task 0033 (0.1): stored notices for stewards. Each person sees a notice until they mark it read themselves; marking
 * is checked (the tenant's notices, 1 to 100 at once) and audited once.
 */
class StewardNoticeServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-11-21T15:00:00Z");
    private final FakeNotices repo = new FakeNotices();
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final StewardNoticeService service = new StewardNoticeService(repo, audit, Clock.fixed(NOW, ZoneOffset.UTC));

    private UUID notice(String identifier) {
        UUID id = UUID.randomUUID();
        repo.notices.add(new StewardNoticeRepository.StoredNotice(id, "UNCORRECTED_COPY_RECEIVED",
                NOW.minusSeconds(repo.notices.size() * 60L), UUID.randomUUID(), "LexaVault", identifier, "Contract number"));
        return id;
    }

    @Test
    void eachStewardSeesANoticeUntilTheyMarkItRead() {
        UUID first = notice("300000000001");
        UUID second = notice("300000000002");
        StewardNoticeService.Notices dee = service.unread(TENANT, "dee");
        assertEquals(2, dee.unread());
        assertEquals("An uncorrected copy of a corrected document was sent again; nothing was stored", dee.notices().get(0).message());
        assertEquals("300000000001", dee.notices().get(0).primaryIdentifier());
        assertEquals("Contract number", dee.notices().get(0).primaryIdentifierLabel());
        assertEquals("LexaVault", dee.notices().get(0).sendingSystem());

        assertEquals(1, service.markRead(TENANT, "dee", "corr", List.of(first)));
        assertEquals(List.of(second), service.unread(TENANT, "dee").notices().stream().map(StewardNoticeService.Notice::noticeId).toList());
        assertEquals(2, service.unread(TENANT, "rae").unread(), "Dee's mark is Dee's only");

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertEquals("STEWARD_NOTICE_READ", event.getValue().action());
        assertEquals("dee", event.getValue().actorId());
        assertEquals(first.toString(), event.getValue().targetId());
        assertEquals("{\"noticeIds\":[\"" + first + "\"],\"markedCount\":1}", event.getValue().safeDetailsJson());

        // Again: nothing changes and nothing more is audited.
        assertEquals(0, service.markRead(TENANT, "dee", "corr", List.of(first)));
        verifyNoMoreInteractions(audit);
    }

    @Test
    void markingIsCheckedBeforeAnythingIsMarked() {
        UUID mine = notice("300000000001");
        assertEquals("SDV-INT-003", code(() -> service.markRead(TENANT, "dee", "c", null)));
        assertEquals("SDV-INT-003", code(() -> service.markRead(TENANT, "dee", "c", List.of())));
        assertEquals("SDV-INT-003", code(() -> service.markRead(TENANT, "dee", "c", Arrays.asList(mine, null))));
        List<UUID> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) many.add(mine);
        assertEquals("SDV-INT-003", code(() -> service.markRead(TENANT, "dee", "c", many)));
        assertEquals("SDV-INT-002", code(() -> service.markRead(TENANT, "dee", "c", List.of(mine, UUID.randomUUID()))));
        assertEquals(1, service.unread(TENANT, "dee").unread(), "nothing was marked");
        verifyNoInteractions(audit);
    }

    @Test
    void moreThanFiftyAreCountedInFull() {
        for (int i = 0; i < 53; i++) notice("3000000000" + i);
        StewardNoticeService.Notices n = service.unread(TENANT, "dee");
        assertEquals(50, n.notices().size());
        assertEquals(53, n.unread());
    }

    @Test
    void aNoticeOfAKindVeroDoesntKnowIsNotShownAsSomethingElse() {
        repo.notices.add(new StewardNoticeRepository.StoredNotice(UUID.randomUUID(), "SOMETHING", NOW, null, null, null, null));
        assertThrows(IllegalStateException.class, () -> service.unread(TENANT, "dee"));
    }

    private static String code(org.junit.jupiter.api.function.Executable call) {
        Throwable t = assertThrows(RuntimeException.class, call);
        return ((CodedRefusal) t).errorCode().code();
    }

    static final class FakeNotices implements StewardNoticeRepository {
        final List<StoredNotice> notices = new ArrayList<>();
        final Map<UUID, Set<String>> readBy = new HashMap<>();

        private boolean unread(StoredNotice n, String subject) {
            return !readBy.getOrDefault(n.noticeId(), Set.of()).contains(subject);
        }

        @Override public List<StoredNotice> findUnread(UUID tenantId, String subject, int limit) {
            return notices.stream().filter(n -> unread(n, subject)).limit(limit).toList();
        }

        @Override public int countUnread(UUID tenantId, String subject) {
            return (int) notices.stream().filter(n -> unread(n, subject)).count();
        }

        @Override public Set<UUID> findExisting(UUID tenantId, Collection<UUID> ids) {
            Set<UUID> out = new HashSet<>();
            notices.forEach(n -> { if (ids.contains(n.noticeId())) out.add(n.noticeId()); });
            return out;
        }

        @Override public int markRead(UUID tenantId, Collection<UUID> ids, String subject, Instant at) {
            int marked = 0;
            for (UUID id : ids) if (readBy.computeIfAbsent(id, k -> new HashSet<>()).add(subject)) marked++;
            return marked;
        }
    }
}
