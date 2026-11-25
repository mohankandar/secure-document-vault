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

    /**
     * status (task 0023): ACTIVE or PAUSED. hasRecords: whether the system holds at least one record (not a count).
     * lastDeliveredAt: when it last delivered, null when it holds no record.
     */
    public record SourceSchema(
            UUID sourceId,
            String sourceKey,
            String displayName,
            List<SearchField> fields,
            String status,
            boolean hasRecords,
            Instant lastDeliveredAt) {
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
            MetadataSearchMode searchMode,
            /* Task 0078: found by its whole value only, and not by free search. */
            boolean sensitive,
            /* Set to "contains", but this database can only search from the beginning. */
            boolean partialAsPrefix) { }

    public record FieldValue(
            String fieldKey,
            String displayLabel,
            String value,
            boolean businessIdentifier,
            /* Task 0079: stored encrypted, shown to this person; one they may not see is not here at all. */
            boolean sensitive) {
        public FieldValue(String fieldKey, String displayLabel, String value, boolean businessIdentifier) {
            this(fieldKey, displayLabel, value, businessIdentifier, false);
        }
    }

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

    /** title: the record name from the source's Record title setting (or the default); null when there is none. */
    public record RecordSummary(
            String recordRef,
            String title,
            List<FieldValue> identifiers,
            List<FieldValue> metadataPreview,
            int submissionCount,
            int availableDocumentCount,
            Instant latestSubmissionAt,
            boolean incomplete,
            /* Over the record's incomplete submissions (0 when complete), counted as in record detail. */
            int incompleteExpectedCount,
            int incompleteReceivedCount,
            /* An "anywhere" search only (task 0022): every field of this record that matched, best match first. */
            List<MatchedField> matchedFields,
            /* Task 0026: the highest effective level among this record's documents that this person can't open
               (CONFIDENTIAL or HIGHLY_CONFIDENTIAL), or null when they can open them all. Counts are unchanged. */
            String restrictedLevel,
            /* Task 0048: how many of the record's documents are quarantined now ("Temporarily unavailable"); they are
               still in availableDocumentCount, and their levels are left out of restrictedLevel. */
            int unavailableDocumentCount) {
        public RecordSummary {
            identifiers = List.copyOf(identifiers);
            metadataPreview = List.copyOf(metadataPreview);
            matchedFields = matchedFields == null ? List.of() : List.copyOf(matchedFields);
        }
    }

    /** A field that matched an "anywhere" search: its label and the record's own value (task 0022). */
    public record MatchedField(String fieldKey, String displayLabel, String value) { }

    /** A record the person opened themselves, with when they last opened one of its documents. */
    public record RecentRecord(UUID sourceId, RecordSummary record, Instant lastOpenedAt) { }

    public record RecentRecords(List<RecentRecord> records) {
        public RecentRecords {
            records = List.copyOf(records);
        }
    }

    public record RecordView(
            String recordRef,
            String title,
            UUID sourceId,
            String sourceKey,
            String sourceDisplayName,
            List<FieldValue> identifiers,
            List<FieldValue> metadata,
            int submissionCount,
            Instant latestSubmissionAt,
            List<DocumentSummary> documents,
            IncompleteAttention incompleteAttention,
            /* Task 0024, part 2: the record's own level; null when the record has none. */
            String recordSensitivityLevel,
            /* Task 0026: the documents this person can't open, as a reference and a level only. `documents` holds
               only the ones they can open. */
            List<LockedDocument> lockedDocuments,
            /* Task 0026: as RecordSummary.restrictedLevel (task 0048: over the documents not in unavailableDocuments). */
            String restrictedLevel,
            /* Task 0048: quarantined documents this person may not review: "Temporarily unavailable", the reference
               only (no level, no reason). A reviewer sees them in documents, marked quarantined. */
            List<UnavailableDocument> unavailableDocuments) {
        public RecordView {
            unavailableDocuments = unavailableDocuments == null ? List.of() : List.copyOf(unavailableDocuments);
            identifiers = List.copyOf(identifiers);
            metadata = List.copyOf(metadata);
            documents = List.copyOf(documents);
            lockedDocuments = lockedDocuments == null ? List.of() : List.copyOf(lockedDocuments);
        }
    }

    /**
     * Task 0026: a document this person can't open. Only its reference (the random document ID, which carries no
     * meaning; opening it is refused with SDV-SEN-008) and its effective level. No file name, page count, dates, tags
     * or comments.
     */
    public record LockedDocument(UUID documentId, String effectiveSensitivityLevel) { }

    /** Task 0048: a document that is temporarily unavailable (quarantined): its reference only. */
    public record UnavailableDocument(UUID documentId) { }

    public record DocumentSummary(
            UUID documentId,
            UUID submissionId,
            String sourceDocumentId,
            String originalFilename,
            Instant ingestedAt,
            Instant availableAt,
            Instant expiresAt,
            /** null when the document was stored before page counts were kept. */
            Integer pageCount,
            boolean redacted,
            /** Task 0024: the document's own sensitivity level. */
            String sensitivityLevel,
            /** Task 0024, part 2: the higher of its own level and its record's level (SensitivityLevel.effective). */
            String effectiveSensitivityLevel,
            /** Task 0048: quarantined now (only a reviewer who can open its level sees it here). */
            boolean quarantined) { }

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
