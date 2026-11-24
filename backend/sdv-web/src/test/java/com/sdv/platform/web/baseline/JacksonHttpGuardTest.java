package com.sdv.platform.web.baseline;

import tools.jackson.databind.JsonNode;
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
 * then fail only when they are used (Boot 4 reads with Jackson 3, which can't fill Jackson 2's types). Task 0070: Vero is
 * on Jackson 3; Jackson 2's types are nowhere in a request or an answer, and nowhere in Vero's code.
 */
class JacksonHttpGuardTest {
    private static boolean jackson(Class<?> c) {
        return c.getName().startsWith("com.fasterxml.jackson.") || c.getName().startsWith("tools.jackson.");
    }

    @Test
    void noJackson2TypeInARequestOrAnAnswer() throws Exception {
        var handlers = HttpTypes.handlers();
        assertTrue(handlers.size() > 100, "Vero's controller methods were found: " + handlers.size());
        assertEquals(Set.of(), HttpTypes.places(handlers, c -> c.getName().startsWith("com.fasterxml.jackson.")));
    }

    /** Jackson 3's exceptions are unchecked and Boot 4 doesn't know Jackson 2's types: none in the code (annotations keep their old name). */
    @Test
    void noJackson2InVerosCode() throws Exception {
        java.nio.file.Path backend = java.nio.file.Path.of("").toAbsolutePath().getParent();
        java.util.List<String> found = new java.util.ArrayList<>();
        int files = 0;
        try (var paths = java.nio.file.Files.walk(backend)) {
            for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) paths.filter(x -> x.toString().endsWith(".java") && !x.toString().contains("target"))::iterator) {
                files++;
                String text = java.nio.file.Files.readString(f);
                for (String part : List.of("databind", "core", "datatype")) if (text.contains("com.fasterxml." + "jackson." + part)) found.add(f.getFileName().toString());
            }
        }
        assertTrue(files > 300, "Vero's sources were found: " + files);
        assertEquals(List.of(), found);
    }

    /** The guard sees one wherever it hides: in a map of lists, a nested record, an answer, a wildcard. */
    @Test
    void theGuardFindsThem() {
        assertEquals(Set.of("Inner.rules: tools.jackson.databind.JsonNode", "Probe.answer(): tools.jackson.databind.JsonNode",
                        "Probe.listed(): tools.jackson.databind.JsonNode"),
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
