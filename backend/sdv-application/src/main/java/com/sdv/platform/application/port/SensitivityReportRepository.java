package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0033: what the Sensitivity report reads. Counts only: no name, title, file name, metadata value or content is
 * ever selected. Every query is limited to one tenant; sourceId null means every sending system.
 */
public interface SensitivityReportRepository {

    /**
     * Documents stored now (received, being finished, available, or being corrected; not failed, deleted or being
     * deleted), by effective level: the higher of the document's own level and its record's level, the same rule as
     * every document path (SensitivityLevel.effective). Also how many records have a record (contract) level, and how
     * many stored documents are on an active legal hold.
     */
    DocumentLevels countStoredDocuments(UUID tenantId, UUID sourceId);

    /**
     * Access requests made in [from, to), by kind and by what became of them, judged at {@code now}: APPROVED
     * (approved, used, or approved and its grant ran out), DECLINED, EXPIRED (nobody decided in time) and WAITING.
     */
    List<RequestCount> countRequests(UUID tenantId, UUID sourceId, Instant from, Instant to, Instant now);

    /** Level changes in [from, to), from the audit trail. */
    LevelChanges countLevelChanges(UUID tenantId, UUID sourceId, Instant from, Instant to);

    /**
     * Refused attempts to open a document the person's role can't open (DOCUMENT_ACCESS_REFUSED with SDV-SEN-008) in
     * [from, to), by the roles the refusal names (empty: the event names none), with how many people tried.
     */
    List<AttemptCount> countRestrictedAttempts(UUID tenantId, UUID sourceId, Instant from, Instant to);

    /** The display name of the primary identifier most sending systems use ("Contract number"); empty when none. */
    Optional<String> findPrimaryIdentifierLabel(UUID tenantId, UUID sourceId);

    record DocumentLevels(long standard, long confidential, long highlyConfidential, long recordsWithLevel,
                          long onLegalHold) { }

    /** kind: PRINT, DOWNLOAD, LOWER_LEVEL or LOWER_RECORD_LEVEL; outcome: APPROVED, DECLINED, EXPIRED or WAITING. */
    record RequestCount(String kind, String outcome, long count) { }

    /**
     * raisedBySteward: a Steward's raise of a document or record level; raisedBySendingSystem: a delivery raised a
     * record (contract) level, or a duplicate raised a document level; lowered: a lowering a Business Admin approved.
     */
    record LevelChanges(long raisedBySteward, long raisedBySendingSystem, long lowered) { }

    /** roles: the role names the refusal recorded, sorted; empty when it recorded none (before task 0033). */
    record AttemptCount(List<String> roles, long attempts, long people) { }
}
