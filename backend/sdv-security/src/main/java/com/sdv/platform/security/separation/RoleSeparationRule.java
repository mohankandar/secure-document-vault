package com.sdv.platform.security.separation;

import java.util.Objects;
import java.util.Set;

/**
 * Task 0025, 3a: "a person holding any of {@code roles} can never also hold any of {@code neverWith}".
 *
 * <p>Generic over the role type and free of product names, so the rule can move into the shared Enterprise Identity
 * module later. Products supply their rules as data.
 *
 * @param id        a stable name for the rule, used in audit details and problem records
 * @param roles     one side of the rule
 * @param neverWith the other side; must not overlap {@code roles}
 */
public record RoleSeparationRule<R>(String id, Set<R> roles, Set<R> neverWith) {
    public RoleSeparationRule {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("A role separation rule needs an id.");
        Objects.requireNonNull(roles, "roles");
        Objects.requireNonNull(neverWith, "neverWith");
        roles = Set.copyOf(roles);
        neverWith = Set.copyOf(neverWith);
        if (roles.isEmpty() || neverWith.isEmpty()) {
            throw new IllegalArgumentException("Role separation rule " + id + " needs roles on both sides.");
        }
        for (R role : roles) {
            if (neverWith.contains(role)) {
                throw new IllegalArgumentException("Role separation rule " + id + " names " + role + " on both sides.");
            }
        }
    }

    /** True when {@code held} has a role from each side. */
    public boolean brokenBy(Set<R> held) {
        return held.stream().anyMatch(roles::contains) && held.stream().anyMatch(neverWith::contains);
    }
}
