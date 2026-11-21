package com.sdv.platform.web.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.storage.NoWritableStorageException;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.web.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Task 0036: the one error body every error leaving Vero carries, and the one place that decides what a failure
 * (not a refusal) is answered with and how it is logged.
 * <p>
 * Body: {@code {"code", "message", "reference", "timestamp"}}. The message is always a plain, safe sentence: never a
 * stack trace, an exception class, SQL, a path, a token, a key, document content or a redacted value. The reference
 * is the request's correlation ID (the {@code X-Correlation-Id} response header); the same ID is on the log line and
 * on any audit event the request wrote.
 * <p>
 * Logs: one ERROR line with the reference for every 5xx (the exact cause, for IT); WARN for a refusal whose code
 * needs a person ({@link SdvErrorCode#needsPerson()}); INFO for a request Spring couldn't read (SDV-SYS-015).
 */
public final class ErrorReplies {
    static final Logger LOG = LoggerFactory.getLogger(ErrorReplies.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String SYS_013_MESSAGE =
            "Vero couldn't finish this just now. Try again. If it keeps happening, give your IT team the reference.";
    public static final String SYS_014_MESSAGE ="Vero can't reach its database just now. Try again in a few minutes.";
    static final String SYS_015_PREFIX = "Vero couldn't read this request: ";
    static final String SYS_016_MESSAGE = "Vero stopped this because something inside didn't add up. Nothing was "
            + "changed. Give your IT team the reference.";
    static final String SYS_017_MESSAGE =
            "Vero couldn't record this action, so it wasn't done. Try again in a few minutes.";
    static final String STO_001_MESSAGE =
            "Vero has no storage space for new documents right now. Nothing was stored; send it again later.";
    /** Task 0038: two ACTIVE sign-in connections match one sign-in (data changed outside Vero). */
    static final String IDP_007_SIGN_IN_MESSAGE =
            "Vero can't sign you in because its sign-in setup is inconsistent. Your IT team has been told.";

    private ErrorReplies() { }

    /** What a failure is answered with: status, code, safe message; and whether a person must act (for the log). */
    public record Reply(HttpStatusCode status, String code, String message, boolean needsPerson) {
        Reply(HttpStatusCode status, SdvErrorCode code, String message) {
            this(status, code.code(), message, code.needsPerson());
        }
    }

    /**
     * The answer for anything the refusal handlers don't cover: Vero's own failures, the database, Spring MVC's own
     * refusals, and the last resort (SDV-SYS-013).
     */
    public static Reply failure(Throwable ex) {
        if (ex instanceof InternalCheckFailed check) {
            String message = switch (check.errorCode()) {
                case SYS_017 -> SYS_017_MESSAGE;
                case IDP_007 -> IDP_007_SIGN_IN_MESSAGE;
                default -> SYS_016_MESSAGE;
            };
            return new Reply(HttpStatus.INTERNAL_SERVER_ERROR, check.errorCode(), message);
        }
        if (ex instanceof NoWritableStorageException storage) {
            return new Reply(HttpStatus.SERVICE_UNAVAILABLE, storage.errorCode(), STO_001_MESSAGE);
        }
        if (isDatabase(ex)) {
            return new Reply(HttpStatus.SERVICE_UNAVAILABLE, SdvErrorCode.SYS_014, SYS_014_MESSAGE);
        }
        Reply request = springRequestError(ex);
        if (request != null) return request;
        return new Reply(HttpStatus.INTERNAL_SERVER_ERROR, SdvErrorCode.SYS_013, SYS_013_MESSAGE);
    }

    /**
     * Spring MVC refused the request before Vero's code ran (SDV-SYS-015). Requests it can't read are 400 (owner
     * answer 0034, item 5). A wrong method, content type or Accept header and an unknown address keep Spring's own
     * status (405, 415, 406, 404): moving them to 400 was not in the approved status changes (see report 0036).
     * Null when {@code ex} is not one of Spring's request errors.
     */
    static Reply springRequestError(Throwable ex) {
        String what;
        HttpStatusCode status = HttpStatus.BAD_REQUEST;
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            what = "the " + mismatch.getName() + " is not valid";
        } else if (ex instanceof TypeMismatchException) {
            what = "a value is not valid";
        } else if (ex instanceof MissingRequestHeaderException header) {
            what = "the " + header.getHeaderName() + " header is missing";
        } else if (ex instanceof MissingServletRequestParameterException parameter) {
            what = "the " + parameter.getParameterName() + " parameter is missing";
        } else if (ex instanceof MissingServletRequestPartException part) {
            what = "the " + part.getRequestPartName() + " part is missing";
        } else if (ex instanceof ServletRequestBindingException) {
            what = "a required value is missing";
        } else if (ex instanceof HttpMessageNotReadableException) {
            what = "the request body could not be read";
        } else if (ex instanceof BindException || ex instanceof HandlerMethodValidationException) {
            what = "a value in the request is not valid";
        } else if (ex instanceof HttpMediaTypeNotSupportedException media) {
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            String accepted = media.getSupportedMediaTypes().stream().map(MediaType::toString)
                    .collect(Collectors.joining(", "));
            what = accepted.isEmpty() ? "this address does not accept this content type"
                    : "this address accepts only " + accepted;
        } else if (ex instanceof HttpMediaTypeNotAcceptableException) {
            status = HttpStatus.NOT_ACCEPTABLE;
            what = "Vero can't answer in the format the request asks for";
        } else if (ex instanceof HttpRequestMethodNotSupportedException) {
            status = HttpStatus.METHOD_NOT_ALLOWED;
            what = "this address does not accept this method";
        } else if (ex instanceof NoResourceFoundException || ex instanceof NoHandlerFoundException) {
            status = HttpStatus.NOT_FOUND;
            what = "nothing is at this address";
        } else if (ex instanceof ErrorResponse other && other.getStatusCode().is4xxClientError()) {
            status = other.getStatusCode();
            what = "it is not in a form Vero accepts";
        } else {
            return null;
        }
        return new Reply(status, SdvErrorCode.SYS_015, SYS_015_PREFIX + what + ".");
    }

    /** Spring's DataAccessException, or a JDBC SQLException, anywhere in the chain (for example a failed transaction). */
    static boolean isDatabase(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DataAccessException || t instanceof SQLException) return true;
        }
        return false;
    }

    /** The request's reference: its correlation ID. A new one only if the request somehow has none. */
    public static String reference(HttpServletRequest request) {
        Object attribute = request == null ? null : request.getAttribute(CorrelationIdFilter.REFERENCE_ATTRIBUTE);
        if (attribute instanceof String s && !s.isBlank()) return s;
        String mdc = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (mdc != null && !mdc.isBlank()) return mdc;
        return UUID.randomUUID().toString();
    }

    /** The one error body. */
    public static Map<String, Object> body(String code, String message, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("reference", reference);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    /**
     * Writes the body as JSON, whatever the request's Accept header says, so a PDF or download request still gets the
     * code and the reference. Nothing is written once the answer has started (a download that failed part-way).
     */
    public static void write(HttpServletRequest request, HttpServletResponse response, HttpStatusCode status,
                             String code, String message) throws IOException {
        if (response.isCommitted()) return;
        String reference = reference(request);
        response.resetBuffer();
        response.setStatus(status.value());
        response.setHeader(CorrelationIdFilter.HEADER, reference);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.writeValueAsString(body(code, message, reference)));
        response.flushBuffer();
    }

    /** Logs and writes a failure's answer. */
    public static void answer(HttpServletRequest request, HttpServletResponse response, Reply reply, Throwable cause)
            throws IOException {
        log(request, reply, cause);
        write(request, response, reply.status(), reply.code(), reply.message());
    }

    /**
     * One line per error: ERROR for every 5xx, with the exact cause for IT; WARN for a refusal that needs a person;
     * INFO for a request Spring couldn't read; nothing for other refusals (they are the caller's to fix and are
     * audited where the action is). The path is the route pattern (never the IDs in it) when Spring matched one.
     */
    static void log(HttpServletRequest request, Reply reply, Throwable cause) {
        String reference = reference(request);
        String route = route(request);
        int status = reply.status().value();
        if (reply.status().is5xxServerError()) {
            if (cause != null && isDatabase(cause)) {
                // No message: Spring's and PostgreSQL's messages hold the SQL and can hold the values sent.
                LOG.error("code={} reference={} status={} route={} cause={}", reply.code(), reference, status, route,
                        databaseCause(cause));
            } else if (cause != null) {
                LOG.error("code={} reference={} status={} route={} cause={}", reply.code(), reference, status, route,
                        cause.getClass().getName(), cause);
            } else {
                LOG.error("code={} reference={} status={} route={}", reply.code(), reference, status, route);
            }
        } else if (reply.needsPerson()) {
            LOG.warn("code={} reference={} status={} route={} reason={}", reply.code(), reference, status, route,
                    cause == null ? "-" : cause.getMessage());
        } else if (SdvErrorCode.SYS_015.code().equals(reply.code())) {
            LOG.info("code={} reference={} status={} route={} reason={}", reply.code(), reference, status, route,
                    cause == null ? "-" : cause.getClass().getSimpleName());
        }
    }

    /**
     * Task 0038: one line for a coded answer, with its exact cause ({@code detail}), which the caller never sees:
     * ERROR for a 5xx; WARN for a code that needs a person; INFO for an expected refusal the catalog wants logged
     * ({@link SdvErrorCode#loggedAtInfo()}, the sign-in and role refusals); nothing for other refusals.
     */
    static void logCoded(HttpServletRequest request, HttpStatusCode status, String code, String detail) {
        SdvErrorCode known = known(code);
        String reference = reference(request);
        String route = route(request);
        String reason = detail == null || detail.isBlank() ? "-" : detail;
        if (status.is5xxServerError()) {
            LOG.error("code={} reference={} status={} route={} cause={}", code, reference, status.value(), route, reason);
        } else if (known != null && known.needsPerson()) {
            LOG.warn("code={} reference={} status={} route={} reason={}", code, reference, status.value(), route, reason);
        } else if (known != null && known.loggedAtInfo()) {
            LOG.info("code={} reference={} status={} route={} reason={}", code, reference, status.value(), route, reason);
        }
    }

    /** Task 0038: logs and writes a coded answer whose exact cause is known (a refusal, or a sign-in answer). */
    public static void answerCoded(HttpServletRequest request, HttpServletResponse response, HttpStatusCode status,
                                   String code, String message, String detail) throws IOException {
        logCoded(request, status, code, detail);
        write(request, response, status, code, message);
    }

    private static SdvErrorCode known(String code) {
        for (SdvErrorCode candidate : SdvErrorCode.values()) if (candidate.code().equals(code)) return candidate;
        return null;
    }

    /** For a database failure: the exception classes, the SQL state, and the first Vero method on the stack. */
    static String databaseCause(Throwable ex) {
        StringBuilder classes = new StringBuilder();
        String sqlState = null;
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (!classes.isEmpty()) classes.append(" <- ");
            classes.append(t.getClass().getName());
            if (sqlState == null && t instanceof SQLException sql) sqlState = sql.getSQLState();
        }
        String at = "-";
        for (StackTraceElement frame : ex.getStackTrace()) {
            if (frame.getClassName().startsWith("com.sdv.platform.")) {
                at = frame.getClassName() + "." + frame.getMethodName();
                break;
            }
        }
        return classes + " sqlState=" + (sqlState == null ? "-" : sqlState) + " at=" + at;
    }

    private static String route(HttpServletRequest request) {
        if (request == null) return "-";
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String method = request.getMethod() == null ? "" : request.getMethod() + " ";
        return pattern == null ? method + "(no route)" : method + pattern;
    }

}
