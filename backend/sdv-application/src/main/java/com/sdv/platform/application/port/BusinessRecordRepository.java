package com.sdv.platform.application.port;

import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.submission.Submission;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BusinessRecordRepository {
    /** incompleteOnly: only submissions that are not COMPLETE (records with missing documents). */
    List<Submission> findMatchingSubmissions(
            UUID tenantId,
            UUID sourceId,
            List<SearchCriterion> criteria,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            boolean incompleteOnly,
            int offset,
            int limit);

    default List<Submission> findMatchingSubmissions(
            UUID tenantId,
            UUID sourceId,
            List<SearchCriterion> criteria,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            int offset,
            int limit) {
        return findMatchingSubmissions(tenantId, sourceId, criteria, createdFrom, createdTo, updatedFrom, updatedTo,
                false, offset, limit);
    }

    /**
     * The person's own documents opened in the viewer: successful DOCUMENT_VIEW_OPENED audit events by this actor
     * in this tenant since the given time, newest first, one row per document, only documents still AVAILABLE
     * (nothing past retention or deleted). At most eventScanLimit of the actor's newest view events are read.
     */
    List<RecentDocumentView> findRecentlyViewedDocuments(
            UUID tenantId,
            String actorId,
            Instant since,
            int eventScanLimit);

    List<Submission> findSubmissionsByIds(
            UUID tenantId,
            UUID sourceId,
            List<UUID> submissionIds);

    List<UUID> findSubmissionIdsByRecordIdentity(
            UUID tenantId,
            UUID sourceId,
            List<IdentifierCriterion> identifiers);

    List<MetadataSnapshot> findMetadataSnapshots(
            UUID tenantId,
            UUID sourceId,
            List<UUID> submissionIds);

    List<AvailableDocumentSnapshot> findAvailableDocuments(
            UUID tenantId,
            UUID sourceId,
            List<UUID> submissionIds);

    List<ExpectedDocumentSnapshot> findExpectedDocuments(
            UUID tenantId,
            List<UUID> submissionIds);

    List<DocumentCount> countDocuments(
            UUID tenantId,
            UUID sourceId,
            List<UUID> submissionIds);

    record SearchCriterion(
            String fieldKey,
            MetadataSearchMode searchMode,
            List<String> normalizedVariants) {
        public SearchCriterion {
            normalizedVariants = List.copyOf(normalizedVariants);
        }
    }

    record IdentifierCriterion(String fieldKey, String normalizedValue) { }

    record MetadataSnapshot(
            UUID submissionId,
            String fieldKey,
            String displayLabel,
            String storedValue,
            String normalizedValue,
            boolean businessIdentifier) { }

    record AvailableDocumentSnapshot(
            UUID documentId,
            UUID submissionId,
            String sourceDocumentId,
            String originalFilename,
            Instant ingestedAt,
            Instant availableAt,
            Instant expiresAt) { }

    record ExpectedDocumentSnapshot(
            UUID submissionId,
            String sourceDocumentId,
            String state) { }

    record DocumentCount(UUID submissionId, int documentCount) { }

    record RecentDocumentView(UUID documentId, UUID submissionId, UUID sourceId, Instant lastOpenedAt) { }
}
