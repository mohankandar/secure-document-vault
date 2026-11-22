package com.sdv.platform.web.foundation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.business.RecordRefusals;
import com.sdv.platform.application.document.DocumentRefusals;
import com.sdv.platform.application.download.DocumentDownloadUnavailableException;
import com.sdv.platform.application.viewer.DocumentViewerUnavailableException;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.web.config.CorrelationIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Task 0043, items 1 and 2: the DOC and SCH answers carry their code, a catalog sentence and the reference, never the
 * cause; each writes one log line with the reference and the exact cause: ERROR for Vero's own failures (also when
 * answered 422), WARN for SDV-DOC-005 and -008, INFO for the "not there" refusals.
 */
class DocumentCodesAnswerTest {
    private static final UUID DOC = UUID.fromString("dddddddd-0043-0000-0000-000000000001");
    private final ObjectMapper json = new ObjectMapper();
    private ListAppender<ILoggingEvent> logs;
    private MockMvc mvc;

    @RestController
    static class Documents {
        @GetMapping("/doc-001") void missing() { throw DocumentRefusals.notFound(DOC); }
        @GetMapping("/doc-002") void notYet() { throw DocumentRefusals.notAvailable(DOC, DocumentStatus.REDACTION_PROCESSING); }
        @GetMapping("/doc-003") void unreadable() {
            throw new DocumentViewerUnavailableException(SdvErrorCode.DOC_003, "Vero couldn't read this document from "
                    + "storage. Try again later.", "document=" + DOC + " step=storage read failed",
                    new IOException("disk at C:\\vault failed"));
        }
        @GetMapping("/doc-005") void cantDraw() {
            throw new DocumentViewerUnavailableException(SdvErrorCode.DOC_005, "Vero can't draw this page.",
                    "document=" + DOC + " at=page 3");
        }
        @GetMapping("/doc-006") void badPage() { throw new CodedRefusal.BadRequest(SdvErrorCode.DOC_006, "page must be at least 1."); }
        @GetMapping("/doc-007") void unverified() {
            throw new DocumentDownloadUnavailableException(SdvErrorCode.DOC_007, "Vero couldn't verify this document, so "
                    + "it wasn't downloaded.", "document=" + DOC + " step=key service or decryption failed bytesSent=0 "
                    + "grant=given back");
        }
        @GetMapping("/doc-008") void changed() {
            throw new CodedRefusal.Conflict(SdvErrorCode.DOC_008, "This document was just changed.", "document=" + DOC);
        }
        @GetMapping("/sch-002") void record() { throw RecordRefusals.notFound("sending system not of this tenant"); }
        @GetMapping("/sch-002-bad") void badRef() { throw RecordRefusals.invalid("Business-record reference is invalid."); }
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Documents()).setControllerAdvice(new FoundationExceptionHandler())
                .addFilters(new CorrelationIdFilter()).build();
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ErrorReplies.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ErrorReplies.class)).detachAppender(logs);
    }

    private JsonNode answer(String path, int status, String code) throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertEquals(code, body.get("code").asText());
        assertEquals(result.getResponse().getHeader(CorrelationIdFilter.HEADER), body.get("reference").asText());
        String text = result.getResponse().getContentAsString();
        for (String bad : List.of("Exception", "C:\\", "step=", "document=", "grant=", DOC.toString())) {
            assertFalse(text.contains(bad), "the body holds \"" + bad + "\": " + text);
        }
        return body;
    }

    private List<ILoggingEvent> lines(String code) {
        return logs.list.stream().filter(e -> e.getFormattedMessage().contains("code=" + code + " ")).toList();
    }

    private ILoggingEvent oneLine(String code, Level level, String reference) {
        List<ILoggingEvent> found = lines(code);
        assertEquals(1, found.size(), "one line for " + code + ": " + logs.list);
        assertEquals(level, found.getFirst().getLevel(), code);
        assertTrue(found.getFirst().getFormattedMessage().contains("reference=" + reference), found.getFirst().getFormattedMessage());
        return found.getFirst();
    }

    @Test
    void vero_sOwnFailuresAre422WithAnErrorLineHoldingTheCause() throws Exception {
        JsonNode body = answer("/doc-003", 422, "SDV-DOC-003");
        assertEquals("Vero couldn't read this document from storage. Try again later.", body.get("message").asText());
        String line = oneLine("SDV-DOC-003", Level.ERROR, body.get("reference").asText()).getFormattedMessage();
        assertTrue(line.contains("document=" + DOC), line);
        assertTrue(line.contains("step=storage read failed"), line);
        assertTrue(line.contains("exception=java.io.IOException"), line);
        assertFalse(line.contains("C:\\vault"), "never the exception's own text: " + line);

        body = answer("/doc-007", 422, "SDV-DOC-007");
        line = oneLine("SDV-DOC-007", Level.ERROR, body.get("reference").asText()).getFormattedMessage();
        assertTrue(line.contains("grant=given back"), line);
    }

    @Test
    void aPageThatCantBeDrawnAndAChangedDocumentAreWarnLines() throws Exception {
        JsonNode body = answer("/doc-005", 422, "SDV-DOC-005");
        assertTrue(oneLine("SDV-DOC-005", Level.WARN, body.get("reference").asText()).getFormattedMessage().contains("page 3"));
        body = answer("/doc-008", 409, "SDV-DOC-008");
        oneLine("SDV-DOC-008", Level.WARN, body.get("reference").asText());
    }

    @Test
    void notThereAndNotYetAreInfoLinesAndBadRequestsAreNotWarnOrError() throws Exception {
        JsonNode body = answer("/doc-001", 404, "SDV-DOC-001");
        assertEquals("This document isn't there any more, or the link is wrong. Open the record again from search.",
                body.get("message").asText());
        oneLine("SDV-DOC-001", Level.INFO, body.get("reference").asText());
        body = answer("/doc-002", 409, "SDV-DOC-002");
        assertEquals("This document can't be opened yet: a permanent correction is being made to it.",
                body.get("message").asText());
        assertTrue(oneLine("SDV-DOC-002", Level.INFO, body.get("reference").asText()).getFormattedMessage()
                .contains("status=REDACTION_PROCESSING"));

        answer("/doc-006", 400, "SDV-DOC-006");
        assertTrue(lines("SDV-DOC-006").stream().noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.INFO)),
                "a bad page number is a DEBUG line at most");
    }

    @Test
    void aRecordNotFoundAndABrokenLinkReadTheSame() throws Exception {
        JsonNode missing = answer("/sch-002", 404, "SDV-SCH-002");
        JsonNode broken = answer("/sch-002-bad", 400, "SDV-SCH-002");
        assertEquals(missing.get("message").asText(), broken.get("message").asText());
        assertEquals("This record isn't there any more, or the link is wrong. Search for it again.",
                missing.get("message").asText());
        assertEquals(2, lines("SDV-SCH-002").stream().filter(e -> e.getLevel() == Level.INFO).count());
    }
}
