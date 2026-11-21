package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0033 (0.1): the stored notices for Document stewards and Restricted stewards (steward_notices, changeset 032).
 * A notice is unread for a person until their own entry is in its read_by; each person marks their own.
 */
public interface StewardNoticeRepository {

    /** The notices this person hasn't marked read, newest first, at most {@code limit}. */
    List<StoredNotice> findUnread(UUID tenantId, String subject, int limit);

    /** How many notices this person hasn't marked read. */
    int countUnread(UUID tenantId, String subject);

    /** Which of these notices are the tenant's. */
    Set<UUID> findExisting(UUID tenantId, Collection<UUID> noticeIds);

    /**
     * Marks these notices read by this person at {@code at}; a notice they had already marked keeps its first time.
     * Returns how many were newly marked.
     */
    int markRead(UUID tenantId, Collection<UUID> noticeIds, String subject, Instant at);

    /** sendingSystem: the sending system's name; recordIdentifier and its label: the record's primary identifier. */
    record StoredNotice(UUID noticeId, String kind, Instant createdAt, UUID sourceId, String sendingSystem,
                        String recordIdentifier, String recordIdentifierLabel) { }
}
