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
     * Task 0029: Dana Ruiz (Legal), the canvas's Restricted Steward (local-restricted-steward).
     */
    static final Map<String, String> LOCAL_TEST_NAMES = Map.of(
            "local-document-steward", "Daniel Reyes",
            "local-restricted-steward", "Dana Ruiz",
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
            "local-restricted-steward", "dana.ruiz@acmelending.com",
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
                refuse(request, response, TENANT_HEADER + " and " + SUBJECT_HEADER + " are required");
                return;
            }
            if ((rolesText == null || rolesText.isBlank()) == (sourceText == null || sourceText.isBlank())) {
                refuse(request, response, "send exactly one of " + ROLES_HEADER + " or " + SOURCE_HEADER);
                return;
            }

            try {
                UUID tenantId = uuid(tenantText, TENANT_HEADER);
                if (sourceText != null && !sourceText.isBlank()) {
                    UUID sourceId = uuid(sourceText.trim(), SOURCE_HEADER);
                    IngestionSourcePrincipal principal = new IngestionSourcePrincipal(tenantId, sourceId, subject.trim());
                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal, null, List.of(new SimpleGrantedAuthority("SDV_INGESTION_SOURCE")));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    EnumSet<SdvRole> named = EnumSet.noneOf(SdvRole.class);
                    Arrays.stream(rolesText.split(","))
                            .map(String::trim)
                            .filter(value -> !value.isEmpty())
                            .map(LocalHeaderAuthenticationFilter::role)
                            .forEach(named::add);
                    if (named.isEmpty()) throw new InvalidHeader(ROLES_HEADER + " names no role");

                    String displayName = LOCAL_TEST_NAMES.get(subject.trim());
                    // Task 0025, rule 1: the same separation as a sign-in through login groups.
                    EnumSet<SdvRole> roles;
                    try {
                        roles = separationGuard.apply(tenantId, subject.trim(), displayName,
                                RoleConflictReporter.Source.LOCAL_SIGN_IN, named);
                    } catch (RoleSeparationGuard.RefusedException refused) {
                        SignInAnswer answer = refused.answer();
                        answer.attachTo(request);
                        response.sendError(answer.status(), answer.code().code());
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
            } catch (InvalidHeader ex) {
                refuse(request, response, ex.getMessage());
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /** Task 0038 (SDV-AUT-008): 401 with the sentence naming which header is wrong (never its value). */
    private static void refuse(HttpServletRequest request, HttpServletResponse response, String which) throws IOException {
        SignInAnswer answer = SignInAnswer.of(HttpServletResponse.SC_UNAUTHORIZED, com.sdv.platform.domain.error.SdvErrorCode.AUT_008,
                SignInAnswer.LOCAL_HEADERS_PREFIX + which + ".", which);
        answer.attachTo(request);
        response.sendError(answer.status(), answer.code().code());
    }

    private static UUID uuid(String value, String header) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new InvalidHeader(header + " is not a UUID");
        }
    }

    private static SdvRole role(String name) {
        try {
            return SdvRole.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new InvalidHeader(ROLES_HEADER + " names a role Vero doesn't have");
        }
    }

    private static final class InvalidHeader extends RuntimeException {
        InvalidHeader(String which) {
            super(which);
        }
    }
}
