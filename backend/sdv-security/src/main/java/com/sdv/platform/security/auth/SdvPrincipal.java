package com.sdv.platform.security.auth;

import com.sdv.platform.security.SdvRole;

import java.util.Set;
import java.util.UUID;

/**
 * Authenticated SDV user. {@code displayName} and {@code email} are optional, display-only values
 * from the sign-in token; they are never used for authorization. Blank or over-long values are dropped.
 */
public record SdvPrincipal(UUID tenantId, String subject, Set<SdvRole> roles, String displayName, String email) {
    static final int MAX_DISPLAY_LENGTH = 300;

    public SdvPrincipal {
        roles = Set.copyOf(roles);
        displayName = optionalDisplayValue(displayName);
        email = optionalDisplayValue(email);
    }

    public SdvPrincipal(UUID tenantId, String subject, Set<SdvRole> roles) {
        this(tenantId, subject, roles, null, null);
    }

    private static String optionalDisplayValue(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.length() > MAX_DISPLAY_LENGTH ? null : trimmed;
    }
}
