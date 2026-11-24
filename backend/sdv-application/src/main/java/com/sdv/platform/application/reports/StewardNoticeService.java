package com.sdv.platform.application.reports;

import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.domain.error.InternalCheckFailed;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.StewardNoticeRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0033 (0.1): the stored notices for Document stewards and Restricted stewards. An uncorrected copy of a corrected
 * document is a security event, so its notice is kept in the database (steward_notices), not per browser: each steward
 * sees it, wherever they sign in, until they mark it read themselves. Marking read is audited (who, when, which).
 * <p>
 * A notice names the record by its sending system and primary identifier, never a file name, title or content.
 */
@Service
public class StewardNoticeService {
    public static final String READ_ACTION = "STEWARD_NOTICE_READ";
    public static final String UNCORRECTED_COPY_MESSAGE = IntakeReportService.NOTICE_MESSAGE;
    /** Notices returned at once; {@link Notices#unread()} says how many there are in all. */
    static final int LIMIT = 50;
    /** Notices marked read in one call. */
    public static final int MARK_MAX = 100;
    private static final ObjectMapper JSON = VeroJson.mapper();

    private final StewardNoticeRepository notices;
    private final AuditEventPort audit;
    private final Clock clock;

    @Autowired
    public StewardNoticeService(StewardNoticeRepository notices, AuditEventPort audit) {
        this(notices, audit, Clock.systemUTC());
    }

    StewardNoticeService(StewardNoticeRepository notices, AuditEventPort audit, Clock clock) {
        this.notices = Objects.requireNonNull(notices, "notices");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** This person's unread notices, newest first (at most 50), and how many are unread in all. */
    public Notices unread(UUID tenantId, String subject) {
        List<Notice> list = notices.findUnread(tenantId, subject, LIMIT).stream()
                .map(n -> new Notice(n.noticeId(), n.kind(), n.createdAt(), n.sendingSystem(), n.recordIdentifier(),
                        n.recordIdentifierLabel(), message(n.kind())))
                .toList();
        int unread = list.size() < LIMIT ? list.size() : notices.countUnread(tenantId, subject);
        return new Notices(list, unread);
    }

    /**
     * Marks these notices read by this person. All must be the tenant's (SDV-INT-002, nothing is marked otherwise);
     * 1 to 100 at once (SDV-INT-003). Marking one already read again changes nothing and is not audited again.
     */
    @Transactional
    public int markRead(UUID tenantId, String subject, String correlationId, List<UUID> noticeIds) {
        if (noticeIds == null || noticeIds.isEmpty() || noticeIds.size() > MARK_MAX
                || noticeIds.stream().anyMatch(Objects::isNull)) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.INT_003,
                    "Name 1 to " + MARK_MAX + " notices to mark as read.");
        }
        Set<UUID> wanted = new LinkedHashSet<>(noticeIds);
        Set<UUID> found = notices.findExisting(tenantId, wanted);
        if (!found.containsAll(wanted)) {
            throw new CodedRefusal.NotFound(SdvErrorCode.INT_002, "No notice has this reference.");
        }
        Instant now = clock.instant();
        int marked = notices.markRead(tenantId, wanted, subject, now);
        if (marked > 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("noticeIds", wanted.stream().map(UUID::toString).toList());
            details.put("markedCount", marked);
            audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", subject, READ_ACTION, "STEWARD_NOTICE",
                    wanted.size() == 1 ? wanted.iterator().next().toString() : "MANY", now, correlationId,
                    AuditOutcome.SUCCESS, json(details)));
        }
        return marked;
    }

    static String message(String kind) {
        if ("UNCORRECTED_COPY_RECEIVED".equals(kind)) return UNCORRECTED_COPY_MESSAGE;
        throw new InternalCheckFailed("A notice of an unknown kind can't be shown: " + kind);
    }

    private static String json(Map<String, Object> details) {
        try {
            return JSON.writeValueAsString(details);
        } catch (JacksonException ex) {
            throw InternalCheckFailed.auditNotWritten("Marking the notice read could not be audited, so it was not marked.", ex);
        }
    }

    /**
     * One notice. The field names are those task 0032's notices had: receivedAt is when the copy arrived (the notice's
     * time); primaryIdentifier and its label name the record.
     */
    public record Notice(UUID noticeId, String kind, Instant receivedAt, String sendingSystem, String primaryIdentifier,
                         String primaryIdentifierLabel, String message) { }

    public record Notices(List<Notice> notices, int unread) { }
}
