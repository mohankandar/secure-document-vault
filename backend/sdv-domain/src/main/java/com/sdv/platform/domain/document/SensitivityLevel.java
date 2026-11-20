package com.sdv.platform.domain.document;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Task 0024: how sensitive a document is, set by the sending system when it delivers the document.
 * Declared from lowest to highest; {@link #higherOf} relies on that order.
 * Nothing filters on the level yet (enforcement is task 0026).
 */
public enum SensitivityLevel {
    STANDARD,
    CONFIDENTIAL,
    HIGHLY_CONFIDENTIAL;

    private static final String ALLOWED = Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));

    /**
     * A level as a sending system wrote it. Missing (null) means {@link #STANDARD}. Anything else must be one of the
     * names exactly, in capitals: an unknown, blank or wrongly cased value is refused, never guessed or defaulted.
     */
    public static SensitivityLevel fromRequest(String value, String field) {
        return value == null ? STANDARD : exact(value, field, "leave it out for STANDARD", SdvErrorCode.SEN_001);
    }

    /**
     * A record level as a sending system wrote it (task 0024, part 2). Missing (null) means the record gets no level
     * from this delivery; anything else must be one of the names exactly, as for {@link #fromRequest}.
     */
    public static SensitivityLevel optionalFromRequest(String value, String field) {
        return value == null ? null : exact(value, field, "leave it out for no record level", SdvErrorCode.SEN_002);
    }

    private static SensitivityLevel exact(String value, String field, String whenMissing, SdvErrorCode code) {
        for (SensitivityLevel level : values()) {
            if (level.name().equals(value)) return level;
        }
        throw new CodedRefusal.BadRequest(code, field + " must be one of " + ALLOWED
                + " (exactly, in capitals); " + whenMissing + ".");
    }

    /**
     * The one rule for a document's effective level: the higher of its own level and its record's level.
     * {@code recordLevel} is null when the record has no level of its own.
     */
    public static SensitivityLevel effective(SensitivityLevel documentLevel, SensitivityLevel recordLevel) {
        if (documentLevel == null) throw new IllegalArgumentException("A document always has a sensitivity level.");
        return recordLevel == null ? documentLevel : higherOf(documentLevel, recordLevel);
    }

    public static SensitivityLevel higherOf(SensitivityLevel a, SensitivityLevel b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
