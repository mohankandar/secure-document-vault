package com.sdv.platform.web.schema;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SchemaGateFilterTest {
    private static MockHttpServletResponse call(SchemaVersion.State state, String path) throws Exception {
        SchemaGateFilter filter = new SchemaGateFilter(new DatabaseSchemaCheck(new SchemaVersion.Status(state, List.of(), List.of(), 0)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void anOlderDatabaseRefusesBusinessRequestsWithThePlainSentence() throws Exception {
        MockHttpServletResponse response = call(SchemaVersion.State.OLDER, "/api/v1/business/search");
        assertEquals(503, response.getStatus());
        // Task 0036: SDV-SYS-001 in place of DATABASE_OLDER, with the reference (the correlation ID) and the time.
        com.fasterxml.jackson.databind.JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertEquals("SDV-SYS-001", body.get("code").asText());
        assertEquals(SchemaVersion.OLDER_MESSAGE, body.get("message").asText());
        assertFalse(body.get("reference").asText().isBlank());
        assertEquals(body.get("reference").asText(), response.getHeader("X-Correlation-Id"));
        assertTrue(body.has("timestamp"));
        assertEquals(4, body.size());
    }

    @Test
    void aNewerDatabaseSaysNewer() throws Exception {
        MockHttpServletResponse response = call(SchemaVersion.State.NEWER, "/api/v1/operations/summary");
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("SDV-SYS-002"));
        assertTrue(response.getContentAsString().contains("The database is newer than this Vero version."));
    }

    @Test
    void anUnreachableDatabaseAtStartIsSys003() throws Exception {
        MockHttpServletResponse response = call(SchemaVersion.State.UNREACHABLE, "/api/v1/operations/summary");
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":\"SDV-SYS-003\""));
        assertTrue(response.getContentAsString().contains("\"reference\":"));
    }

    @Test
    void healthAndSystemInformationStayOpen() throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/api/v1/system/info", "/error")) {
            assertEquals(200, call(SchemaVersion.State.OLDER, path).getStatus(), path);
        }
    }

    @Test
    void aMatchingDatabasePassesEverythingThrough() throws Exception {
        assertEquals(200, call(SchemaVersion.State.CURRENT, "/api/v1/business/search").getStatus());
    }
}
