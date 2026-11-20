package com.sdv.platform.security.auth;

import com.sdv.platform.security.SdvRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Profile("local")
@ConditionalOnProperty(prefix = "sdv.auth", name = "mode", havingValue = "local-header")
public class LocalHeaderAuthenticationFilter extends OncePerRequestFilter {
    public static final String TENANT_HEADER = "X-SDV-Tenant-Id";
    public static final String SUBJECT_HEADER = "X-SDV-Subject";
    public static final String ROLES_HEADER = "X-SDV-Roles";
    public static final String SOURCE_HEADER = "X-SDV-Source-Id";

    /**
     * Owner, 2026-11-18 (after task 0011): display names for the fixed local test sign-ins, so audit sentences can be
     * checked on a developer machine. This filter exists only in the local profile with local-header sign-in; any other
     * subject stays unnamed, and the name is never taken from a request header.
     * Task 0018: the names (and emails) of the approved canvas's people, so a local screen looks like the canvas.
     */
    static final Map<String, String> LOCAL_TEST_NAMES = Map.of(
            "local-document-steward", "Daniel Reyes",
            "local-business-user", "Maya Okafor",
            "local-business-admin", "Priya Natarajan",
            "local-tenant-it-admin", "Jonas Weber",
            "local-tenant-it", "Jonas Weber",
            "local-platform-operations-admin", "Aiko Tanaka",
            "local-platform-operations", "Aiko Tanaka",
            "local-administrator", "Ari Lindqvist");

    /** The same people's work emails (display only, local profile only). */
    static final Map<String, String> LOCAL_TEST_EMAILS = Map.of(
            "local-document-steward", "daniel.reyes@acmelending.com",
            "local-business-user", "maya.okafor@acmelending.com",
            "local-business-admin", "priya.natarajan@acmelending.com",
            "local-tenant-it-admin", "jonas.weber@acmelending.com",
            "local-tenant-it", "jonas.weber@acmelending.com",
            "local-platform-operations-admin", "aiko.tanaka@acmelending.com",
            "local-platform-operations", "aiko.tanaka@acmelending.com",
            "local-administrator", "ari.lindqvist@acmelending.com");

    private final RoleSeparationGuard separationGuard;

    /** Without a reporter, a local sign-in that names conflicting roles is refused (task 0025). */
    public LocalHeaderAuthenticationFilter() {
        this(null);
    }

    @Autowired
    public LocalHeaderAuthenticationFilter(RoleConflictReporter conflictReporter) {
        this.separationGuard = new RoleSeparationGuard(conflictReporter);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String tenantText = request.getHeader(TENANT_HEADER);
        String subject = request.getHeader(SUBJECT_HEADER);
        String rolesText = request.getHeader(ROLES_HEADER);
        String sourceText = request.getHeader(SOURCE_HEADER);

        boolean anyLocalHeader = tenantText != null || subject != null || rolesText != null || sourceText != null;
        if (anyLocalHeader) {
            if (tenantText == null || subject == null || subject.isBlank()) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Tenant and subject local SDV authentication headers are required.");
                return;
            }
            if ((rolesText == null || rolesText.isBlank()) == (sourceText == null || sourceText.isBlank())) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                        "Local SDV authentication must identify exactly one user-role principal or ingestion-source principal.");
                return;
            }

            try {
                UUID tenantId = UUID.fromString(tenantText);
                if (sourceText != null && !sourceText.isBlank()) {
                    UUID sourceId = UUID.fromString(sourceText.trim());
                    IngestionSourcePrincipal principal = new IngestionSourcePrincipal(tenantId, sourceId, subject.trim());
                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, List.of(new SimpleGrantedAuthority("SDV_INGESTION_SOURCE")));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    EnumSet<SdvRole> named = EnumSet.noneOf(SdvRole.class);
                    Arrays.stream(rolesText.split(","))
                            .map(String::trim)
                            .filter(value -> !value.isEmpty())
                            .map(SdvRole::valueOf)
                            .forEach(named::add);
                    if (named.isEmpty()) throw new IllegalArgumentException("At least one SDV role is required.");

                    String displayName = LOCAL_TEST_NAMES.get(subject.trim());
                    // Task 0025, rule 1: the same separation as a sign-in through login groups.
                    EnumSet<SdvRole> roles;
                    try {
                        roles = separationGuard.apply(tenantId, subject.trim(), displayName,
                                RoleConflictReporter.Source.LOCAL_SIGN_IN, named);
                    } catch (RoleSeparationGuard.RefusedException refused) {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, refused.getMessage());
                        return;
                    }

                    SdvPrincipal principal = new SdvPrincipal(tenantId, subject.trim(), roles,
                            displayName, LOCAL_TEST_EMAILS.get(subject.trim()));
                    List<SimpleGrantedAuthority> authorities = roles.stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                            .toList();
                    var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (IllegalArgumentException ex) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid local SDV authentication headers.");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
