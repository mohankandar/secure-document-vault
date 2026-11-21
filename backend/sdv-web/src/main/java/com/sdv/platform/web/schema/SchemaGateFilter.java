package com.sdv.platform.web.schema;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.web.foundation.ErrorReplies;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * While the database doesn't match this version, every request except health and system information gets
 * 503 with the plain sentence, before sign-in is even looked at. Runs right after the correlation-ID filter.
 * Task 0036: the one error body, with SDV codes in place of DATABASE_OLDER, DATABASE_NEWER and DATABASE_UNREACHABLE
 * (SDV-SYS-001, -002, -003; owner answer 0034, item 3), and the request's reference.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class SchemaGateFilter extends OncePerRequestFilter {
    static final Set<String> OPEN = Set.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info", "/api/v1/system/info", "/error");

    private final DatabaseSchemaCheck check;

    public SchemaGateFilter(DatabaseSchemaCheck check) {
        this.check = check;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (check.current() || OPEN.contains(path)) {
            chain.doFilter(request, response);
            return;
        }
        SchemaVersion.Status status = check.status();
        String message = status.reachable() ? status.message() : ErrorReplies.SYS_014_MESSAGE;
        ErrorReplies.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, code(status.state()).code(), message);
    }

    /**
     * Task 0037: while the database can't be reached Vero keeps trying, and the answer is SDV-SYS-014, the same as a
     * database that fails during a request. SDV-SYS-003 is the start-up log line for it.
     */
    static SdvErrorCode code(SchemaVersion.State state) {
        return switch (state) {
            case OLDER -> SdvErrorCode.SYS_001;
            case NEWER -> SdvErrorCode.SYS_002;
            case UNREACHABLE, CURRENT -> SdvErrorCode.SYS_014;
        };
    }
}
