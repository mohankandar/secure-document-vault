package com.sdv.platform.application.business;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Task 0024, part 2: a record as one canonical text value, the key of its row in record_sensitivity_levels.
 * <p>
 * A record is the group of a sending system's submissions that share their business-identifier values, compared as
 * stored (normalized), the same grouping as {@code findSubmissionIdsByRecordIdentity}. The key is those
 * (field key, normalized value) pairs sorted by field key then value, written as a JSON array:
 * {@code identifiers:[["contractNumber","300000000076"]]}. A submission without identifier values is a record on its
 * own: {@code submission:<submission id>}. The sending system is not in the key; it is its own column.
 */
public final class RecordKey {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IDENTIFIERS = "identifiers:";
    private static final String SUBMISSION = "submission:";

    private RecordKey() { }

    public record Part(String fieldKey, String normalizedValue) {
        public Part {
            if (fieldKey == null || fieldKey.isBlank()) throw new IllegalArgumentException("An identifier needs a field key.");
            if (normalizedValue == null || normalizedValue.isBlank()) {
                throw new IllegalArgumentException("Identifier '" + fieldKey + "' has no value.");
            }
        }
    }

    /** The key of a record grouped by identifier values; at least one value is needed. */
    public static String ofIdentifiers(List<Part> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) {
            throw new IllegalArgumentException("A record key needs at least one identifier value.");
        }
        List<List<String>> sorted = identifiers.stream()
                .sorted(Comparator.comparing(Part::fieldKey).thenComparing(Part::normalizedValue))
                .map(part -> List.of(part.fieldKey(), part.normalizedValue()))
                .toList();
        try {
            return IDENTIFIERS + JSON.writeValueAsString(sorted);
        } catch (JsonProcessingException ex) {
            throw new InternalCheckFailed("Could not write the record key.", ex);
        }
    }

    /** The key of a submission without identifier values, which is a record on its own. */
    public static String ofSubmission(UUID submissionId) {
        if (submissionId == null) throw new IllegalArgumentException("A submission record key needs the submission.");
        return SUBMISSION + submissionId;
    }

    /**
     * Task 0026: the key of the record a submission belongs to, from its stored metadata, grouped exactly as search
     * and record detail group it: its business-identifier values that are not blank, or the submission alone when it
     * has none.
     */
    public static String forSubmission(UUID submissionId,
                                       List<com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot> metadata) {
        List<Part> identifiers = metadata.stream()
                .filter(value -> submissionId.equals(value.submissionId()))
                .filter(com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot::businessIdentifier)
                .filter(value -> value.normalizedValue() != null && !value.normalizedValue().isBlank())
                .map(value -> new Part(value.fieldKey(), value.normalizedValue()))
                .toList();
        return identifiers.isEmpty() ? ofSubmission(submissionId) : ofIdentifiers(identifiers);
    }

    /**
     * Task 0028: a stored key read back. Exactly one of the two is set: the identifier values, or the submission that
     * is a record on its own. Anything not written by this class is refused.
     */
    public record Parsed(List<Part> identifiers, UUID submissionId) { }

    public static Parsed parse(String recordKey) {
        if (recordKey == null) throw new IllegalArgumentException("A record key is required.");
        if (recordKey.startsWith(SUBMISSION)) {
            return new Parsed(List.of(), UUID.fromString(recordKey.substring(SUBMISSION.length())));
        }
        if (!recordKey.startsWith(IDENTIFIERS)) throw new IllegalArgumentException("Unknown record key.");
        try {
            com.fasterxml.jackson.databind.JsonNode array = JSON.readTree(recordKey.substring(IDENTIFIERS.length()));
            if (array == null || !array.isArray() || array.isEmpty()) throw new IllegalArgumentException("Unknown record key.");
            List<Part> parts = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode pair : array) {
                if (!pair.isArray() || pair.size() != 2 || !pair.get(0).isTextual() || !pair.get(1).isTextual()) {
                    throw new IllegalArgumentException("Unknown record key.");
                }
                parts.add(new Part(pair.get(0).asText(), pair.get(1).asText()));
            }
            if (!ofIdentifiers(parts).equals(recordKey)) throw new IllegalArgumentException("Unknown record key.");
            return new Parsed(List.copyOf(parts), null);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unknown record key.", ex);
        }
    }

    /** The key's SHA-256 as lower-case hex, over its UTF-8 bytes (the database checks the same). */
    public static String sha256(String recordKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(recordKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new InternalCheckFailed("SHA-256 is unavailable.", ex);
        }
    }
}
