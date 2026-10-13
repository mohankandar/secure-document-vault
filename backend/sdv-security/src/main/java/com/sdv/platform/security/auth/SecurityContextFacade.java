package com.sdv.platform.security.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityContextFacade {
    public SdvPrincipal requirePrincipal() {
        Authentication authentication = authentication();
        if (!(authentication.getPrincipal() instanceof SdvPrincipal principal)) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                    "Authenticated SDV user principal is required.");
        }
        return principal;
    }

    public IngestionSourcePrincipal requireIngestionSourcePrincipal() {
        Authentication authentication = authentication();
        if (!(authentication.getPrincipal() instanceof IngestionSourcePrincipal principal)) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                    "Authenticated SDV ingestion-source principal is required.");
        }
        return principal;
    }

    private static Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                    "Authenticated SDV principal is required.");
        }
        return authentication;
    }
}
