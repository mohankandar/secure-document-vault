package com.sdv.platform.web.foundation;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Task 0036: replaces Spring Boot's /error, so answers that never reach a controller carry the one body too: a
 * sign-in refusal from the security filters (401, 403), and anything a filter throws. The text the filters passed
 * to sendError is not sent (it can name what was checked); each status has a fixed sentence.
 * <p>
 * 401 and 403 keep today's generic codes (UNAUTHORIZED, FORBIDDEN) until the sign-in task gives them AUT codes.
 */
@RestController
public class SdvErrorController implements ErrorController {
    static final String UNAUTHORIZED_MESSAGE = "Authentication is required.";
    static final String FORBIDDEN_MESSAGE = "Access is denied.";

    @RequestMapping("/error")
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Throwable ex = unwrap(request.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
        int status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code ? code : 500;
        if (ex != null) {
            ErrorReplies.answer(request, response, ErrorReplies.failure(ex), ex);
        } else if (status == 401) {
            ErrorReplies.write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", UNAUTHORIZED_MESSAGE);
        } else if (status == 403) {
            ErrorReplies.write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN", FORBIDDEN_MESSAGE);
        } else if (status >= 400 && status < 500) {
            ErrorReplies.Reply reply = new ErrorReplies.Reply(org.springframework.http.HttpStatusCode.valueOf(status),
                    com.sdv.platform.domain.error.SdvErrorCode.SYS_015,
                    ErrorReplies.SYS_015_PREFIX + "it is not in a form Vero accepts.");
            ErrorReplies.answer(request, response, reply, null);
        } else {
            ErrorReplies.answer(request, response, ErrorReplies.failure(new IllegalStateException(
                    "Answered " + status + " without an exception.")), null);
        }
    }

    private static Throwable unwrap(Object attribute) {
        Throwable ex = attribute instanceof Throwable t ? t : null;
        while (ex instanceof ServletException servlet && servlet.getRootCause() != null) ex = servlet.getRootCause();
        return ex;
    }
}
