package com.sdv.platform.security.auth;

import com.sdv.platform.security.SdvRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import java.util.UUID;

@Component
@Profile("local")
@ConditionalOnProperty(prefix = "sdv.auth", name = "mode", havingValue = "local-header")
public class LocalHeaderAuthenticationFilter extends OncePerRequestFilter {
    public static final String TENANT_HEADER = "X-SDV-Tenant-Id";
    public static final String SUBJECT_HEADER = "X-SDV-Subject";
    public static final String ROLES_HEADER = "X-SDV-Roles";
    public static final String SOURCE_HEADER = "X-SDV-Source-Id";

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
                    EnumSet<SdvRole> roles = EnumSet.noneOf(SdvRole.class);
                    Arrays.stream(rolesText.split(","))
                            .map(String::trim)
                            .filter(value -> !value.isEmpty())
                            .map(SdvRole::valueOf)
                            .forEach(roles::add);
                    if (roles.isEmpty()) throw new IllegalArgumentException("At least one SDV role is required.");

                    SdvPrincipal principal = new SdvPrincipal(tenantId, subject.trim(), roles);
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
