package com.sdv.platform.web.foundation;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Task 0062: every request body has a limit, by its Content-Length and by counting while it is read, before sign-in and
 * before any controller. Over it: 413 SDV-SYS-019. The one exception is the document upload address, whose PDF is
 * streamed and counted against the sending system's own limit (SDV-INT-014). A submission has its own, larger limit:
 * its list of documents is long for a large delivery.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public final class RequestBodyLimitFilter extends OncePerRequestFilter {
    public static final String SUBMISSIONS = "/api/v1/ingestion/submissions";
    private static final Pattern UPLOAD = Pattern.compile(SUBMISSIONS + "/[^/]+/documents");
    static final long MIN = 1_024, MAX = 100L * 1024 * 1024;

    private final long bodyLimit;
    private final long submissionLimit;

    @Autowired
    public RequestBodyLimitFilter(@Value("${sdv.limits.request-body-bytes:1048576}") long bodyLimit,
                                  @Value("${sdv.limits.submission-body-bytes:8388608}") long submissionLimit) {
        this.bodyLimit = checked(bodyLimit, "sdv.limits.request-body-bytes (SDV_MAX_REQUEST_BODY_BYTES)");
        this.submissionLimit = checked(submissionLimit, "sdv.limits.submission-body-bytes (SDV_MAX_SUBMISSION_BODY_BYTES)");
    }

    private static long checked(long value, String setting) {
        if (value < MIN || value > MAX) {
            throw new StartupFailure(SdvErrorCode.SYS_005, setting + " must be from " + MIN + " to " + MAX + " bytes.");
        }
        return value;
    }

    /** The larger of the two: the most a JSON document may be. */
    public long largest() {
        return Math.max(bodyLimit, submissionLimit);
    }

    /** Found in the cause chain of the read that went over the limit. */
    public static final class BodyTooLargeException extends IOException {
        BodyTooLargeException(long limit) {
            super("The request body is over " + limit + " bytes.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("POST".equals(request.getMethod()) && UPLOAD.matcher(path).matches()) {
            chain.doFilter(request, response);
            return;
        }
        long limit = path.equals(SUBMISSIONS) ? submissionLimit : bodyLimit;
        if (request.getContentLengthLong() > limit) {
            ErrorReplies.answerCoded(request, response, HttpStatus.PAYLOAD_TOO_LARGE, SdvErrorCode.SYS_019.code(),
                    ErrorReplies.SYS_019_MESSAGE, "Content-Length over " + limit);
            return;
        }
        chain.doFilter(new Limited(request, limit), response);
    }

    private static final class Limited extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream stream;
        private BufferedReader reader;

        Limited(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new Counted(super.getInputStream(), limit);
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                reader = new BufferedReader(new InputStreamReader(getInputStream(),
                        encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding)));
            }
            return reader;
        }
    }

    private static final class Counted extends ServletInputStream {
        private final ServletInputStream in;
        private final long limit;
        private long read;

        Counted(ServletInputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        private void count(long n) throws IOException {
            read += n;
            if (read > limit) throw new BodyTooLargeException(limit);
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) count(n);
            return n;
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }

        @Override
        public int available() throws IOException {
            return in.available();
        }
    }
}
