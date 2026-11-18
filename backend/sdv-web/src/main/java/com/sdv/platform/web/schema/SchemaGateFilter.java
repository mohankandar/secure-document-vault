package com.sdv.platform.web.schema;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * While the database doesn't match this version, every request except health and system information gets
 * 503 with the plain sentence, before sign-in is even looked at. Runs right after the correlation-ID filter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class SchemaGateFilter extends OncePerRequestFilter {
    static final Set<String> OPEN = Set.of("/actuator/health", "/actuator/info", "/api/v1/system/info", "/error");

    private final DatabaseSchemaCheck check;

    public SchemaGateFilter(DatabaseSchemaCheck check) {
        this.check = check;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (check.current() || OPEN.contains(path) || path.startsWith("/actuator/health/")) {
            chain.doFilter(request, response);
            return;
        }
        SchemaVersion.Status status = check.status();
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"DATABASE_" + status.state().name() + "\",\"message\":\"" + json(status.message()) + "\"}");
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
