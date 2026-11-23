package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 0055: the Restrictions page's reads (one tenant per call). Never a title, file name, content, reason or the
 * internal document ID: records are named by their primary identifier only (separation rule 4).
 */
public interface RestrictionsRepository {

    /** A record with a record (contract) level above Standard, as kept: its sending system, key, level, when set. */
    record RecordLevel(UUID levelId, UUID sourceId, String recordKey, String level, Instant setAt) { }

    /** A level change: who (name, else sign-in ID), when, DOCUMENT or RECORD, from, to; the record it belongs to. */
    record LevelChange(UUID changeId, String changedBy, Instant changedAt, String scope, String fromLevel, String toLevel,
                       UUID sourceId, String recordKey, UUID submissionId) { }

    /** A record's primary identifier: its label and value. */
    record Identifier(String label, String value) { }

    /** Waiting lowering requests: how many, and when the oldest was made. */
    record Waiting(long count, Instant oldest) { }

    /** Legal holds in place, and the documents they hold. */
    record Holds(long holds, long documents) { }

    /** Open quarantine incidents, the documents still quarantined in them, and those still being prepared. */
    record Quarantine(long incidents, long documents, long preparing) { }

    long countRestrictedRecords(UUID tenantId);

    /** A page of restricted records, newest set first, after {@code after} (null: the first page). */
    List<RecordLevel> findRestrictedRecords(UUID tenantId, RecordLevel after, int limit);

    /** A page of level changes since {@code since}, newest first, after {@code after} (null: the first page). */
    List<LevelChange> findLevelChanges(UUID tenantId, Instant since, LevelChange after, int limit);

    long countLevelChanges(UUID tenantId, Instant since);

    /** Each submission's primary identifier (its first business identifier in display order). */
    Map<UUID, Identifier> findSubmissionIdentifiers(UUID tenantId, Collection<UUID> submissionIds);

    /** Each sending system's identifier labels by field key (the record keys name fields by key). */
    Map<UUID, Map<String, String>> findIdentifierLabels(UUID tenantId, Collection<UUID> sourceIds);

    /** Each sending system's primary identifier field (its first business identifier in display order). */
    Map<UUID, String> findPrimaryFields(UUID tenantId, Collection<UUID> sourceIds);

    Waiting findWaitingLowerings(UUID tenantId);

    Holds countHolds(UUID tenantId);

    Quarantine countQuarantine(UUID tenantId);
}
