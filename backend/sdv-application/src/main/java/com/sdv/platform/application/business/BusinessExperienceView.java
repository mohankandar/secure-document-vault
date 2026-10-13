package com.sdv.platform.application.business;

import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BusinessExperienceView {
    private BusinessExperienceView() { }

    public record SearchSchema(List<SourceSchema> sources) {
        public SearchSchema {
            sources = List.copyOf(sources);
        }
    }

    public record SourceSchema(
            UUID sourceId,
            String sourceKey,
            String displayName,
            List<SearchField> fields) {
        public SourceSchema {
            fields = List.copyOf(fields);
        }
    }

    public record SearchField(
            String fieldKey,
            String displayLabel,
            MetadataFieldType fieldType,
            boolean businessIdentifier,
            MetadataNormalization normalization,
            MetadataSearchMode searchMode) { }

    public record FieldValue(
            String fieldKey,
            String displayLabel,
            String value,
            boolean businessIdentifier) { }

    public record SearchResponse(
            UUID sourceId,
            List<RecordSummary> records,
            int page,
            int pageSize,
            boolean hasMore,
            boolean truncated) {
        public SearchResponse {
            records = List.copyOf(records);
        }
    }

    public record RecordSummary(
            String recordRef,
            List<FieldValue> identifiers,
            List<FieldValue> metadataPreview,
            int submissionCount,
            int availableDocumentCount,
            Instant latestSubmissionAt,
            boolean incomplete) {
        public RecordSummary {
            identifiers = List.copyOf(identifiers);
            metadataPreview = List.copyOf(metadataPreview);
        }
    }

    public record RecordView(
            String recordRef,
            UUID sourceId,
            String sourceKey,
            String sourceDisplayName,
            List<FieldValue> identifiers,
            List<FieldValue> metadata,
            int submissionCount,
            Instant latestSubmissionAt,
            List<DocumentSummary> documents,
            IncompleteAttention incompleteAttention) {
        public RecordView {
            identifiers = List.copyOf(identifiers);
            metadata = List.copyOf(metadata);
            documents = List.copyOf(documents);
        }
    }

    public record DocumentSummary(
            UUID documentId,
            UUID submissionId,
            String sourceDocumentId,
            String originalFilename,
            Instant ingestedAt,
            Instant availableAt,
            Instant expiresAt) { }

    public record IncompleteAttention(List<IncompleteSubmission> submissions) {
        public IncompleteAttention {
            submissions = List.copyOf(submissions);
        }
    }

    public record IncompleteSubmission(
            UUID submissionId,
            String status,
            int expectedCount,
            int receivedCount,
            int missingCount,
            List<String> missingSourceDocumentIds) {
        public IncompleteSubmission {
            missingSourceDocumentIds = List.copyOf(missingSourceDocumentIds);
        }
    }
}
