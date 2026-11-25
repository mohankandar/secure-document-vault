package com.sdv.platform.web.erase;

import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.eraselog.EraseLogError;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.storage.local.LocalFilesystemVolumeProbe;
import com.sdv.platform.web.monitoring.EraseLogVerdict;
import com.sdv.platform.web.schema.DatabaseSchemaCheck;
import com.sdv.platform.web.schema.ReadinessOnly;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 0080: while the erase log stops work only health answers, the jobs wait, and the log is asked again by itself. */
class EraseGateTest {
    private final EraseGuard guard = mock(EraseGuard.class);
    private final DatabaseSchemaCheck schema = mock(DatabaseSchemaCheck.class);
    private final WorkGate gate = new WorkGate(schema, guard);
    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<WorkGate> gates = mock(org.springframework.beans.factory.ObjectProvider.class);

    private void state(EraseLogError code, EraseGuard.ApplyFailure failure) {
        EraseGuard.State state = new EraseGuard.State(code == null, code, "m", UUID.randomUUID(), 5, 3, Instant.now(), true, failure);
        when(guard.state()).thenReturn(state);
        when(guard.mayTakeWork()).thenReturn(code == null);
        when(schema.current()).thenReturn(true);
    }

    private MockHttpServletResponse call(String path) throws Exception {
        when(gates.getObject()).thenReturn(gate);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new EraseGateFilter(gates).doFilter(new MockHttpServletRequest("GET", path), response, new MockFilterChain());
        return response;
    }

    @Test
    void aRestoredDatabaseStopsEverythingButHealth() throws Exception {
        state(EraseLogError.ERASE_LOG_RESTORE_DETECTED, null);
        for (String path : List.of("/api/v1/business/search", "/v1/submissions", "/api/v1/system/info", "/actuator/info", "/")) {
            MockHttpServletResponse r = call(path);
            assertEquals(503, r.getStatus(), path);
            JsonNode body = new ObjectMapper().readTree(r.getContentAsString());
            assertEquals("SDV-ERS-001", body.get("code").asString(), path);
            assertFalse(body.get("reference").asString().isBlank());
            assertEquals(4, body.size());
        }
        assertEquals(200, call("/actuator/health").getStatus());
        assertEquals(200, call("/actuator/health/liveness").getStatus());
        MockHttpServletResponse ready = call("/actuator/health/readiness");
        assertEquals(503, ready.getStatus());
        JsonNode body = new ObjectMapper().readTree(ready.getContentAsString());
        assertEquals("SDV-ERS-001", body.get("code").asString());
        assertEquals("ERASE_LOG_RESTORE_DETECTED", body.get("eraseLog").asString());
        assertFalse(gate.open(), "the jobs wait");
        verify(guard, timeout(5000).atLeastOnce()).refresh();
        assertEquals(ReadinessOnly.NOT_READY, new EraseLogHealth(guard).health().getStatus());
        assertEquals(com.documentplatform.monitoring.CheckState.DOWN, EraseLogVerdict.of(guard.state()).state());
    }

    @Test
    void aPositionThatCantBeReadIsTheDatabasesAnswer() throws Exception {
        state(EraseLogError.ERASE_POSITION_FAILED, null);
        assertTrue(call("/api/v1/business/search").getContentAsString().contains("SDV-SYS-014"));
        assertEquals(200, call("/actuator/health/readiness").getStatus(), "readiness has the database's own check");
    }

    @Test
    void inStepEverythingGoesOnAndAPendingEntryShowsOnHealth() throws Exception {
        state(null, null);
        assertEquals(200, call("/api/v1/business/search").getStatus());
        assertTrue(gate.open());
        assertEquals(Status.UP, new EraseLogHealth(guard).health().getStatus());
        assertEquals(com.documentplatform.monitoring.CheckState.WORKING, EraseLogVerdict.of(guard.state()).state());

        String id = UUID.randomUUID().toString();
        state(null, new EraseGuard.ApplyFailure(4, "document", "document", id, "SDV_ERS_003", EraseLogError.ERASE_HANDLER_FAILED, Instant.now()));
        assertTrue(gate.open());
        assertEquals(com.documentplatform.monitoring.CheckState.HAVING_PROBLEMS, EraseLogVerdict.of(guard.state()).state());
        assertTrue(id.equals(EraseLogVerdict.of(guard.state()).details().get("record")));
        assertEquals("entry 4 document " + id + " SDV-ERS-003", new EraseLogHealth(guard).health().getDetails().get("lastFailure"));

        when(schema.current()).thenReturn(false);
        assertFalse(gate.open());
    }

    @Test
    void theLogIsNeverInAVolumeNorAVolumeInTheLog(@TempDir Path dir) throws Exception {
        Path volume = Files.createDirectories(dir.resolve("documents")), log = dir.resolve("erase-log");
        new EraseLogFolder(log.toString(), List.of(volume.toString()));
        for (String bad : List.of(volume.toString(), volume.resolve("log").toString(), dir.toString())) {
            assertEquals(SdvErrorCode.ERS_002, assertThrows(StartupFailure.class, () -> new EraseLogFolder(bad, List.of(volume.toString()))).errorCode(), bad);
        }
        LocalFilesystemVolumeProbe probe = new LocalFilesystemVolumeProbe(dir.toString(), log.toString());
        assertTrue(probe.allowed(volume.toString()));
        assertFalse(probe.allowed(log.toString()));
        assertFalse(probe.allowed(log.resolve("sub").toString()));
        assertFalse(probe.allowed(dir.toString()));
    }
}
