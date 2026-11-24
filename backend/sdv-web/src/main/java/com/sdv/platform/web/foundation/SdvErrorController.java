package com.sdv.platform.web.foundation;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.auth.SignInAnswer;
import com.sdv.platform.web.ingestion.IntakeRefusals;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Objects;

/**
 * Task 0036: replaces Spring Boot's /error, so answers that never reach a controller carry the one body too: a
 * sign-in refusal from the security filters (401, 403), and anything a filter throws. The text the filters passed
 * to sendError is not sent (it can name what was checked); each status has a fixed sentence.
 * <p>
 * Task 0038: a sign-in refusal carries its answer ({@link SignInAnswer}: code, sentence, and the exact cause for the
 * log line). A bare 401 is SDV-AUT-001 and a bare 403 SDV-ROL-006.
 * <p>
 * Task 0039: an answer to an intake address is recorded ({@link IntakeRefusals}): a failed sign-in there has no known
 * sending system, so it goes to IT's Problems as an unidentified intake attempt.
 */
@RestController
public class SdvErrorController implements ErrorController {
    static final String UNAUTHORIZED_MESSAGE = SignInAnswer.SIGN_IN_AGAIN;
    static final String FORBIDDEN_MESSAGE = SignInAnswer.ROLE_LACKS_THIS;

    private final IntakeRefusals intake;

    /** Records nothing on intake addresses (for tests that run the page by hand). */
    public SdvErrorController() {
        this(IntakeRefusals.NONE);
    }

    @Autowired
    public SdvErrorController(IntakeRefusals intake) {
        this.intake = Objects.requireNonNull(intake, "intake");
    }

    @RequestMapping("/error")
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Throwable ex = unwrap(request.getAttribute(RequestDispatcher.ERROR_EXCEPTION));
        int status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code ? code : 500;
        SignInAnswer signIn = SignInAnswer.on(request);
        if (ex != null) {
            answer(request, response, ErrorReplies.failure(ex), ex);
        } else if (signIn != null) {
            answerCoded(request, response, HttpStatusCode.valueOf(signIn.status()), signIn.code().code(),
                    signIn.message(), signIn.logDetail());
        } else if (status == 401) {
            answerCoded(request, response, HttpStatus.UNAUTHORIZED, SdvErrorCode.AUT_001.code(),
                    UNAUTHORIZED_MESSAGE, "401 without a sign-in answer");
        } else if (status == 403) {
            answerCoded(request, response, HttpStatus.FORBIDDEN, SdvErrorCode.ROL_006.code(),
                    FORBIDDEN_MESSAGE, "403 without a sign-in answer");
        } else if (status >= 400 && status < 500) {
            ErrorReplies.Reply reply = new ErrorReplies.Reply(HttpStatusCode.valueOf(status), SdvErrorCode.SYS_015,
                    ErrorReplies.SYS_015_PREFIX + "it is not in a form Vero accepts.");
            answer(request, response, reply, null);
        } else {
            answer(request, response, ErrorReplies.failure(new IllegalStateException(
                    "Answered " + status + " without an exception.")), null);
        }
    }

    private void answer(HttpServletRequest request, HttpServletResponse response, ErrorReplies.Reply reply,
                        Throwable cause) throws IOException {
        ErrorReplies.answer(request, response, reply, cause);
        intake.answered(request, reply.status(), reply.code());
    }

    private void answerCoded(HttpServletRequest request, HttpServletResponse response, HttpStatusCode status,
                             String code, String message, String detail) throws IOException {
        ErrorReplies.answerCoded(request, response, status, code, message, detail);
        intake.answered(request, status, code);
    }

    private static Throwable unwrap(Object attribute) {
        Throwable ex = attribute instanceof Throwable t ? t : null;
        while (ex instanceof ServletException servlet && servlet.getRootCause() != null) ex = servlet.getRootCause();
        return ex;
    }
}
