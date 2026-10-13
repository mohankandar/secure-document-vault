package com.sdv.platform.application.business;

import com.sdv.platform.domain.metadata.MetadataNormalization;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchValueNormalizerTest {
    @Test
    void appliesConfiguredNormalization() {
        assertEquals("123456", SearchValueNormalizer.normalize(
                MetadataNormalization.DIGITS_ONLY, "123-45 6"));
        assertEquals("alice johnson", SearchValueNormalizer.normalize(
                MetadataNormalization.LOWERCASE, "Alice Johnson"));
        assertEquals("BRANCH01", SearchValueNormalizer.normalize(
                MetadataNormalization.UPPERCASE, "branch01"));
        assertEquals("trimmed", SearchValueNormalizer.normalize(
                MetadataNormalization.TRIM, "  trimmed  "));
    }
}
