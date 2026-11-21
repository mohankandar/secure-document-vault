package com.sdv.platform.security.separation;

import com.sdv.platform.domain.error.InternalCheckFailed;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Task 0025, 3a: applies a list of {@link RoleSeparationRule}s to the roles a person would get at sign-in.
 *
 * <p>Fail closed: for every rule the held roles break, the person gets <em>none</em> of the held roles named by that
 * rule, on either side. Roles no broken rule names are kept. The caller decides what to record (audit, problem).
 */
public final class RoleSeparationPolicy<R> {
    private final List<RoleSeparationRule<R>> rules;

    public RoleSeparationPolicy(List<RoleSeparationRule<R>> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        Set<String> ids = new HashSet<>();
        for (RoleSeparationRule<R> rule : this.rules) {
            if (!ids.add(rule.id())) throw new InternalCheckFailed("Duplicate role separation rule " + rule.id());
        }
    }

    public List<RoleSeparationRule<R>> rules() {
        return rules;
    }

    public Resolution<R> resolve(Set<R> held) {
        Objects.requireNonNull(held, "held");
        // Set.of(...).contains(null) throws, so nulls are looked for one by one.
        if (held.stream().anyMatch(Objects::isNull)) throw new InternalCheckFailed("Held roles must not contain null.");
        Set<R> withheld = new LinkedHashSet<>();
        List<String> broken = new ArrayList<>();
        for (RoleSeparationRule<R> rule : rules) {
            if (!rule.brokenBy(held)) continue;
            broken.add(rule.id());
            for (R role : held) {
                if (rule.roles().contains(role) || rule.neverWith().contains(role)) withheld.add(role);
            }
        }
        Set<R> granted = new LinkedHashSet<>(held);
        granted.removeAll(withheld);
        return new Resolution<>(Set.copyOf(held), Set.copyOf(granted), Set.copyOf(withheld), List.copyOf(broken));
    }

    /**
     * @param held        what the person's login groups (or local sign-in) named
     * @param granted     what they get
     * @param withheld    what they don't get because a rule was broken
     * @param brokenRules the ids of the broken rules, in rule order
     */
    public record Resolution<R>(Set<R> held, Set<R> granted, Set<R> withheld, List<String> brokenRules) {
        public boolean conflicted() {
            return !brokenRules.isEmpty();
        }
    }
}
