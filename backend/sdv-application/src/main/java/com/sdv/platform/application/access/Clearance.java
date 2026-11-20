package com.sdv.platform.application.access;

import com.sdv.platform.domain.document.SensitivityLevel;

import java.util.Collection;

/**
 * Task 0026: which sensitivity levels a person may open, worked out on the server from their role's capabilities
 * (VIEW_CONFIDENTIAL and VIEW_HIGHLY_CONFIDENTIAL). Standard is open to everyone with document access; each higher
 * level needs its own capability. Always compared with a document's effective level, never its own level alone.
 */
public record Clearance(boolean confidential, boolean highlyConfidential) {
    /** Standard documents only: the most restrictive clearance, used wherever none is given. */
    public static final Clearance STANDARD_ONLY = new Clearance(false, false);
    public static final Clearance ALL = new Clearance(true, true);

    /** False for a missing level: what can't be classified is never opened. */
    public boolean canOpen(SensitivityLevel level) {
        if (level == null) return false;
        return switch (level) {
            case STANDARD -> true;
            case CONFIDENTIAL -> confidential;
            case HIGHLY_CONFIDENTIAL -> highlyConfidential;
        };
    }

    /** The highest of these levels this clearance can't open, or null when it can open them all. */
    public SensitivityLevel highestLocked(Collection<SensitivityLevel> levels) {
        SensitivityLevel highest = null;
        for (SensitivityLevel level : levels) {
            if (canOpen(level)) continue;
            if (level == null) return SensitivityLevel.HIGHLY_CONFIDENTIAL;
            highest = highest == null ? level : SensitivityLevel.higherOf(highest, level);
        }
        return highest;
    }
}
