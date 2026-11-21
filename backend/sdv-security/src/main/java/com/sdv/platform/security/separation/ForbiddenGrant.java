package com.sdv.platform.security.separation;

import com.sdv.platform.domain.error.InternalCheckFailed;
import java.util.Objects;
import java.util.Set;

/**
 * Task 0025, 3a: "a configurable override may never give any of {@code capabilities} to any of {@code roles}".
 * Generic and free of product names, like {@link RoleSeparationRule}.
 */
public record ForbiddenGrant<R, C>(String id, Set<R> roles, Set<C> capabilities) {
    public ForbiddenGrant {
        if (id == null || id.isBlank()) throw new InternalCheckFailed("A forbidden grant needs an id.");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (roles.isEmpty() || capabilities.isEmpty()) {
            throw new InternalCheckFailed("Forbidden grant " + id + " needs roles and capabilities.");
        }
    }

    public boolean forbids(R role, C capability) {
        return roles.contains(role) && capabilities.contains(capability);
    }

    /** The first grant in {@code grants} that forbids giving {@code capability} to {@code role}, or null. */
    public static <R, C> ForbiddenGrant<R, C> find(Iterable<ForbiddenGrant<R, C>> grants, R role, C capability) {
        for (ForbiddenGrant<R, C> grant : grants) if (grant.forbids(role, capability)) return grant;
        return null;
    }
}
