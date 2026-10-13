package com.sdv.platform.security.auth;

import com.sdv.platform.security.SdvRole;

import java.util.Set;
import java.util.UUID;

public record SdvPrincipal(UUID tenantId, String subject, Set<SdvRole> roles) {
    public SdvPrincipal {
        roles = Set.copyOf(roles);
    }
}
