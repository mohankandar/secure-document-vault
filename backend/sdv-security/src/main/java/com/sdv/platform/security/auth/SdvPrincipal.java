package com.sdv.platform.security.auth;

import com.sdv.platform.security.SdvRole;

import java.util.Set;
import java.util.UUID;

/**
 * Authenticated SDV user. {@code displayName} and {@code email} are optional, display-only values
 * from the sign-in token; they are never used for authorization. Blank or over-long values are dropped.
 * <p>
 * Task 0041: {@code tenantId} is the entity this request works in (platform-core's entity UUID; Vero's table is still
 * called {@code tenants}). {@code entities} are all the entities this sign-in may work in, the current one included,
 * for the entity switcher; the roles are the current entity's only.
 */
public record SdvPrincipal(UUID tenantId, String subject, Set<SdvRole> roles, String displayName, String email,
                           Set<UUID> entities) {
    static final int MAX_DISPLAY_LENGTH = 300;

    public SdvPrincipal {
        roles = Set.copyOf(roles);
        displayName = optionalDisplayValue(displayName);
        email = optionalDisplayValue(email);
        entities = entities == null || entities.isEmpty() ? (tenantId == null ? Set.of() : Set.of(tenantId)) : Set.copyOf(entities);
        if (tenantId != null && !entities.contains(tenantId)) {
            throw new IllegalArgumentException("the current entity must be one of the sign-in's entities");
        }
    }

    public SdvPrincipal(UUID tenantId, String subject, Set<SdvRole> roles, String displayName, String email) {
        this(tenantId, subject, roles, displayName, email, null);
    }

    public SdvPrincipal(UUID tenantId, String subject, Set<SdvRole> roles) {
        this(tenantId, subject, roles, null, null, null);
    }

    private static String optionalDisplayValue(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.length() > MAX_DISPLAY_LENGTH ? null : trimmed;
    }
}
