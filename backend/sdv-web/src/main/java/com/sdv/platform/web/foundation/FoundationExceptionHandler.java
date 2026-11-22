package com.sdv.platform.web.foundation;

import com.sdv.platform.application.audit.AuditQueryService;
import com.sdv.platform.application.document.InvalidPdfException;
import com.sdv.platform.application.document.PdfTooLargeException;
import com.sdv.platform.application.redaction.RedactionFailedException;
import com.sdv.platform.domain.error.CodedFault;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.web.ingestion.IntakeRefusals;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Every error a controller lets out is answered here with the one body (task 0036, {@link ErrorReplies}):
 * {@code {code, message, reference, timestamp}}. Refusals keep their codes and statuses; Vero's own failures, the
 * database, Spring MVC's refusals and anything unexpected are answered by {@link ErrorReplies#failure(Throwable)}.
 * <p>
 * Task 0039: every answer on an intake address is then recorded ({@link IntakeRefusals}), so the Daily intake report
 * and Problems see each refused or failed intake request, also the ones Spring refused before Vero's code ran.
 */
@RestControllerAdvice
public class FoundationExceptionHandler {
    private final IntakeRefusals intake;

    /** Records nothing on intake addresses (for tests that build the handler by hand). */
    public FoundationExceptionHandler() {
        this(IntakeRefusals.NONE);
    }

    @Autowired
    public FoundationExceptionHandler(IntakeRefusals intake) {
        this.intake = Objects.requireNonNull(intake, "intake");
    }

    /**
     * Task 0043: the viewer or a download couldn't finish (SDV-DOC-003, -004, -005, -007, -009; they were
     * VIEWER_UNAVAILABLE and DOWNLOAD_UNAVAILABLE), answered with the fault's own status (owner answer 1 to report
     * 0043): 503 storage or key service, 500 a failed check, 422 what belongs to the document. No Retry-After. One line
     * at the code's level (ERROR, WARN for DOC-005) with the reference and the exact cause. A download that stopped
     * part-way has a committed answer: nothing more is written, but the line is.
     */
    @ExceptionHandler(CodedFault.class)
    void documentFault(CodedFault ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        String cause = ex.getCause() == null ? "" : " exception=" + ex.getCause().getClass().getName();
        answerCoded(rq, rs, HttpStatus.valueOf(ex.httpStatus()), ex.errorCode().code(), ex.getMessage(),
                (ex.logDetail() == null ? "-" : ex.logDetail()) + cause);
    }

