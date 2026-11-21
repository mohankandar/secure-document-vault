package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0032: what the Daily intake report and the Stewards' intake notices read. Counts and identifiers only: no file
 * name, title, metadata other than the primary identifier, or content is ever selected.
 */
public interface IntakeReportRepository {

    /** The tenant's own time zone setting (tenants.time_zone, changeset 030); empty when it has none. */
    Optional<String> findTenantTimeZone(UUID tenantId);

    /** Every sending system of the tenant, by name. */
    List<SendingSystem> findSendingSystems(UUID tenantId);

    /**
     * What happened at intake in [from, to), oldest first, at most {@code limit} items: deliveries created
     * (submissions), documents stored, exact duplicates (task 0031, also uncorrected copies), and refusals
     * (INTAKE_REFUSED audit events). sourceId null: every sending system.
     */
    List<Item> findItems(UUID tenantId, UUID sourceId, Instant from, Instant to, int limit);

    /** The primary identifier of each of these submissions (the first business identifier in display order). */
    Map<UUID, PrimaryIdentifier> findPrimaryIdentifiers(UUID tenantId, Collection<UUID> submissionIds);

    /**
     * Which of these normalized identifier values a submission created before {@code before} already carried, in any
     * of these identifier fields and from any sending system of the tenant ("updated", not "new").
     */
    Set<String> findValuesKnownBefore(UUID tenantId, Collection<String> fieldKeys, Collection<String> normalizedValues,
                                      Instant before);

    /** The uncorrected copies received since {@code since} (DOCUMENT_UNCORRECTED_COPY_RECEIVED), newest first. */
    List<UncorrectedCopy> findUncorrectedCopies(UUID tenantId, Instant since, int limit);

    record SendingSystem(UUID sourceId, String displayName) { }

    /**
     * One thing that happened. kind DELIVERY: a submission was created (no document); DOCUMENT: a document was stored
     * (status says whether it failed after receipt); DUPLICATE: an exact duplicate (or an uncorrected copy) was
     * acknowledged and not stored; REFUSED: a delivery or upload was refused (refusalCode), with submissionId null when
     * it was refused before a submission existed. requestId: the delivery's ID as its sending system sent it
     * (source_submission_id). contentSha256 and pages: of the document (for a duplicate, the arrival's checksum and the
     * kept document's pages); null for DELIVERY and REFUSED.
     */
    record Item(Kind kind, UUID itemId, UUID sourceId, UUID submissionId, String requestId, String contentSha256,
                Integer pages, Instant at, String documentStatus, String refusalCode) {
        public enum Kind { DELIVERY, DOCUMENT, DUPLICATE, REFUSED }
    }

    /** value: as stored (what people see); normalizedValue: as compared; label: the field's display name. */
    record PrimaryIdentifier(String fieldKey, String value, String normalizedValue, String label) { }

    record UncorrectedCopy(UUID noticeId, Instant receivedAt, UUID sourceId, String sendingSystem,
                           String primaryIdentifier, String primaryIdentifierLabel) { }
}
