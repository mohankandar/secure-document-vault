package com.sdv.platform.application.business;

import com.sdv.platform.domain.metadata.MetadataNormalization;

import java.util.Locale;

final class SearchValueNormalizer {
    private SearchValueNormalizer() { }

    static String normalize(MetadataNormalization normalization, String value) {
        if (normalization == null) throw new IllegalArgumentException("Search normalization is required.");
        if (value == null) throw new IllegalArgumentException("Search value cannot be null.");

        return switch (normalization) {
            case NONE -> value;
            case TRIM -> value.trim();
            case UPPERCASE -> value.toUpperCase(Locale.ROOT);
            case LOWERCASE -> value.toLowerCase(Locale.ROOT);
            case DIGITS_ONLY -> value.replaceAll("\\D", "");
        };
    }
}
