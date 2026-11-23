package com.sdv.platform.security.auth;

import com.sdv.platform.domain.error.SdvErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Task 0041 (owner answer 5): a person who has not chosen an entity ({@link EntityChoicePrincipal}) may only list their
 * entities ({@code GET /api/v1/session/entities}) and activate one ({@code POST /api/v1/admin/entities/{slot}/activate}).
 * Every other request gets 409 SDV-AUT-010 ("choose an entity"); the browser then shows the entity chooser.
 */
public final class EntityChoiceFilter extends OncePerRequestFilter {
    public static final String ENTITIES_PATH = "/api/v1/session/entities";
    private static final Pattern ACTIVATE_PATH = Pattern.compile("^/api/v1/admin/entities/[^/]{1,80}/activate$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof EntityChoicePrincipal && !allowed(request)) {
            SignInAnswer.of(409, SdvErrorCode.AUT_010, SignInAnswer.CHOOSE_ENTITY,
                    "no entity chosen; " + request.getMethod() + " needs one").attachTo(request);
            response.sendError(409, SdvErrorCode.AUT_010.code());
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean allowed(HttpServletRequest request) {
        String path = path(request);
        return ("GET".equals(request.getMethod()) && ENTITIES_PATH.equals(path))
                || ("POST".equals(request.getMethod()) && path != null && ACTIVATE_PATH.matcher(path).matches());
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (uri != null && context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }
}
