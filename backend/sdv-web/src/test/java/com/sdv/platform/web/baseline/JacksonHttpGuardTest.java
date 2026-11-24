package com.sdv.platform.web.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0060 (plan 0059, step 1): a JSON library's own types in a request or an answer compile under Spring Boot 4 and
 * then fail only when they are used (Boot 4 reads with Jackson 3, which can't fill Jackson 2's types). Today's places
 * are the only exceptions; a new one fails this test. The list empties when Vero moves to Jackson 3.
 */
class JacksonHttpGuardTest {
    private static final Set<String> TODAY = Set.of(
            "CreateSubmissionRequest.metadata: com.fasterxml.jackson.databind.JsonNode",
            "CreateSubmissionRequest.recordSensitivityLevel: com.fasterxml.jackson.databind.JsonNode",
            "CreateSubmissionRequest.sensitivityLevel: com.fasterxml.jackson.databind.JsonNode",
            "MetadataFieldRequest.validationRules: com.fasterxml.jackson.databind.JsonNode",
            "SourceFieldRequest.validationRules: com.fasterxml.jackson.databind.JsonNode");

    private static boolean jackson(Class<?> c) {
        return c.getName().startsWith("com.fasterxml.jackson.") || c.getName().startsWith("tools.jackson.");
    }

    @Test
    void jacksonTypesInRequestsAndAnswersAreOnlyTodaysPlaces() throws Exception {
        var handlers = HttpTypes.handlers();
        assertTrue(handlers.size() > 100, "Vero's controller methods were found: " + handlers.size());
        assertEquals(TODAY, HttpTypes.places(handlers, JacksonHttpGuardTest::jackson));
    }

    /** The guard sees one wherever it hides: in a map of lists, a nested record, an answer, a wildcard. */
    @Test
    void theGuardFindsThem() {
        assertEquals(Set.of("Inner.rules: com.fasterxml.jackson.databind.JsonNode", "Probe.answer(): com.fasterxml.jackson.databind.JsonNode",
                        "Probe.listed(): com.fasterxml.jackson.databind.JsonNode"),
                HttpTypes.places(HttpTypes.handlersOf(Probe.class), JacksonHttpGuardTest::jackson));
    }

    record Inner(Map<String, List<JsonNode>> rules) { }

    record Outer(List<Inner> inner) { }

    @RestController
    static class Probe {
        @PostMapping("/a") String nested(@RequestBody Outer body) { return ""; }
        @PostMapping("/b") JsonNode answer() { return null; }
        @PostMapping("/c") List<? extends JsonNode> listed() { return null; }
    }
}
