package com.sdv.platform.web.foundation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.operations.BusyAnswers;
import com.sdv.platform.application.storage.NoWritableStorageException;
import com.sdv.platform.application.storage.StorageProviderMissingException;
import com.sdv.platform.web.config.CorrelationIdFilter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Task 0040 part 2: the busy answer. When Vero is too busy to take a request (today: no database connection is free
 * for it), it answers 503 SDV-SYS-018 with a Retry-After header in seconds. Every other 503 means "unavailable" and
 * carries no Retry-After (SDV-SYS-014 for the database, SDV-STO-001, SDV-STO-004).
 * <p>
 * The pool cases run a real HikariCP pool (Vero's pool) over a stand-in database, so the test proves the shape of
 * Hikari's own exceptions: a full pool, a database that refuses connections, and PostgreSQL's "too many connections".
 */
class BusyAnswerTest {
    private final ObjectMapper json = new ObjectMapper();
    private ListAppender<ILoggingEvent> logs;
    private MockMvc mvc;
    private static final AtomicReference<RuntimeException> NEXT = new AtomicReference<>();

    @RestController
    static class Failing {
        @GetMapping("/work") void work() { throw NEXT.get(); }
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
        NEXT.set(null);
        ErrorReplies.use(new BusyAnswers());
    }

    private MvcResult answer(RuntimeException failure) throws Exception {
        NEXT.set(failure);
        return mvc.perform(get("/work")).andReturn();
    }

    private JsonNode expect(MvcResult result, int status, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertEquals(code, body.get("code").asString());
        assertEquals(4, body.size(), "the one error body");
        return body;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // A real Hikari pool

    /** A pool of one connection, waiting at most 250 ms (Hikari's minimum), over {@code database}. */
    private static HikariDataSource pool(DataSource database) {
        HikariConfig config = new HikariConfig();
        config.setDataSource(database);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(250);
        config.setValidationTimeout(250);
        config.setInitializationFailTimeout(-1);
        config.setPoolName("busy-test");
        return new HikariDataSource(config);
    }

    /** A stand-in database whose connections answer every question harmlessly. */
    private static DataSource database(SQLException refusal) {
        return (DataSource) Proxy.newProxyInstance(BusyAnswerTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        if (refusal != null) throw refusal;
                        return connection();
                    }
                    return defaultFor(method.getReturnType());
                });
    }

    private static Connection connection() {
        return (Connection) Proxy.newProxyInstance(BusyAnswerTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isValid" -> true;
                    case "getAutoCommit" -> true;
                    case "isClosed" -> false;
                    case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                    case "unwrap" -> null;
                    case "isWrapperFor" -> false;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "stand-in connection";
                    default -> defaultFor(method.getReturnType());
                });
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    /** What Spring throws when the pool gives no connection: Hikari's exception inside Spring's. */
    private static RuntimeException noConnection(HikariDataSource pool) {
        CannotGetJdbcConnectionException spring = assertThrows(CannotGetJdbcConnectionException.class,
                () -> DataSourceUtils.getConnection(pool));
        assertInstanceOf(SQLTransientConnectionException.class, spring.getCause(), "Hikari's timeout");
        return spring;
    }

    @Test
    void aFullPoolIsBusy503Sys018WithRetryAfterInSeconds() throws Exception {
        try (HikariDataSource pool = pool(database(null)); Connection held = pool.getConnection()) {
            assertNotNull(held);
            RuntimeException failure = noConnection(pool);
            assertNull(failure.getCause().getCause(), "a full pool: no connection failed");
            MvcResult result = answer(failure);
            JsonNode body = expect(result, 503, "SDV-SYS-018");
            assertEquals("5", result.getResponse().getHeader("Retry-After"), "Retry-After in seconds");
            assertEquals(ErrorReplies.SYS_018_MESSAGE, body.get("message").asString());
            assertFalse(body.get("message").asString().contains("busy-test"), "no pool details in the body");
            // Owner answer 4 to report 0040 part 2: busy is WARN, not ERROR (expected under load, not a fault).
            assertTrue(logs.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR), "no ERROR line for busy");
            List<ILoggingEvent> warnings = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
            assertEquals(1, warnings.size(), "one WARN line");
            assertTrue(warnings.getFirst().getFormattedMessage().contains("code=SDV-SYS-018"));
            assertTrue(warnings.getFirst().getFormattedMessage().contains("SQLTransientConnectionException"));
            assertTrue(warnings.getFirst().getFormattedMessage().contains("reference="));
        }
    }

    @Test
    void aFullPoolAtTheStartOfATransactionIsBusyToo() throws Exception {
        try (HikariDataSource pool = pool(database(null)); Connection held = pool.getConnection()) {
            assertNotNull(held);
            RuntimeException failure = new CannotCreateTransactionException("Could not open JDBC Connection for transaction",
                    noConnection(pool).getCause());
            MvcResult result = answer(failure);
            expect(result, 503, "SDV-SYS-018");
            assertEquals("5", result.getResponse().getHeader("Retry-After"));
        }
    }

    @Test
    void aDatabaseThatRefusesConnectionsIsUnavailable503Sys014WithoutRetryAfter() throws Exception {
        try (HikariDataSource pool = pool(database(new SQLException("Connection refused", "08001")))) {
            RuntimeException failure = noConnection(pool);
            assertInstanceOf(SQLException.class, failure.getCause().getCause(), "Hikari names the last connection failure");
            MvcResult result = answer(failure);
            expect(result, 503, "SDV-SYS-014");
            assertNull(result.getResponse().getHeader("Retry-After"), "no Retry-After when unavailable");
        }
    }

    @Test
    void postgresWithNoConnectionLeftIsBusy() throws Exception {
        try (HikariDataSource pool = pool(database(new SQLException("sorry, too many clients already", "53300")))) {
            MvcResult result = answer(noConnection(pool));
            expect(result, 503, "SDV-SYS-018");
            assertEquals("5", result.getResponse().getHeader("Retry-After"));
        }
        // The same refusal when Spring sees it directly (not through the pool's timeout).
        MvcResult direct = answer(new DataAccessResourceFailureException("no connection",
                new SQLException("sorry, too many clients already", "53300")));
        expect(direct, 503, "SDV-SYS-018");
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Every other 503 has no busy code and no Retry-After

    @Test
    void otherServiceUnavailableAnswersCarryNoRetryAfter() throws Exception {
        record Case(RuntimeException failure, String code) { }
        for (Case c : List.of(
                new Case(new DataAccessResourceFailureException("down", new SQLException("down", "08006")), "SDV-SYS-014"),
                new Case(new DataAccessResourceFailureException("down"), "SDV-SYS-014"),
                // A timeout from something other than the pool (with a cause) is not "busy".
                new Case(new DataAccessResourceFailureException("x", new SQLTransientConnectionException("x", "08001",
                        new SQLException("refused", "08001"))), "SDV-SYS-014"),
                new Case(new NoWritableStorageException("full"), "SDV-STO-001"),
                new Case(new StorageProviderMissingException("Configured storage provider is unavailable: S3"), "SDV-STO-004"))) {
            logs.list.clear();
            MvcResult result = answer(c.failure());
            JsonNode body = expect(result, 503, c.code());
            assertNull(result.getResponse().getHeader("Retry-After"), c.code() + " has no Retry-After");
            assertNotEquals("SDV-SYS-018", body.get("code").asString());
        }
    }

    @Test
    void storageProviderMissingIs503Sto004WithAPlainMessageAndTheProviderOnlyInTheLog() throws Exception {
        MvcResult result = answer(new StorageProviderMissingException("Configured storage provider is unavailable: S3_SOMEWHERE"));
        JsonNode body = expect(result, 503, "SDV-STO-004");
        assertEquals(ErrorReplies.STO_004_MESSAGE, body.get("message").asString());
        assertFalse(result.getResponse().getContentAsString().contains("S3_SOMEWHERE"), "the provider is for the log");
        assertNull(FoundationExceptionHandler.refusalCode(new StorageProviderMissingException("x")), "a failure, not a refusal");
        List<ILoggingEvent> errors = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().getFormattedMessage().contains("code=SDV-STO-004"));
    }

    @Test
    void theErrorPageGivesTheBusyAnswerForAFilterThatFoundNoConnection() throws Exception {
        SdvErrorController controller = new SdvErrorController();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new jakarta.servlet.ServletException(
                new CannotGetJdbcConnectionException("no connection", new SQLTransientConnectionException(
                        "busy-test - Connection is not available, request timed out after 250ms"))));
        request.setAttribute(CorrelationIdFilter.REFERENCE_ATTRIBUTE, "ref-busy-filter");
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.error(request, response);
        assertEquals(503, response.getStatus());
        assertEquals("SDV-SYS-018", json.readTree(response.getContentAsString()).get("code").asString());
        assertEquals("5", response.getHeader("Retry-After"));
    }

    /**
     * Owner answers 3 and 4 to report 0040 part 2: Retry-After is the setting SDV_BUSY_RETRY_AFTER_SECONDS, and each
     * busy answer is counted for Problems; other 503s are not counted and stay ERROR.
     */
    @Test
    void retryAfterIsTheSettingAndEachBusyAnswerIsCounted() throws Exception {
        BusyAnswers busy = new BusyAnswers("12");
        ErrorReplies.use(busy);
        RuntimeException full = new CannotGetJdbcConnectionException("no connection",
                new SQLTransientConnectionException("pool - Connection is not available, request timed out after 30000ms"));
        MvcResult first = answer(full);
        expect(first, 503, "SDV-SYS-018");
        assertEquals("12", first.getResponse().getHeader("Retry-After"));
        expect(answer(full), 503, "SDV-SYS-018");
        assertEquals(2, busy.count().lastHour());
        assertEquals(2, busy.count().sinceStart());

        logs.list.clear();
        MvcResult down = answer(new DataAccessResourceFailureException("down", new SQLException("down", "08006")));
        expect(down, 503, "SDV-SYS-014");
        assertNull(down.getResponse().getHeader("Retry-After"));
        assertEquals(2, busy.count().sinceStart(), "only busy answers are counted");
        assertEquals(1, logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).count(), "other 5xx stay ERROR");
        assertEquals(0, logs.list.stream().filter(e -> e.getLevel() == Level.WARN).count());
    }

    @Test
    void aCodedBusyAnswerIsWarnAndOtherCoded5xxStayError() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ingestion/submissions");
        ErrorReplies.logCoded(request, org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "SDV-SYS-018", "pool full");
        ErrorReplies.logCoded(request, org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "SDV-AUT-004", "no keys");
        assertEquals(List.of(Level.WARN, Level.ERROR), logs.list.stream().map(ILoggingEvent::getLevel).toList());
    }

    @Test
    void isBusyLooksAtTheWholeChainAndNothingElse() {
        assertTrue(ErrorReplies.isBusy(new RuntimeException(new SQLTransientConnectionException("pool timeout"))));
        assertTrue(ErrorReplies.isBusy(new SQLException("too many", "53300")));
        assertFalse(ErrorReplies.isBusy(new SQLException("disk full", "53100")), "out of disk is not busy");
        assertFalse(ErrorReplies.isBusy(new SQLException("refused", "08001")));
        assertFalse(ErrorReplies.isBusy(new IllegalStateException("x")));
        assertFalse(ErrorReplies.isBusy(null));
    }
}
