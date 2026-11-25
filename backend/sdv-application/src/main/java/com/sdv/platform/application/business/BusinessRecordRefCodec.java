package com.sdv.platform.application.business;

import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.domain.error.InternalCheckFailed;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** A record's reference in a URL: its sending system and its ID (task 0078). A reference of before (identifier values) is "not found". */
final class BusinessRecordRefCodec {
    private static final ObjectMapper JSON = VeroJson.mapper();
    private static final int MAX_TOKEN_LENGTH = 8192;

    String encode(RecordIdentity identity) {
        try {
            var root = JSON.createObjectNode();
            root.put("s", identity.sourceId().toString());
            root.put("r", identity.recordId().toString());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(root));
        } catch (Exception ex) {
            throw new InternalCheckFailed("Could not encode business-record reference.", ex);
        }
    }

    /** Task 0043: a reference that can't be read is SDV-SCH-002 (400); the exact cause goes to the log line. */
    RecordIdentity decode(String token) {
        RecordIdentity identity;
        try {
            identity = read(token);
        } catch (IllegalArgumentException ex) {
            throw RecordRefusals.invalid(ex.getMessage());
        }
        if (identity.recordId() == null) throw RecordRefusals.notFound("a reference of before task 0078");
        return identity;
    }

    private static RecordIdentity read(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException("Business-record reference is invalid.");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length > 6144) throw new IllegalArgumentException("Business-record reference is too large.");
            JsonNode root = JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
            UUID source = UUID.fromString(requiredText(root, "s"));
            boolean before = root.has("i") || root.has("u");
            return new RecordIdentity(source, before ? null : UUID.fromString(requiredText(root, "r")));
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

    record RecordIdentity(UUID sourceId, UUID recordId) { }
}
