package com.sdv.platform.web.config;

import com.documentplatform.errors.ReferenceId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Makes the reference of every request: on the log lines, the error body, the audit events and the
 * {@code X-Correlation-Id} answer header. Task 0062: always Vero's own, so one request's lines can't carry another's
 * reference. A caller's {@code X-Correlation-Id} is kept beside it ({@link #CALLER_MDC_KEY}), in the log lines and, as
 * {@code callerReference}, in the audit events; a value that isn't plain is dropped.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    /** Task 0062: the reference the caller sent, if any. Never used as Vero's own. */
    public static final String CALLER_MDC_KEY = "callerReference";
    /** Task 0036: the same ID as a request attribute, so the /error dispatch (after the MDC is cleared) still has it. */
    public static final String REFERENCE_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".reference";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // Task 0074: the reference platform-core made for this request (its filter runs just before this one).
        String made = ReferenceId.current();
        String correlationId = made == null ? ReferenceId.next() : made;
        String caller = request.getHeader(HEADER);
        MDC.put(MDC_KEY, correlationId);
        if (caller != null && SAFE.matcher(caller).matches()) MDC.put(CALLER_MDC_KEY, caller);
        request.setAttribute(REFERENCE_ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(CALLER_MDC_KEY);
        }
    }
}
