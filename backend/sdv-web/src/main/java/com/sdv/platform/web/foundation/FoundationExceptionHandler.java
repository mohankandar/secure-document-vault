package com.sdv.platform.web.foundation;

import com.sdv.platform.application.audit.AuditQueryService;
import com.sdv.platform.application.document.InvalidPdfException;
import com.sdv.platform.application.document.PdfTooLargeException;
import com.sdv.platform.application.download.DocumentDownloadUnavailableException;
import com.sdv.platform.application.viewer.DocumentViewerUnavailableException;
import com.sdv.platform.application.redaction.RedactionFailedException;
import com.sdv.platform.domain.error.CodedRefusal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.NoSuchElementException;

/**
 * Every error a controller lets out is answered here with the one body (task 0036, {@link ErrorReplies}):
 * {@code {code, message, reference, timestamp}}. Refusals keep their codes and statuses; Vero's own failures, the
 * database, Spring MVC's refusals and anything unexpected are answered by {@link ErrorReplies#failure(Throwable)}.
 */
@RestControllerAdvice
public class FoundationExceptionHandler {
    @ExceptionHandler(DocumentDownloadUnavailableException.class)
    void downloadUnavailable(DocumentDownloadUnavailableException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.UNPROCESSABLE_ENTITY, "DOWNLOAD_UNAVAILABLE", "The current document cannot be downloaded securely right now.");
    }

    @ExceptionHandler(RedactionFailedException.class)
    void redactionFailed(RedactionFailedException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.UNPROCESSABLE_ENTITY, "REDACTION_FAILED", "Corrective redaction could not complete safely.");
    }

    @ExceptionHandler(DocumentViewerUnavailableException.class)
    void viewerUnavailable(DocumentViewerUnavailableException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.UNPROCESSABLE_ENTITY, "VIEWER_UNAVAILABLE", "The document cannot be viewed securely right now.");
    }

    @ExceptionHandler(PdfTooLargeException.class)
    void tooLarge(PdfTooLargeException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.PAYLOAD_TOO_LARGE, "PDF_TOO_LARGE", ex.getMessage());
    }

    @ExceptionHandler(InvalidPdfException.class)
    void invalidPdf(InvalidPdfException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.BAD_REQUEST, "INVALID_PDF", ex.getMessage());
    }

    @ExceptionHandler(AuditQueryService.ExportTooLargeException.class)
    void exportTooLarge(AuditQueryService.ExportTooLargeException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.UNPROCESSABLE_ENTITY, "EXPORT_TOO_LARGE", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    void badRequest(IllegalArgumentException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.BAD_REQUEST, code(ex, "BAD_REQUEST"), ex.getMessage());
    }

    /**
     * Task 0032: the code this handler sends for a refusal (a 4xx answer), as the sender sees it; null for anything that
     * is not a refusal (a 5xx failure). The intake endpoints record their refusals with it (INTAKE_REFUSED).
     * Task 0036: InternalCheckFailed and "no storage" (SDV-STO-001) are failures, not refusals, so they give null.
     */
    public static String refusalCode(Throwable ex) {
        if (ex instanceof CodedRefusal coded) return coded.errorCode().code();
        if (ex instanceof PdfTooLargeException) return "PDF_TOO_LARGE";
        if (ex instanceof InvalidPdfException) return "INVALID_PDF";
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
        refuse(rq, rs, HttpStatus.NOT_FOUND, code(ex, "NOT_FOUND"), ex.getMessage());
    }

    /** Task 0026: a coded 403 (for example SDV-SEN-008, a restricted document). Its message names nothing else. */
    @ExceptionHandler(CodedRefusal.Forbidden.class)
    void codedForbidden(CodedRefusal.Forbidden ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.FORBIDDEN, ex.errorCode().code(), ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    void forbidden(AccessDeniedException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.FORBIDDEN, "FORBIDDEN", "The authenticated principal does not have the required capability.");
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    void unauthorized(AuthenticationCredentialsNotFoundException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required.");
    }

    @ExceptionHandler(IllegalStateException.class)
    void conflict(IllegalStateException ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        refuse(rq, rs, HttpStatus.CONFLICT, code(ex, "CONFLICT"), ex.getMessage());
    }

    /**
     * Task 0036: the last resort. Every exception no handler above takes: InternalCheckFailed (500, SDV-SYS-016/017),
     * no storage (503, SDV-STO-001), the database (503, SDV-SYS-014), Spring MVC's refusals (SDV-SYS-015), and
     * anything else (500, SDV-SYS-013). The log line holds the cause; the body holds only the safe sentence.
     */
    @ExceptionHandler(Exception.class)
    void failure(Exception ex, HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        ErrorReplies.Reply reply = ErrorReplies.failure(ex);
        if (ex instanceof ErrorResponse spring) {
            // For example the Allow header of a 405.
            spring.getHeaders().forEach((name, values) -> values.forEach(v -> rs.addHeader(name, v)));
        }
        ErrorReplies.answer(rq, rs, reply, ex);
    }

    private static void refuse(HttpServletRequest rq, HttpServletResponse rs, HttpStatusCode status, String code, String message)
            throws IOException {
        String text = message == null ? "The request was refused." : message;
        // A refusal whose code the catalog marks "needs a person" gets a WARN line with the reference.
        for (com.sdv.platform.domain.error.SdvErrorCode known : com.sdv.platform.domain.error.SdvErrorCode.values()) {
            if (known.code().equals(code) && known.needsPerson()) {
                ErrorReplies.log(rq, new ErrorReplies.Reply(status, code, text, true), null);
            }
        }
        ErrorReplies.write(rq, rs, status, code, text);
    }
}
