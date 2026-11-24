package com.sdv.platform.web.schema;

import com.sdv.platform.application.json.VeroJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.web.foundation.ErrorReplies;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Task 0040 part 2 (master section item 2, owner answers third round): readiness when Vero is busy.
 * <p>
 * When the only reason readiness can't pass is that Vero is busy ({@link ReadinessOnly#BUSY}: the pool is full, or
 * PostgreSQL has no connection left), {@code /actuator/health/readiness} answers 503 with
 * {@code {"status":"BUSY","code":"SDV-SYS-018","message":…}} and a {@code Retry-After} header (the setting
 * SDV_BUSY_RETRY_AFTER_SECONDS). Every other readiness answer is Spring's own, unchanged: no busy code and no
 * Retry-After. A probe is not a refused request, so it isn't counted as a busy answer in Problems.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public final class ReadinessBusyAnswer extends OncePerRequestFilter {
    static final String READINESS = "/actuator/health/readiness";
    private static final ObjectMapper JSON = VeroJson.mapper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !READINESS.equals(request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapper);
        if (wrapper.getStatus() == HttpServletResponse.SC_SERVICE_UNAVAILABLE && busy(wrapper.getContentAsByteArray())) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", ReadinessOnly.BUSY.getCode());
            body.put("code", SdvErrorCode.SYS_018.code());
            body.put("message", ErrorReplies.SYS_018_MESSAGE);
            wrapper.resetBuffer();
            wrapper.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(ErrorReplies.busyAnswers().retryAfterSeconds()));
            wrapper.setContentType(MediaType.APPLICATION_JSON_VALUE);
            wrapper.setCharacterEncoding(StandardCharsets.UTF_8.name());
            wrapper.getOutputStream().write(JSON.writeValueAsBytes(body));
        }
        wrapper.copyBodyToResponse();
    }

    /** Spring's readiness body says BUSY. Anything that can't be read is not busy (fail closed: no busy code). */
    static boolean busy(byte[] body) {
        if (body == null || body.length == 0) return false;
        try {
            JsonNode status = JSON.readTree(body).path("status");
            return ReadinessOnly.BUSY.getCode().equals(status.asString(null));
        } catch (tools.jackson.core.JacksonException ex) {
            return false;
        }
    }
}
