package com.sdv.platform.application.business;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Task 0056: the one rule for a record's primary identifier on every admin page (owner decision, option A).
 * <p>
 * A record's primary identifier is its first non-blank business-identifier value in the sending system's display
 * order (the order the Business admin saved); fields without a saved position come after, and ties go by field key.
 * The SQL form of the same rule is {@code PrimaryIdentifierSql} in sdv-persistence; the two must stay equal.
 * <p>
 * Later: at Vero's case-fields switch-over, platform-fields' {@code primary} flag becomes the source of the primary
 * identifier, and only this helper and {@code PrimaryIdentifierSql} change.
 */
public final class PrimaryIdentifierRule {
    private PrimaryIdentifierRule() { }

    /** Field keys in the rule's order: saved display position first (unsaved last), then field key. */
    public static Comparator<String> fieldOrder(List<String> displayOrder) {
        Map<String, Integer> position = new HashMap<>();
        if (displayOrder != null) {
            for (int i = 0; i < displayOrder.size(); i++) position.putIfAbsent(displayOrder.get(i), i);
        }
        return Comparator.comparingInt((String key) -> position.getOrDefault(key, Integer.MAX_VALUE))
                .thenComparing(Comparator.naturalOrder());
    }
}
