package com.sdv.platform.web.baseline;

import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.web.foundation.FoundationAdminController.MetadataFieldRequest;
import com.sdv.platform.web.foundation.FoundationExceptionHandler;
import com.sdv.platform.web.ingestion.DocumentIntakeController.DocumentReceiptResponse;
import com.sdv.platform.web.ingestion.DocumentIntakeController.DocumentSummaryResponse;
import com.sdv.platform.web.ingestion.SubmissionController.CreateSubmissionRequest;
import com.sdv.platform.web.ingestion.SubmissionController.DocumentStatusResponse;
import com.sdv.platform.web.ingestion.SubmissionController.ExpectedDocumentResponse;
import com.sdv.platform.web.ingestion.SubmissionController.SubmissionProgressResponse;
import com.sdv.platform.web.ingestion.SubmissionController.SubmissionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 0060 (plan 0059, step 0): the JSON Vero answers and what it accepts, over the HTTP set-up that Spring Boot makes
 * for the running app. The published ingestion answers are written exactly; requests with
 * null, text after the JSON, unknown or repeated fields, deep nesting and odd media types give today's outcome.
 */
class HttpJsonBaselineTest {
    private static final UUID A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private interface Use {
        void with(MockMvc mvc) throws Exception;
    }

    private static void vero(Use use) {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                        DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class))
                .withBean(FoundationExceptionHandler.class, FoundationExceptionHandler::new)
                // Task 0070: Vero's own JSON settings, and the settings file's, as in the running app.
                .withUserConfiguration(com.sdv.platform.web.config.JsonLimitsConfiguration.class)
                .withBean(com.sdv.platform.web.foundation.RequestBodyLimitFilter.class, () -> new com.sdv.platform.web.foundation.RequestBodyLimitFilter(1_048_576, 5_242_880))
                .withBean(Probe.class, Probe::new)
                .run(context -> use.with(MockMvcBuilders.webAppContextSetup(context).build()));
    }

    /** Every kind of value an answer holds, names not in alphabetical order. */
    record Kinds(Instant zoned, Instant nanos, LocalDate day, SensitivityLevel level, BigDecimal decimal, double number, long count,
                 Integer none, boolean flag, UUID id, List<String> list, Map<String, Object> map, byte[] bytes, String text) { }

    @RestController
    static class Probe {
        @GetMapping("/kinds") Kinds kinds() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("z", 1);
            map.put("a", null);
            return new Kinds(Instant.parse("2026-11-24T10:15:30Z"), Instant.parse("2026-11-24T10:15:30.123456700Z"), LocalDate.of(2026, 11, 24),
                    SensitivityLevel.CONFIDENTIAL, new BigDecimal("10.500"), 1.0E10, 9_000_000_000L, null, true, A, List.of("b", "a"), map,
                    new byte[] {1, 2, 3}, "a/b <tag> & 'q' \"q\" Zoë 日 😀  \u007f");
        }

        @GetMapping("/submission") SubmissionResponse submission() {
            return new SubmissionResponse(A, B, "SUB-1", 2, "OPEN", List.of(new ExpectedDocumentResponse("DOC-1", "RECEIVED")),
                    List.of(new DocumentStatusResponse(A, "DOC-1", "file.pdf", "AVAILABLE")),
                    new SubmissionProgressResponse(2, 1, 1, List.of("DOC-2")), Map.of("contractNumber", "3"), false);
        }

        @GetMapping("/submission-empty") SubmissionResponse empty() {
            return new SubmissionResponse(A, B, "SUB-1", 0, "OPEN", List.of(), List.of(), null, Map.of(), true);
        }

        @GetMapping("/receipt") DocumentReceiptResponse receipt() {
            return new DocumentReceiptResponse(A, "DOC-1", "RECEIVED", "AVAILABLE", "ab12", 2202L, false, false, false, "STANDARD");
        }

        @GetMapping("/summary") List<DocumentSummaryResponse> summary() {
            return List.of(new DocumentSummaryResponse(A, "DOC-1", "file.pdf", "FAILED", 0L, "ab12", 3, null, "HIGHLY_CONFIDENTIAL"));
        }

        @PostMapping("/create") String create(@RequestBody CreateSubmissionRequest r) { return String.valueOf(r); }

        @PostMapping("/field") String field(@RequestBody MetadataFieldRequest r) { return String.valueOf(r); }
    }

    private static String line(MockHttpServletResponse r) throws Exception {
        return r.getStatus() + " " + r.getContentType() + " " + r.getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("\"reference\":\"[^\"]*\"", "\"reference\":\"...\"").replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"...\"") + "\n";
    }

    @Test
    void theAnswers() {
        vero(mvc -> {
            StringBuilder text = new StringBuilder();
            for (String path : List.of("/kinds", "/submission", "/submission-empty", "/receipt", "/summary", "/nothing-here")) {
                text.append(path).append(" -> ").append(line(mvc.perform(get(path)).andReturn().getResponse()));
            }
            Baseline.matches("http-answers", text);
        });
    }

    @Test
    void theRequestBodies() {
        vero(mvc -> {
            StringBuilder text = new StringBuilder();
            for (String body : List.of(
                    "{\"sourceSubmissionId\":\"S\",\"expectedDocumentCount\":1}",
                    "{\"sourceSubmissionId\":\"S\",\"expectedDocumentCount\":null}",
                    "{\"sourceSubmissionId\":null}",
                    "{}", "{} x", "{} {}", "{} ", "", "null", "[]", "\"text\"", "{", "{\"a\":1,}", "{'a':1}", "{/*c*/}", "{\"expectedDocumentCount\":NaN}",
                    "{\"unknown\":{\"x\":[1]},\"sourceSubmissionId\":\"S\"}",
                    "{\"sourceSubmissionId\":\"S\",\"sourceSubmissionId\":\"T\"}",
                    "{\"SourceSubmissionId\":\"S\"}",
                    "{\"sourceSubmissionId\":7}", "{\"sourceSubmissionId\":true}", "{\"sourceSubmissionId\":{}}",
                    "{\"expectedDocumentCount\":\"5\"}", "{\"expectedDocumentCount\":\"\"}", "{\"expectedDocumentCount\":1.5}", "{\"expectedDocumentCount\":1.0}",
                    "{\"expectedDocumentCount\":99999999999}", "{\"expectedDocumentCount\":true}",
                    "{\"expectedDocuments\":{\"sourceDocumentId\":\"D\"}}", "{\"expectedDocuments\":[\"D\"]}", "{\"expectedDocuments\":[null]}",
                    "{\"expectedDocuments\":null}",
                    "{\"metadata\":{\"b\":1,\"a\":\"x\",\"n\":null,\"d\":1.50,\"big\":12345678901234567890,\"e\":1E5,\"o\":{},\"l\":[1]}}",
                    "{\"sensitivityLevel\":null,\"recordSensitivityLevel\":null}", "{\"recordSensitivityLevel\":7}")) {
                text.append("create ").append(body).append(" -> ").append(line(mvc.perform(post("/create").contentType("application/json")
                        .content(body)).andReturn().getResponse()));
            }
            for (int depth : new int[] {400, 900, 1100}) {
                String body = "{\"metadata\":{\"a\":" + "[".repeat(depth) + "]".repeat(depth) + "}}";
                text.append("create, nested ").append(depth).append(" -> ").append(mvc.perform(post("/create").contentType("application/json")
                        .content(body)).andReturn().getResponse().getStatus()).append('\n');
            }
            for (String body : List.of("{\"fieldType\":\"DATE\"}", "{\"fieldType\":\"date\"}", "{\"fieldType\":0}", "{\"fieldType\":\"\"}",
                    "{\"fieldType\":\"NOPE\"}", "{\"fieldType\":null}", "{\"required\":null}", "{\"required\":\"true\"}", "{\"required\":1}",
                    "{\"required\":\"yes\"}", "{\"validationRules\":{\"z\":1,\"a\":2.0}}", "{\"validationRules\":null}")) {
                text.append("field ").append(body).append(" -> ").append(line(mvc.perform(post("/field").contentType("application/json")
                        .content(body)).andReturn().getResponse()));
            }
            Baseline.matches("http-request-bodies", text);
        });
    }

    @Test
    void theMediaTypes() {
        vero(mvc -> {
            StringBuilder text = new StringBuilder();
            for (String type : List.of("application/json", "application/json;charset=UTF-8", "application/json; charset=utf-8; charset=us-ascii",
                    "application/json;x", "APPLICATION/JSON", "application/vnd.x+json", "application/merge-patch+json", "text/json", "text/plain",
                    "application/*", "*/*", "json", "application/", "", "application/json;charset=nope", "application/xml", "application/x-yaml")) {
                text.append("Content-Type [").append(type).append("] -> ").append(line(mvc.perform(post("/create").header("Content-Type", type)
                        .content("{}")).andReturn().getResponse()));
            }
            for (String accept : List.of("*/*", "application/json", "application/json;q=0.5, text/html", "application/json;x", "text/html",
                    "application/xml", "application/vnd.x+json", "json", "application/", "", "application/json;v=1;v=2", "application/json;q=2")) {
                text.append("Accept [").append(accept).append("] -> ").append(line(mvc.perform(get("/receipt").header("Accept", accept))
                        .andReturn().getResponse()));
            }
            Baseline.matches("http-media-types", text);
        });
    }
}
