package com.sdv.platform.application.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Task 0078: the values of a record's fields (platform-fields). What isn't sensitive is read with the record; this is the rest. */
public interface RecordValuesPort {
    /** A sensitive value, opened. */
    record Opened(String fieldKey, String label, String value, boolean identifier) {
        @Override
        public String toString() {
            return "Opened[" + fieldKey + "]";
        }
    }

    /**
     * A delivery's values: finds or makes the record its identifiers name, points the submission at it, and replaces the fields sent.
     * Within the caller's transaction. Returns the record.
     */
    UUID store(UUID tenantId, UUID sourceId, UUID submissionId, Map<String, String> values);

    /** The record's sensitive values, for a person who may see them: one key-service call, none when it has none. */
    List<Opened> open(UUID tenantId, UUID sourceId, UUID recordId);

    /** The same for a page of records: one key-service call for all of them. A record that can't be opened is left out. */
    Map<UUID, List<Opened>> openPage(UUID tenantId, UUID sourceId, Collection<UUID> recordIds);

    /** The entity's sensitive fields. */
    Set<String> sensitiveKeys(UUID tenantId);

    /** What a search compares for these sensitive fields: two key-service calls at most, however many fields. */
    Map<String, List<String>> fingerprints(UUID tenantId, Map<String, String> values);

    /** False: the database can't search for a part of a value, so "contains" works as "starts with". */
    boolean partialSearch();

    /** Nothing is sensitive and every submission is its own record (set-ups without case fields). */
    RecordValuesPort NONE = new RecordValuesPort() {
        @Override
        public UUID store(UUID tenantId, UUID sourceId, UUID submissionId, Map<String, String> values) {
            return submissionId;
        }

        @Override
        public List<Opened> open(UUID tenantId, UUID sourceId, UUID recordId) {
            return List.of();
        }

        @Override
        public Map<UUID, List<Opened>> openPage(UUID tenantId, UUID sourceId, Collection<UUID> recordIds) {
            return Map.of();
        }

        @Override
        public Set<String> sensitiveKeys(UUID tenantId) {
            return Set.of();
        }

        @Override
        public Map<String, List<String>> fingerprints(UUID tenantId, Map<String, String> values) {
            return Map.of();
        }

        @Override
        public boolean partialSearch() {
            return true;
        }
    };
}
