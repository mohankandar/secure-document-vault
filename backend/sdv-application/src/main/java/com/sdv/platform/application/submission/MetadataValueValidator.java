package com.sdv.platform.application.submission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class MetadataValueValidator {
    private static final ObjectMapper JSON = new ObjectMapper();

    private MetadataValueValidator() { }

    static ValidatedValue validate(MetadataFieldDefinition definition, String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("Metadata field '" + definition.fieldKey() + "' cannot be null.");
        }

        validateType(definition.fieldType(), rawValue, definition.fieldKey());
        String normalized = normalize(definition.normalization(), rawValue);
        applyRules(definition, normalized);
        return new ValidatedValue(rawValue, normalized);
    }

    private static void validateType(MetadataFieldType type, String value, String fieldKey) {
        try {
            switch (type) {
                case TEXT -> { }
                case INTEGER -> new BigInteger(value.trim());
                case DECIMAL -> new BigDecimal(value.trim());
                case DATE -> LocalDate.parse(value.trim());
                case DATETIME -> OffsetDateTime.parse(value.trim());
                case BOOLEAN -> {
                    String v = value.trim().toLowerCase(Locale.ROOT);
                    if (!v.equals("true") && !v.equals("false")) {
                        throw new IllegalArgumentException("Metadata field '" + fieldKey + "' must be true or false.");
                    }
                }
                case EMAIL -> {
                    String v = value.trim();
                    int at = v.indexOf('@');
                    if (at <= 0 || at == v.length() - 1 || v.indexOf('.', at) < at + 2) {
                        throw new IllegalArgumentException("Metadata field '" + fieldKey + "' must be a valid email address.");
                    }
                }
                case UUID -> UUID.fromString(value.trim());
            }
        } catch (NumberFormatException | DateTimeParseException ex) {
            throw new IllegalArgumentException("Metadata field '" + fieldKey + "' is not a valid " + type + " value.");
        } catch (IllegalArgumentException ex) {
            if (type == MetadataFieldType.UUID && !ex.getMessage().startsWith("Metadata field")) {
                throw new IllegalArgumentException("Metadata field '" + fieldKey + "' is not a valid UUID value.");
            }
            throw ex;
        }
    }

    private static String normalize(MetadataNormalization normalization, String value) {
        return switch (normalization) {
            case NONE -> value;
            case TRIM -> value.trim();
            case UPPERCASE -> value.toUpperCase(Locale.ROOT);
            case LOWERCASE -> value.toLowerCase(Locale.ROOT);
            case DIGITS_ONLY -> value.replaceAll("\\D", "");
        };
    }

    private static void applyRules(MetadataFieldDefinition definition, String value) {
        String rulesJson = definition.validationRulesJson();
        if (rulesJson == null || rulesJson.isBlank() || rulesJson.equals("{}")) return;

        try {
            JsonNode rules = JSON.readTree(rulesJson);
            checkLength(rules, "exactLength", value, definition.fieldKey(), true);
            checkLength(rules, "minLength", value, definition.fieldKey(), false);
            checkMaxLength(rules, value, definition.fieldKey());

            JsonNode pattern = rules.get("pattern");
            if (pattern != null && pattern.isTextual()) {
                try {
                    if (!Pattern.compile(pattern.asText()).matcher(value).matches()) {
                        throw new IllegalArgumentException("Metadata field '" + definition.fieldKey() + "' does not match its configured pattern.");
                    }
                } catch (PatternSyntaxException ex) {
                    throw new IllegalStateException("Metadata field '" + definition.fieldKey() + "' has an invalid configured pattern.");
                }
            }

            if (definition.fieldType() == MetadataFieldType.INTEGER || definition.fieldType() == MetadataFieldType.DECIMAL) {
                BigDecimal numeric = new BigDecimal(value.trim());
                JsonNode min = rules.get("min");
                if (min != null && min.isNumber() && numeric.compareTo(min.decimalValue()) < 0) {
                    throw new IllegalArgumentException("Metadata field '" + definition.fieldKey() + "' is below its configured minimum.");
                }
                JsonNode max = rules.get("max");
                if (max != null && max.isNumber() && numeric.compareTo(max.decimalValue()) > 0) {
                    throw new IllegalArgumentException("Metadata field '" + definition.fieldKey() + "' is above its configured maximum.");
                }
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Metadata field '" + definition.fieldKey() + "' has unreadable validation rules.", ex);
        }
    }

    private static void checkLength(JsonNode rules, String ruleName, String value, String fieldKey, boolean exact) {
        JsonNode node = rules.get(ruleName);
        if (node == null || !node.canConvertToInt()) return;
        int limit = node.asInt();
        if (limit < 0) throw new IllegalStateException("Metadata field '" + fieldKey + "' has an invalid " + ruleName + " rule.");
        if (exact && value.length() != limit) {
            throw new IllegalArgumentException("Metadata field '" + fieldKey + "' must contain exactly " + limit + " characters after normalization.");
        }
        if (!exact && value.length() < limit) {
            throw new IllegalArgumentException("Metadata field '" + fieldKey + "' is shorter than its configured minimum length.");
        }
    }

    private static void checkMaxLength(JsonNode rules, String value, String fieldKey) {
        JsonNode node = rules.get("maxLength");
        if (node == null || !node.canConvertToInt()) return;
        int limit = node.asInt();
        if (limit < 0) throw new IllegalStateException("Metadata field '" + fieldKey + "' has an invalid maxLength rule.");
        if (value.length() > limit) {
            throw new IllegalArgumentException("Metadata field '" + fieldKey + "' exceeds its configured maximum length.");
        }
    }

    record ValidatedValue(String storedValue, String normalizedValue) { }
}
