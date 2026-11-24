package com.sdv.platform.application.business;

import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.domain.error.InternalCheckFailed;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class BusinessRecordRefCodec {
    private static final ObjectMapper JSON = VeroJson.mapper();
    private static final int MAX_TOKEN_LENGTH = 8192;
    private static final int MAX_IDENTIFIER_COUNT = 20;

    String encode(RecordIdentity identity) {
        try {
            var root = JSON.createObjectNode();
            root.put("s", identity.sourceId().toString());
            if (identity.fallbackSubmissionId() != null) {
                root.put("u", identity.fallbackSubmissionId().toString());
            } else {
                var ids = root.putArray("i");
                identity.identifiers().stream()
                        .sorted(Comparator.comparing(IdentifierPart::fieldKey)
                                .thenComparing(IdentifierPart::normalizedValue))
                        .forEach(part -> {
                            var node = ids.addObject();
                            node.put("k", part.fieldKey());
                            node.put("v", part.normalizedValue());
                        });
            }
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(JSON.writeValueAsBytes(root));
        } catch (Exception ex) {
            throw new InternalCheckFailed("Could not encode business-record reference.", ex);
        }
    }

    /** Task 0043: a reference that can't be read is SDV-SCH-002 (400); the exact cause goes to the log line. */
    RecordIdentity decode(String token) {
        try {
            return read(token);
        } catch (IllegalArgumentException ex) {
            throw RecordRefusals.invalid(ex.getMessage());
        }
    }

    private RecordIdentity read(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("Business-record reference is invalid.");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length > 6144) throw new IllegalArgumentException("Business-record reference is too large.");
            JsonNode root = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
            UUID sourceId = UUID.fromString(requiredText(root, "s"));

            JsonNode fallback = root.get("u");
            JsonNode identifiers = root.get("i");
            if ((fallback == null || fallback.isNull()) == (identifiers == null || identifiers.isNull())) {
                throw new IllegalArgumentException("Business-record reference must have exactly one identity form.");
            }

            if (fallback != null && !fallback.isNull()) {
                return new RecordIdentity(sourceId, List.of(), UUID.fromString(VeroJson.text(fallback)));
            }

            if (!identifiers.isArray() || identifiers.size() == 0 || identifiers.size() > MAX_IDENTIFIER_COUNT) {
                throw new IllegalArgumentException("Business-record identifier set is invalid.");
            }
            List<IdentifierPart> parts = new ArrayList<>();
            for (JsonNode node : identifiers) {
                String key = requiredText(node, "k");
                String value = requiredText(node, "v");
                if (key.length() > 100 || value.length() > 2000) {
                    throw new IllegalArgumentException("Business-record identifier is too large.");
                }
                parts.add(new IdentifierPart(key, value));
            }
            parts.sort(Comparator.comparing(IdentifierPart::fieldKey)
                    .thenComparing(IdentifierPart::normalizedValue));
            return new RecordIdentity(sourceId, parts, null);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Business-record reference is invalid.", ex);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Business-record reference is missing '" + field + "'.");
        }
        return value.asString();
    }

    record RecordIdentity(
            UUID sourceId,
            List<IdentifierPart> identifiers,
            UUID fallbackSubmissionId) {
        RecordIdentity {
            identifiers = List.copyOf(identifiers);
        }

        String canonicalKey() {
            if (fallbackSubmissionId != null) return "submission:" + fallbackSubmissionId;
            return identifiers.stream()
                    .sorted(Comparator.comparing(IdentifierPart::fieldKey)
                            .thenComparing(IdentifierPart::normalizedValue))
                    .map(part -> part.fieldKey() + "\u001f" + part.normalizedValue())
                    .reduce((left, right) -> left + "\u001e" + right)
                    .orElseThrow(() -> new InternalCheckFailed("A record has neither business identifiers nor a submission to name it by."));
        }

        /** The key of this record's row in record_sensitivity_levels (task 0024, part 2). */
        String recordKey() {
            if (fallbackSubmissionId != null) return RecordKey.ofSubmission(fallbackSubmissionId);
            return RecordKey.ofIdentifiers(identifiers.stream()
                    .map(part -> new RecordKey.Part(part.fieldKey(), part.normalizedValue()))
                    .toList());
        }
    }

    record IdentifierPart(String fieldKey, String normalizedValue) { }
}
