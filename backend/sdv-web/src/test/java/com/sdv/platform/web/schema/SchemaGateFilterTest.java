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
        assertEquals("{\"code\":\"DATABASE_OLDER\",\"message\":\"The database is older than this Vero version. Ask your database team to run the schema step (install guide, \\\"Update the database\\\").\"}",
                response.getContentAsString());
    }

    @Test
    void aNewerDatabaseSaysNewer() throws Exception {
        MockHttpServletResponse response = call(SchemaVersion.State.NEWER, "/api/v1/operations/summary");
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("DATABASE_NEWER"));
        assertTrue(response.getContentAsString().contains("The database is newer than this Vero version."));
    }

    @Test
    void healthAndSystemInformationStayOpen() throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/api/v1/system/info", "/error")) {
            assertEquals(200, call(SchemaVersion.State.OLDER, path).getStatus(), path);
        }
    }

    @Test
    void aMatchingDatabasePassesEverythingThrough() throws Exception {
        assertEquals(200, call(SchemaVersion.State.CURRENT, "/api/v1/business/search").getStatus());
    }
}
