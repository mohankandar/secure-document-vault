package com.sdv.platform.web.erase;

import com.documentplatform.eraselog.EraseLogError;
import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.web.foundation.ErrorReplies;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Task 0080: while the erase log stops work, only health answers, before sign-in is looked at; everything else is 503 SDV-ERS-001.
 * Readiness is 503 with that code and the log's own. A position that can't be read is the database's answer (SDV-SYS-014), as before.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public final class EraseGateFilter extends OncePerRequestFilter {
    static final String READINESS = "/actuator/health/readiness";
    static final Set<String> OPEN = Set.of("/actuator/health", "/actuator/health/liveness", READINESS, "/error");
    static final String MESSAGE = "Vero is paused until IT has checked the erase log. Nothing was done.";

    /** Asked for at the first request: filters are made before the checks that may refuse the start. */
    private final ObjectProvider<WorkGate> gates;

    public EraseGateFilter(ObjectProvider<WorkGate> gates) {
        this.gates = gates;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        WorkGate gate = gates.getObject();
        if (gate.eraseLogAllows()) {
            chain.doFilter(request, response);
            return;
        }
        EraseLogError code = gate.code();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean database = code == EraseLogError.ERASE_POSITION_FAILED;
        if (READINESS.equals(path) && code != null && !database) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "OUT_OF_SERVICE");
            body.put("code", SdvErrorCode.ERS_001.code());
            body.put("eraseLog", code.code());
            body.put("message", MESSAGE);
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(VeroJson.mapper().writeValueAsString(body));
        } else if (OPEN.contains(path)) {
            chain.doFilter(request, response);
        } else if (database) {
            ErrorReplies.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, SdvErrorCode.SYS_014.code(), ErrorReplies.SYS_014_MESSAGE);
        } else {
            ErrorReplies.write(request, response, HttpStatus.SERVICE_UNAVAILABLE, SdvErrorCode.ERS_001.code(), MESSAGE);
        }
    }
}
