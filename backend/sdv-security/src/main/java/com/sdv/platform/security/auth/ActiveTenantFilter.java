package com.sdv.platform.security.auth;

import com.sdv.platform.application.access.ActiveTenantCache;
import com.sdv.platform.domain.error.CodedRefusal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 0039 (owner answer to report 0038, 3): after the sign-in is checked, every request of a signed-in person or
 * sending system checks that its tenant exists and is active ({@link ActiveTenantCache}, kept up to 60 s). A suspended
 * or unknown tenant gets 403 SDV-AUT-009 everywhere, with the same sentence for both and a WARN line saying which.
 * Requests without a sign-in (health, system information) are not checked here.
 * <p>
 * It also leaves the verified principal on the request ({@link #PRINCIPAL_ATTRIBUTE}), so Vero's error page can still
 * tell which sending system an intake request came from after the security context is gone.
 */
public final class ActiveTenantFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = ActiveTenantFilter.class.getName() + ".principal";

    private final ActiveTenantCache tenants;

    public ActiveTenantFilter(ActiveTenantCache tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        UUID tenantId = principal instanceof SdvPrincipal person ? person.tenantId()
                : principal instanceof IngestionSourcePrincipal source ? source.tenantId() : null;
        if (principal instanceof SdvPrincipal || principal instanceof IngestionSourcePrincipal) {
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            try {
                tenants.requireActive(tenantId);
            } catch (CodedRefusal.Forbidden refused) {
                SignInAnswer.of(HttpServletResponse.SC_FORBIDDEN, refused.errorCode(), refused.getMessage(),
                        refused.logDetail()).attachTo(request);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, refused.errorCode().code());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
