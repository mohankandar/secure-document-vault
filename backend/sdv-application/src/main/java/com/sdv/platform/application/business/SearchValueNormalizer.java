package com.sdv.platform.application.business;

import com.sdv.platform.domain.metadata.MetadataNormalization;

import java.util.Locale;

public final class SearchValueNormalizer {
    private SearchValueNormalizer() { }

    public static String normalize(MetadataNormalization normalization, String value) {
        if (normalization == null) throw new com.sdv.platform.domain.error.CodedRefusal.BadRequest(com.sdv.platform.domain.error.SdvErrorCode.SCH_001, "Search normalization is required.");
        if (value == null) throw new com.sdv.platform.domain.error.CodedRefusal.BadRequest(com.sdv.platform.domain.error.SdvErrorCode.SCH_001, "Search value cannot be null.");

        return switch (normalization) {
            case NONE -> value;
            case TRIM -> value.trim();
            case UPPERCASE -> value.toUpperCase(Locale.ROOT);
            case LOWERCASE -> value.toLowerCase(Locale.ROOT);
            case DIGITS_ONLY -> value.replaceAll("\\D", "");
        };
    }

    /** Task 0078: a value or search text as stored for search: no capitals, single spaces. */
    public static String searchForm(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
