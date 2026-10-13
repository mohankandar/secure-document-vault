package com.sdv.platform.web.foundation;

import com.sdv.platform.application.document.InvalidPdfException;
import com.sdv.platform.application.document.PdfTooLargeException;
import com.sdv.platform.application.download.DocumentDownloadUnavailableException;
import com.sdv.platform.application.viewer.DocumentViewerUnavailableException;
import com.sdv.platform.application.redaction.RedactionFailedException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class FoundationExceptionHandler {
    @ExceptionHandler(DocumentDownloadUnavailableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    Map<String, Object> downloadUnavailable(DocumentDownloadUnavailableException ex) {
        return error("DOWNLOAD_UNAVAILABLE", "The current document cannot be downloaded securely right now.");
    }

    @ExceptionHandler(RedactionFailedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    Map<String, Object> redactionFailed(RedactionFailedException ex) {
        return error("REDACTION_FAILED", "Corrective redaction could not complete safely.");
    }

    @ExceptionHandler(DocumentViewerUnavailableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    Map<String, Object> viewerUnavailable(DocumentViewerUnavailableException ex) {
        return error("VIEWER_UNAVAILABLE", "The document cannot be viewed securely right now.");
    }

    @ExceptionHandler(PdfTooLargeException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    Map<String, Object> tooLarge(PdfTooLargeException ex) {
        return error("PDF_TOO_LARGE", ex.getMessage());
    }

    @ExceptionHandler(InvalidPdfException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, Object> invalidPdf(InvalidPdfException ex) {
        return error("INVALID_PDF", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, Object> badRequest(IllegalArgumentException ex) {
        return error("BAD_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, Object> notFound(NoSuchElementException ex) {
        return error("NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    Map<String, Object> forbidden(AccessDeniedException ex) {
        return error("FORBIDDEN", "The authenticated principal does not have the required capability.");
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String, Object> unauthorized(AuthenticationCredentialsNotFoundException ex) {
        return error("UNAUTHORIZED", "Authentication is required.");
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, Object> conflict(IllegalStateException ex) {
        return error("CONFLICT", ex.getMessage());
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("code", code, "message", message, "timestamp", Instant.now().toString());
    }
}
