package com.sdv.platform.application.port;

import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.submission.Submission;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BusinessRecordRepository {
    List<Submission> findMatchingSubmissions(
            UUID tenantId,
            UUID sourceId,
            List<SearchCriterion> criteria,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            int limit);

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
}
