package com.sdv.platform.web.foundation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.storage.NoWritableStorageException;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.web.config.CorrelationIdFilter;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 0036: every error leaving Vero has one body {code, message, reference, timestamp}; each mapping's status and
 * code; nothing forbidden in the body; the reference in the body is the one on the log line.
 */
class ErrorPlumbingTest {
    /** Text that must never reach a body: classes, SQL, paths, exception messages, values. */
    private static final List<String> FORBIDDEN = List.of("Exception", "java.", "org.", "com.sdv", "select ", "SELECT",
            "secret-table", "C:\\", "/var/", "affected 2 rows", "disk full at", "token-abc", "at com.", "sqlState");

    private final ObjectMapper json = new ObjectMapper();
    private ListAppender<ILoggingEvent> logs;
    private MockMvc mvc;

    @RestController
    static class Failing {
        @GetMapping("/unexpected") void unexpected() { throw new UncheckedIOException(new IOException("disk full at C:\\vault\\token-abc")); }
        @GetMapping("/database") void database() {
            throw new BadSqlGrammarException("select", "SELECT * FROM secret-table WHERE id='token-abc'",
                    new SQLException("relation secret-table does not exist", "42P01"));
        }
        @GetMapping("/database-down") void databaseDown() {
            throw new DataAccessResourceFailureException("Connection refused", new SQLException("refused", "08001"));
        }
        @GetMapping("/check") void check() { throw new InternalCheckFailed("Tenant-scoped ingestion source update affected 2 rows."); }
        @GetMapping("/audit") void audit() {
            throw InternalCheckFailed.auditNotWritten("The legal hold could not be audited, so nothing was changed.",
                    new IllegalStateException("token-abc"));
        }
        @GetMapping("/storage") void storage() { throw new NoWritableStorageException("No healthy ACTIVE storage volume at /var/sdv."); }
        @GetMapping("/doc/{documentId}") String doc(@PathVariable UUID documentId) { return "ok"; }
        @GetMapping("/header") String header(@RequestHeader("X-SDV-Filename") String name) { return name; }
        @PostMapping(path = "/json", consumes = "application/json") String json(@RequestBody Map<String, Object> body) { return "ok"; }
        @PostMapping(path = "/pdf", consumes = "application/pdf") String pdf(@RequestBody byte[] body) { return "ok"; }
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Failing()).setControllerAdvice(new FoundationExceptionHandler())
                .addFilters(new CorrelationIdFilter()).build();
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ErrorReplies.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ErrorReplies.class)).detachAppender(logs);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** The one body: exactly the four fields, the reference equal to the header, nothing forbidden. */
    private JsonNode expect(MvcResult result, int status, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = body(result);
        assertEquals(List.of("code", "message", "reference", "timestamp"), iterable(body));
        assertEquals(code, body.get("code").asString());
        assertFalse(body.get("message").asString().isBlank());
        assertEquals(result.getResponse().getHeader(CorrelationIdFilter.HEADER), body.get("reference").asString());
        assertFalse(body.get("reference").asString().isBlank());
        assertDoesNotThrow(() -> java.time.Instant.parse(body.get("timestamp").asString()));
        String text = result.getResponse().getContentAsString();
        for (String bad : FORBIDDEN) assertFalse(text.contains(bad), "the body holds \"" + bad + "\": " + text);
        return body;
    }

    private static List<String> iterable(JsonNode body) {
        List<String> names = new java.util.ArrayList<>();
        body.propertyNames().iterator().forEachRemaining(names::add);
        return names;
    }

    private List<ILoggingEvent> lines(Level level, String reference) {
        return logs.list.stream().filter(e -> e.getLevel() == level && e.getFormattedMessage().contains("reference=" + reference)).toList();
    }

    /** Task 0062: an uncoded exception's own text is in the log line only. */
    @Test
    void anUncodedExceptionKeepsItsStatusAndItsTextStaysInTheLog() throws Exception {
        for (Object[] c : new Object[][] {{"/uncoded/argument", 400, "BAD_REQUEST"}, {"/uncoded/state", 409, "CONFLICT"}, {"/uncoded/element", 404, "NOT_FOUND"}}) {
            JsonNode body = expect(uncoded.perform(get((String) c[0])).andReturn(), (int) c[1], (String) c[2]);
            assertFalse(body.get("message").asString().contains("inner text"), body.toString());
            List<ILoggingEvent> info = lines(Level.INFO, body.get("reference").asString());
            assertEquals(1, info.size());
            assertTrue(info.getFirst().getFormattedMessage().contains("reason=inner text of C:/vault  x"), info.getFirst().getFormattedMessage());
        }
        assertEquals("plain coded message", expect(uncoded.perform(get("/uncoded/coded")).andReturn(), 400, "SDV-SEN-002").get("message").asString());
    }

    private final MockMvc uncoded = MockMvcBuilders.standaloneSetup(new Uncoded()).setControllerAdvice(new FoundationExceptionHandler())
            .addFilters(new CorrelationIdFilter()).build();

    @RestController
    static class Uncoded {
        private static final String TEXT = "inner text of C:/vault\r\nx";
        @GetMapping("/uncoded/argument") void argument() { throw new IllegalArgumentException(TEXT); }
        @GetMapping("/uncoded/state") void state() { throw new IllegalStateException(TEXT); }
        @GetMapping("/uncoded/element") void element() { throw new java.util.NoSuchElementException(TEXT); }
        @GetMapping("/uncoded/coded") void coded() {
            throw new com.sdv.platform.domain.error.CodedRefusal.BadRequest(com.sdv.platform.domain.error.SdvErrorCode.SEN_002, "plain coded message");
        }
    }

    @Test
    void anUnexpectedFailureIs500Sys013WithOneErrorLineWithTheSameReference() throws Exception {
        JsonNode body = expect(mvc.perform(get("/unexpected").header(CorrelationIdFilter.HEADER, "ref-unexpected-1")).andReturn(),
                500, "SDV-SYS-013");
        // Task 0062: the reference is always Vero's own (it was the caller's X-Correlation-Id when one was sent).
        String reference = body.get("reference").asString();
        assertTrue(com.documentplatform.errors.ReferenceId.isReference(reference), reference);
        List<ILoggingEvent> errors = lines(Level.ERROR, reference);
        assertEquals("ref-unexpected-1", errors.getFirst().getMDCPropertyMap().get(CorrelationIdFilter.CALLER_MDC_KEY), "the caller's is kept beside it");
        assertEquals(1, errors.size(), "one ERROR line for the 5xx");
        assertTrue(errors.getFirst().getFormattedMessage().contains("code=SDV-SYS-013"));
        assertNotNull(errors.getFirst().getThrowableProxy(), "the log line holds the exact cause for IT");
    }

    @Test
    void aDatabaseFailureIs503Sys014AndTheLogHoldsNoSql() throws Exception {
        JsonNode body = expect(mvc.perform(get("/database")).andReturn(), 503, "SDV-SYS-014");
        List<ILoggingEvent> errors = lines(Level.ERROR, body.get("reference").asString());
        assertEquals(1, errors.size());
        String line = errors.getFirst().getFormattedMessage();
        assertTrue(line.contains("sqlState=42P01"), line);
        assertTrue(line.contains("BadSqlGrammarException"), line);
        assertFalse(line.contains("secret-table"), "no SQL and no values in the log: " + line);
        assertFalse(line.contains("token-abc"), line);
        assertNull(errors.getFirst().getThrowableProxy(), "the stack trace would print the SQL");

        expect(mvc.perform(get("/database-down")).andReturn(), 503, "SDV-SYS-014");
    }

    @Test
    void springsOwnRefusalsAreSys015WithWhatWasWrongAndNoValue() throws Exception {
        JsonNode badId = expect(mvc.perform(get("/doc/not-a-uuid")).andReturn(), 400, "SDV-SYS-015");
        assertEquals("Vero couldn't read this request: the documentId is not valid.", badId.get("message").asString());
        assertFalse(badId.toString().contains("not-a-uuid"), "the value sent is not echoed");
        assertEquals(1, lines(Level.INFO, badId.get("reference").asString()).size(), "an INFO line for IT");

        JsonNode header = expect(mvc.perform(get("/header")).andReturn(), 400, "SDV-SYS-015");
        assertEquals("Vero couldn't read this request: the X-SDV-Filename header is missing.", header.get("message").asString());

        JsonNode badJson = expect(mvc.perform(post("/json").contentType(MediaType.APPLICATION_JSON).content("{not json")).andReturn(),
                400, "SDV-SYS-015");
        assertEquals("Vero couldn't read this request: the request body could not be read.", badJson.get("message").asString());

        // Kept at Spring's own status (not in the approved status changes; question in report 0036).
        JsonNode media = expect(mvc.perform(post("/pdf").contentType(MediaType.TEXT_PLAIN).content("x")).andReturn(), 415, "SDV-SYS-015");
        assertEquals("Vero couldn't read this request: this address accepts only application/pdf.", media.get("message").asString());
        MvcResult method = mvc.perform(post("/header")).andReturn();
        expect(method, 405, "SDV-SYS-015");
        assertEquals("GET", method.getResponse().getHeader("Allow"));
        assertTrue(logs.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR), "refusals are not ERROR lines");
    }

    @Test
    void anInternalCheckIs500Sys016NotA409AndItsSentenceGoesOnlyToTheLog() throws Exception {
        JsonNode body = expect(mvc.perform(get("/check")).andReturn(), 500, "SDV-SYS-016");
        List<ILoggingEvent> errors = lines(Level.ERROR, body.get("reference").asString());
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().getThrowableProxy().getMessage().contains("affected 2 rows"), "the check's sentence is for IT");
        assertNull(FoundationExceptionHandler.refusalCode(new InternalCheckFailed("x")), "not counted as an intake refusal");
    }

    @Test
    void anAuditThatCantBeWrittenIs500Sys017() throws Exception {
        JsonNode body = expect(mvc.perform(get("/audit")).andReturn(), 500, "SDV-SYS-017");
        assertEquals(ErrorReplies.SYS_017_MESSAGE, body.get("message").asString());
        assertEquals(1, lines(Level.ERROR, body.get("reference").asString()).size());
    }

    @Test
    void noStorageSpaceIs503Sto001NotA409() throws Exception {
        JsonNode body = expect(mvc.perform(get("/storage")).andReturn(), 503, "SDV-STO-001");
        assertEquals(ErrorReplies.STO_001_MESSAGE, body.get("message").asString());
        assertEquals(1, lines(Level.ERROR, body.get("reference").asString()).size(), "a 5xx is ERROR even though it needs a person");
        assertNull(FoundationExceptionHandler.refusalCode(new NoWritableStorageException("x")), "not counted as a refusal");
    }

    @Test
    void aRefusalThatNeedsAPersonIsAWarnLine() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setAttribute(CorrelationIdFilter.REFERENCE_ATTRIBUTE, "ref-warn-1");
        ErrorReplies.log(request, new ErrorReplies.Reply(org.springframework.http.HttpStatus.CONFLICT, "SDV-XXX-001", "m", true), null);
        ErrorReplies.log(request, new ErrorReplies.Reply(org.springframework.http.HttpStatus.CONFLICT, "SDV-XXX-002", "m", false), null);
        assertEquals(1, lines(Level.WARN, "ref-warn-1").size());
        assertTrue(lines(Level.WARN, "ref-warn-1").getFirst().getFormattedMessage().contains("SDV-XXX-001"));
        assertEquals(1, logs.list.size(), "a refusal that needs nobody is not logged here");
    }

    @Test
    void theErrorPageGivesTheBodyForSignInRefusalsAndFilterFailures() throws Exception {
        SdvErrorController controller = new SdvErrorController();
        for (int status : new int[]{401, 403}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
            request.setAttribute(CorrelationIdFilter.REFERENCE_ATTRIBUTE, "ref-" + status);
            MockHttpServletResponse response = new MockHttpServletResponse();
            controller.error(request, response);
            assertEquals(status, response.getStatus());
            JsonNode body = json.readTree(response.getContentAsString());
            // Task 0038: a bare 401 is SDV-AUT-001 and a bare 403 SDV-ROL-006 (were UNAUTHORIZED and FORBIDDEN).
            assertEquals(status == 401 ? "SDV-AUT-001" : "SDV-ROL-006", body.get("code").asString());
            assertEquals("ref-" + status, body.get("reference").asString());
            assertEquals(4, body.size());
        }

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new jakarta.servlet.ServletException(
                new DataAccessResourceFailureException("db down", new SQLException("down", "08006"))));
        request.setAttribute(CorrelationIdFilter.REFERENCE_ATTRIBUTE, "ref-filter-db");
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.error(request, response);
        assertEquals(503, response.getStatus());
        assertEquals("SDV-SYS-014", json.readTree(response.getContentAsString()).get("code").asString());
        assertEquals(1, lines(Level.ERROR, "ref-filter-db").size());
    }

    @Test
    void aRefusedBearerTokenKeepsItsHeaderAndGoesToTheErrorPage() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/business/search");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // Task 0038: Vero's entry point replaces withErrorBody(BearerTokenAuthenticationEntryPoint).
        com.sdv.platform.security.config.SignInAnswers.entryPoint("enterprise-jwt")
                .commence(request, response, new BadCredentialsException("bad token"));
        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"), "a bearer header is kept");
        assertTrue(response.isCommitted(), "sendError hands the answer to /error, which writes the body");
    }
}
