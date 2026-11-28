package com.sdv.platform.application.port;

import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.submission.Submission;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BusinessRecordRepository {
    /**
     * incompleteOnly: only submissions that are not COMPLETE (records with missing documents).
     * anywhere (task 0022): when not null, a submission must also match at least one of its fields, and the best
     * match comes first (exact identifier, exact other field, starts with, contains; then newest first).
     */
    List<Submission> findMatchingSubmissions(
            UUID tenantId,
            UUID sourceId,
            List<SearchCriterion> criteria,
            AnywhereCriterion anywhere,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            boolean incompleteOnly,
            int offset,
            int limit);

    /** incompleteOnly: only submissions that are not COMPLETE (records with missing documents). */
    default List<Submission> findMatchingSubmissions(
            UUID tenantId,
            UUID sourceId,
            List<SearchCriterion> criteria,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            boolean incompleteOnly,
            int offset,
            int limit) {
        return findMatchingSubmissions(tenantId, sourceId, criteria, null, createdFrom, createdTo, updatedFrom, updatedTo,
                incompleteOnly, offset, limit);
    }

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

    /** The record's submissions, newest first. */
    List<UUID> findSubmissionIdsOfRecord(UUID tenantId, UUID sourceId, UUID recordId);

    /** Task 0078: each submission's record; a submission that isn't there is left out. */
    java.util.Map<UUID, UUID> findRecordIds(UUID tenantId, UUID sourceId, java.util.Collection<UUID> submissionIds);

    /** Task 0024, part 2: the record's own level (record_sensitivity_levels); empty when the record has none. */
    java.util.Optional<SensitivityLevel> findRecordSensitivityLevel(
            UUID tenantId,
            UUID sourceId,
            UUID recordId);

    /**
     * Task 0026: the levels of several records of one sending system at once, by record key; records without a level
     * are left out. The default asks one record at a time.
     */
    default java.util.Map<UUID, SensitivityLevel> findRecordSensitivityLevels(
            UUID tenantId,
            UUID sourceId,
            java.util.Collection<UUID> recordIds) {
        java.util.Map<UUID, SensitivityLevel> levels = new java.util.HashMap<>();
        for (UUID key : new java.util.LinkedHashSet<>(recordIds)) {
            findRecordSensitivityLevel(tenantId, sourceId, key).ifPresent(level -> levels.put(key, level));
        }
        return levels;
    }

    /** Each submission's record's values that aren't sensitive (task 0078: a record has one set, the latest delivery's). */
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

    /**
     * ignoreCase: an Exact match compares without regard to capitals (exact text fields that are neither an
     * identifier nor digits-only; task 0022 follow-up). Starts with and Contains always ignore case.
     */
    record SearchCriterion(
            String fieldKey,
            MetadataSearchMode searchMode,
            List<String> normalizedVariants,
            boolean ignoreCase,
            /* Task 0078: a sensitive field: what its value is compared with; the variants are then empty. */
            List<String> fingerprints,
            /*
             * Task 0101 (VSEC-202): for a sensitive field, the levels the person searching may open. Only records whose
             * level and every available document are at one of them can match, so "not found" and "not yours" look
             * the same. Empty for a sensitive field: nothing matches (fail closed).
             */
            List<String> openableLevels) {
        public SearchCriterion {
            normalizedVariants = List.copyOf(normalizedVariants);
            fingerprints = List.copyOf(fingerprints);
            openableLevels = List.copyOf(openableLevels);
        }

        public SearchCriterion(String fieldKey, MetadataSearchMode searchMode, List<String> normalizedVariants, boolean ignoreCase) {
            this(fieldKey, searchMode, normalizedVariants, ignoreCase, List.of(), List.of());
        }

        /** A sensitive field's criterion: its fingerprints, and the levels the person searching may open. */
        public static SearchCriterion sensitive(String fieldKey, List<String> fingerprints, java.util.Collection<com.sdv.platform.domain.document.SensitivityLevel> openable) {
            return new SearchCriterion(fieldKey, MetadataSearchMode.EXACT, List.of(), false, fingerprints,
                    openable.stream().map(Enum::name).sorted().toList());
        }

        /** Exact keeps capitals. */
        public SearchCriterion(String fieldKey, MetadataSearchMode searchMode, List<String> normalizedVariants) {
            this(fieldKey, searchMode, normalizedVariants, false);
        }
    }

    /**
     * One field the "anywhere" text is tried in, with that field's own match and normalized variants.
     * businessIdentifier: an exact match here ranks first. ignoreCase: as for {@link SearchCriterion}.
     */
    record AnywhereField(
            String fieldKey,
            MetadataSearchMode searchMode,
            List<String> normalizedVariants,
            boolean businessIdentifier,
            boolean ignoreCase) {
        public AnywhereField {
            normalizedVariants = List.copyOf(normalizedVariants);
            if (normalizedVariants.isEmpty()) throw new com.sdv.platform.domain.error.CodedRefusal.BadRequest(com.sdv.platform.domain.error.SdvErrorCode.SCH_001, "An anywhere field needs a value.");
        }

        /** Exact keeps capitals. */
        public AnywhereField(String fieldKey, MetadataSearchMode searchMode, List<String> normalizedVariants,
                boolean businessIdentifier) {
            this(fieldKey, searchMode, normalizedVariants, businessIdentifier, false);
        }
    }

    /** The "anywhere" text as the fields it is tried in; a submission matches when any one of them matches. */
    record AnywhereCriterion(List<AnywhereField> fields) {
        public AnywhereCriterion {
            fields = List.copyOf(fields);
            if (fields.isEmpty()) throw new com.sdv.platform.domain.error.CodedRefusal.BadRequest(com.sdv.platform.domain.error.SdvErrorCode.SCH_001, "An anywhere search needs at least one field.");
        }
    }

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
            Instant expiresAt,
            /** Worked out when the document was stored; null for documents stored before that (task 0018). */
            Integer pageCount,
            /** A permanent correction (redaction) has been applied to the current version. */
            boolean redacted,
            /** The document's own sensitivity level (task 0024). */
            SensitivityLevel sensitivityLevel) { }

    record ExpectedDocumentSnapshot(
            UUID submissionId,
            String sourceDocumentId,
            String state) { }

    record DocumentCount(UUID submissionId, int documentCount) { }

    record RecentDocumentView(UUID documentId, UUID submissionId, UUID sourceId, Instant lastOpenedAt) { }

    /**
     * Task 0023: for every sending system of the tenant, in one query, when it last delivered (the newest
     * last_activity_at of its submissions), or null when it holds no record. Nothing is counted.
     */
    List<SourceActivity> findSourceActivity(UUID tenantId);

    record SourceActivity(UUID sourceId, Instant lastDeliveredAt) {
        public boolean hasRecords() { return lastDeliveredAt != null; }
    }
}