    @ExceptionHandler(RedactionFailedException.class)
    void redactionFailed(RedactionFailedException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.UNPROCESSABLE_ENTITY, "REDACTION_FAILED", "Corrective redaction could not complete safely.");
    }

    /** Task 0039: SDV-INT-014 (it was PDF_TOO_LARGE), with the limit in the message and the log line. */
    @ExceptionHandler(PdfTooLargeException.class)
    void tooLarge(PdfTooLargeException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.PAYLOAD_TOO_LARGE, ex, ex.getMessage());
    }

    /** Task 0039: SDV-INT-015 (it was INVALID_PDF). */
    @ExceptionHandler(InvalidPdfException.class)
    void invalidPdf(InvalidPdfException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.BAD_REQUEST, ex, ex.getMessage());
    }

    @ExceptionHandler(AuditQueryService.ExportTooLargeException.class)
    void exportTooLarge(AuditQueryService.ExportTooLargeException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.UNPROCESSABLE_ENTITY, "EXPORT_TOO_LARGE", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    void badRequest(IllegalArgumentException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.BAD_REQUEST, code(ex, "BAD_REQUEST"), ex.getMessage(), detail(ex));
    }

    /**
     * Task 0032: the code this handler sends for a refusal (a 4xx answer), as the sender sees it; null for anything that
     * is not a refusal (a 5xx failure). Task 0036: InternalCheckFailed and "no storage" (SDV-STO-001) are failures, not
     * refusals, so they give null. Task 0039: the PDF refusals carry SDV-INT-014 and SDV-INT-015.
     */
    public static String refusalCode(Throwable ex) {
        if (ex instanceof CodedRefusal coded) return coded.errorCode().code();
        if (ex instanceof IllegalArgumentException) return "BAD_REQUEST";
        if (ex instanceof NoSuchElementException) return "NOT_FOUND";
        if (ex instanceof IllegalStateException) return "CONFLICT";
        return null;
    }

    /** A refusal with a stable SDV-AREA-NNN code (docs/api/error-codes.md) sends it; others keep the generic code. */
    private static String code(RuntimeException ex, String generic) {
        return ex instanceof CodedRefusal coded ? coded.errorCode().code() : generic;
    }

    @ExceptionHandler(NoSuchElementException.class)
    void notFound(NoSuchElementException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.NOT_FOUND, code(ex, "NOT_FOUND"), ex.getMessage(), detail(ex));
    }

    /** Task 0026: a coded 403 (for example SDV-SEN-008, a restricted document). Its message names nothing else. */
    @ExceptionHandler(CodedRefusal.Forbidden.class)
    void codedForbidden(CodedRefusal.Forbidden ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.FORBIDDEN, ex, ex.getMessage());
    }

    /**
     * Task 0038: SDV-ROL-006, the role doesn't include this action (the action in plain words, from the refusal). An
     * uncoded AccessDeniedException gets the same code with the general sentence.
     */
    @ExceptionHandler(AccessDeniedException.class)
    void forbidden(AccessDeniedException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        if (ex instanceof CodedRefusal coded) {
            refuse(rq, rs, HttpStatus.FORBIDDEN, coded, ex.getMessage());
        } else {
            answerCoded(rq, rs, HttpStatus.FORBIDDEN, SdvErrorCode.ROL_006.code(),
                    "Your role doesn't include this action. Ask someone who has it.", "uncoded refusal");
        }
    }

    /** Task 0038: no sign-in reached the controller: 401 SDV-AUT-001. */
    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    void unauthorized(AuthenticationCredentialsNotFoundException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        answerCoded(rq, rs, HttpStatus.UNAUTHORIZED, SdvErrorCode.AUT_001.code(), "Please sign in again.",
                "no Vero sign-in on the request");
    }

    /** Task 0038: a coded 401 (SDV-AUT-001). */
    @ExceptionHandler(CodedRefusal.Unauthorized.class)
    void codedUnauthorized(CodedRefusal.Unauthorized ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.UNAUTHORIZED, ex, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    void conflict(IllegalStateException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.CONFLICT, code(ex, "CONFLICT"), ex.getMessage(), detail(ex));
    }

    /**
     * Task 0036: the last resort. Every exception no handler above takes: InternalCheckFailed (500, SDV-SYS-016/017),
     * a setup problem (500, for example SDV-INT-010, task 0039), no storage (503, SDV-STO-001), the database (503,
     * SDV-SYS-014), Spring MVC's refusals (SDV-SYS-015), and anything else (500, SDV-SYS-013). The log line holds the
     * cause; the body holds only the safe sentence.
     */
    @ExceptionHandler(Exception.class)
    void failure(Exception ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        ErrorReplies.Reply reply = ErrorReplies.failure(ex);
        if (ex instanceof ErrorResponse spring) {
            // For example the Allow header of a 405.
            spring.getHeaders().forEach((name, values) -> values.forEach(v -> rs.addHeader(name, v)));
        }
        ErrorReplies.answer(rq, rs, reply, ex);
        intake.answered(rq, reply.status(), reply.code());
    }

    private void refuse(HttpServletRequest rq, HttpServletResponse rs, HttpStatusCode status, String code, String message)
            throws IOException {
        refuse(rq, rs, status, code, message, null);
    }

    private void refuse(HttpServletRequest rq, HttpServletResponse rs, HttpStatusCode status, CodedRefusal coded,
                        String message) throws IOException {
        refuse(rq, rs, status, coded.errorCode().code(), message, coded.logDetail());
    }

    /**
     * A refusal whose code the catalog marks "needs a person" gets a WARN line with the reference; task 0038: an
     * expected sign-in or role refusal gets an INFO line. The line holds the refusal's log detail (the exact cause),
     * which is never sent.
     */
    private void refuse(HttpServletRequest rq, HttpServletResponse rs, HttpStatusCode status, String code,
                        String message, String detail) throws IOException {
        String text = message == null ? "The request was refused." : message;
        answerCoded(rq, rs, status, code, text, detail);
    }

    /** Writes and logs the answer, then records it if it was on an intake address (task 0039). */
    private void answerCoded(HttpServletRequest rq, HttpServletResponse rs, HttpStatusCode status, String code,
                             String message, String detail) throws IOException {
        ErrorReplies.answerCoded(rq, rs, status, code, message, detail);
        intake.answered(rq, status, code);
    }

    private static String detail(RuntimeException ex) {
        return ex instanceof CodedRefusal coded ? coded.logDetail() : null;
    }
}
