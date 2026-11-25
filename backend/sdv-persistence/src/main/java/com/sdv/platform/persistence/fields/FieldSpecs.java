package com.sdv.platform.persistence.fields;

import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.Normalisation;
import com.documentplatform.fields.SearchMode;
import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Task 0077: Vero's field settings as a platform-fields definition and back, as docs/design/case-fields.md maps them. */
final class FieldSpecs {
    private FieldSpecs() { }

    static FieldType type(MetadataFieldType type) {
        return switch (type) {
            case TEXT -> FieldType.TEXT;
            case INTEGER -> FieldType.NUMBER;
            case DECIMAL -> FieldType.DECIMAL;
            case DATE -> FieldType.DATE;
            case DATETIME -> FieldType.DATE_TIME;
            case BOOLEAN -> FieldType.DROPDOWN;
            case EMAIL -> FieldType.EMAIL;
            case UUID -> FieldType.UUID;
        };
    }

    /** A definition Vero's intake can check; null for a structured address, which a delivery can't carry as one text (until 0078). */
    static MetadataFieldType type(FieldSpec spec) {
        return switch (spec.type()) {
            case TEXT, CUSTOM_PATTERN -> MetadataFieldType.TEXT;
            case NUMBER -> MetadataFieldType.INTEGER;
            case DECIMAL, MONEY -> MetadataFieldType.DECIMAL;
            case DATE -> MetadataFieldType.DATE;
            case DATE_TIME -> MetadataFieldType.DATETIME;
            case DROPDOWN -> spec.options().equals(List.of("true", "false")) ? MetadataFieldType.BOOLEAN : MetadataFieldType.TEXT;
            case EMAIL -> MetadataFieldType.EMAIL;
            case UUID -> MetadataFieldType.UUID;
            case ADDRESS -> null;
        };
    }

    private static Normalisation normalisation(MetadataNormalization n, FieldType type) {
        if (type == FieldType.DROPDOWN) return Normalisation.of(Normalisation.LetterCase.LOWER, Normalisation.Strip.NONE);
        if (!type.takesNormalisation()) return Normalisation.NONE;
        return switch (n) {
            case NONE, TRIM -> Normalisation.NONE;
            case UPPERCASE -> Normalisation.of(Normalisation.LetterCase.UPPER, Normalisation.Strip.NONE);
            case LOWERCASE -> Normalisation.of(Normalisation.LetterCase.LOWER, Normalisation.Strip.NONE);
            case DIGITS_ONLY -> Normalisation.of(Normalisation.LetterCase.PRESERVE, Normalisation.Strip.NON_DIGITS);
        };
    }

    /**
     * The definition for Vero's settings. before: the one there is, whose sensitivity and place stay; null: a new one, sensitive by
     * default when its key or label says it is a name or an address. primary: any of the entity's systems uses it as an identifier.
     */
    static FieldSpec spec(MetadataFieldDefinition d, FieldSpec before, boolean primary) {
        FieldType type = before != null && type(before) == d.fieldType() ? before.type() : type(d.fieldType());
        boolean sensitive = before != null ? before.sensitive() : !primary && FieldSpec.suggestsSensitive(d.fieldKey(), d.displayLabel(), type);
        FieldSpec.Builder b = FieldSpec.builder(d.fieldKey(), before == null ? d.displayLabel() : before.label(), type)
                .normalisation(normalisation(d.normalization(), type)).required(false).primary(primary).sensitive(sensitive)
                .displayOrder(before == null ? 0 : before.displayOrder());
        if (before != null && before.type() == type) {
            if (type == FieldType.MONEY) b.currency(before.currency());
            if (type == FieldType.DROPDOWN) b.options(before.options());
            b.multiValue(before.multiValue()).displayable(before.displayable());
        } else if (type == FieldType.DROPDOWN) {
            b.options("true", "false");
        }
        if (type == FieldType.DROPDOWN && before != null && type(before) == MetadataFieldType.TEXT) b.normalisation(before.normalisation());
        // Sensitive fields are found by all of their value only; how people search them until 0078 is the system's own setting.
        if (d.searchable() || primary) b.searchable(sensitive ? SearchMode.EXACT : mode(d.searchMode()));
        JsonNode rules = VeroJson.mapper().readTree(d.validationRulesJson() == null || d.validationRulesJson().isBlank() ? "{}" : d.validationRulesJson());
        // A list's values and a money amount's form are the definition's; the rules Vero shows for them are derived from it.
        boolean derived = before != null && before.type() == type && (type == FieldType.DROPDOWN && type(before) == MetadataFieldType.TEXT);
        if (!derived) {
            if (has(rules, "exactLength")) b.exactLength(rules.get("exactLength").asInt());
            if (has(rules, "minLength")) b.minLength(rules.get("minLength").asInt());
            if (has(rules, "maxLength")) b.maxLength(rules.get("maxLength").asInt());
            if (rules.path("pattern").isString()) b.pattern(rules.get("pattern").asString());
            else if (type == FieldType.CUSTOM_PATTERN) b.pattern(before.pattern());
            if (has(rules, "min")) b.minValue(new BigDecimal(rules.get("min").asString()));
            if (has(rules, "max")) b.maxValue(new BigDecimal(rules.get("max").asString()));
        }
        return b.build();
    }

    private static boolean has(JsonNode rules, String name) {
        return rules.path(name).isNumber();
    }

    private static SearchMode mode(MetadataSearchMode mode) {
        return SearchMode.valueOf(mode.name());
    }

    /** The definition's rules as Vero's intake reads them. */
    static String rules(FieldSpec spec) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (spec.exactLength() != null) m.put("exactLength", spec.exactLength());
        if (spec.minLength() != null) m.put("minLength", spec.minLength());
        if (spec.maxLength() != null) m.put("maxLength", spec.maxLength());
        if (spec.pattern() != null) m.put("pattern", spec.pattern());
        else if (spec.type() == FieldType.DROPDOWN && type(spec) == MetadataFieldType.TEXT) {
            m.put("pattern", spec.options().stream().map(Pattern::quote).collect(Collectors.joining("|")));
        }
        if (spec.minValue() != null) m.put("min", spec.minValue());
        if (spec.maxValue() != null) m.put("max", spec.maxValue());
        return VeroJson.mapper().writeValueAsString(m);
    }
}
