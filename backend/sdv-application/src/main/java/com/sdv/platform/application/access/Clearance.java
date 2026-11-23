package com.sdv.platform.application.access;

import com.sdv.platform.domain.document.SensitivityLevel;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Task 0026: which sensitivity levels a person may open, worked out on the server from their role's capabilities
 * (VIEW_CONFIDENTIAL and VIEW_HIGHLY_CONFIDENTIAL). Standard is open to everyone with document access; each higher
 * level needs its own capability. Always compared with a document's effective level, never its own level alone.
 * <p>
 * Task 0033: {@code roles} are the person's role names (sorted), written in the audit event of a refusal so the
 * Sensitivity report can count attempts by role. They never decide anything here: two clearances that open the same
 * levels are equal, whatever their roles. Empty when not known (then the event names no role).
 */
/* Task 0048: {@code reviewsQuarantine}: the person may review quarantined documents (QUARANTINE_DOCUMENTS), still only
 * the levels they can open. */
public record Clearance(boolean confidential, boolean highlyConfidential, List<String> roles, boolean reviewsQuarantine) {
    /** Standard documents only: the most restrictive clearance, used wherever none is given. */
    public static final Clearance STANDARD_ONLY = new Clearance(false, false);
    public static final Clearance ALL = new Clearance(true, true);

    public Clearance {
        roles = roles == null ? List.of() : roles.stream().filter(Objects::nonNull).sorted().distinct().toList();
    }

    public Clearance(boolean confidential, boolean highlyConfidential, List<String> roles) {
        this(confidential, highlyConfidential, roles, false);
    }

    public Clearance(boolean confidential, boolean highlyConfidential) {
        this(confidential, highlyConfidential, List.of(), false);
    }

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

    /** Task 0048: may review this quarantined document: a reviewer who can open its level. */
    public boolean canReviewQuarantined(SensitivityLevel level) {
        return reviewsQuarantine && canOpen(level);
    }

    /** The levels opened decide equality; the roles are only written in audit events (and review rights are checked apart). */
    @Override
    public boolean equals(Object other) {
        return other instanceof Clearance c && c.confidential == confidential && c.highlyConfidential == highlyConfidential;
    }

    @Override
    public int hashCode() {
        return Objects.hash(confidential, highlyConfidential);
    }
}
