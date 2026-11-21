package com.sdv.platform.web.foundation;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.auth.SignInAnswer;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Task 0036: replaces Spring Boot's /error, so answers that never reach a controller carry the one body too: a
 * sign-in refusal from the security filters (401, 403), and anything a filter throws. The text the filters passed
 * to sendError is not sent (it can name what was checked); each status has a fixed sentence.
 * <p>
 * Task 0038: a sign-in refusal carries its answer ({@link SignInAnswer}: code, sentence, and the exact cause for the
 * log line). A bare 401 is SDV-AUT-001 and a bare 403 SDV-ROL-006.
 */
@RestController
public class SdvErrorController implements ErrorController {
    static final String UNAUTHORIZED_MESSAGE = SignInAnswer.SIGN_IN_AGAIN;
    static final String FORBIDDEN_MESSAGE = SignInAnswer.ROLE_LACKS_THIS;

    @RequestMapping("/error")
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Throwable ex = unwrap(request.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
        int status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code ? code : 500;
        SignInAnswer signIn = SignInAnswer.on(request);
        if (ex != null) {
            ErrorReplies.answer(request, response, ErrorReplies.failure(ex), ex);
        } else if (signIn != null) {
            ErrorReplies.answerCoded(request, response, HttpStatusCode.valueOf(signIn.status()), signIn.code().code(),
                    signIn.message(), signIn.logDetail());
        } else if (status == 401) {
            ErrorReplies.answerCoded(request, response, HttpStatus.UNAUTHORIZED, SdvErrorCode.AUT_001.code(),
                    UNAUTHORIZED_MESSAGE, "401 without a sign-in answer");
        } else if (status == 403) {
            ErrorReplies.answerCoded(request, response, HttpStatus.FORBIDDEN, SdvErrorCode.ROL_006.code(),
                    FORBIDDEN_MESSAGE, "403 without a sign-in answer");
        } else if (status >= 400 && status < 500) {
            ErrorReplies.Reply reply = new ErrorReplies.Reply(HttpStatusCode.valueOf(status), SdvErrorCode.SYS_015,
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
