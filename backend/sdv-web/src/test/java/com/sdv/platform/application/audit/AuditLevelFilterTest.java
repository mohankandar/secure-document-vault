package com.sdv.platform.application.audit;

import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.AuditQueryPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0030 (item 6): the audit's Sensitivity filter, so compliance can ask "who opened Highly Confidential documents
 * this month". The filter is the level each event recorded when it happened; a value that names no level is refused
 * with SDV-SEN-019 (never quietly ignored), and an export records the filter it used.
 */
class AuditLevelFilterTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final AuditQueryPort query = mock(AuditQueryPort.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final AuditQueryService service = new AuditQueryService(query, audit,
            new DocumentAccessGuard(mock(BusinessRecordRepository.class), audit),
            Clock.fixed(Instant.parse("2026-11-20T15:00:00Z"), ZoneOffset.UTC));

    private static AuditQueryService.SearchRequest level(String level) {
        return new AuditQueryService.SearchRequest(null, null, null, null, null, null, null, null, null, null, null, null, level);
    }

    @Test
    void theLevelGoesToTheQueryAndNoLevelMeansAny() {
        when(query.findRows(eq(TENANT), any(), anyInt())).thenReturn(List.of());
        service.search(TENANT, "ops", level("HIGHLY_CONFIDENTIAL"), "c");
        service.search(TENANT, "ops", level(null), "c");
        service.search(TENANT, "ops", level(""), "c");
        ArgumentCaptor<AuditQueryPort.Filter> filters = ArgumentCaptor.forClass(AuditQueryPort.Filter.class);
        verify(query, times(3)).findRows(eq(TENANT), filters.capture(), anyInt());
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, filters.getAllValues().get(0).sensitivityLevel());
        assertNull(filters.getAllValues().get(1).sensitivityLevel());
        assertNull(filters.getAllValues().get(2).sensitivityLevel());
        ArgumentCaptor<AuditEvent> viewed = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(3)).append(viewed.capture());
        assertTrue(viewed.getAllValues().get(0).safeDetailsJson().contains("\"filtered\":true"), "a level counts as a filter");
        assertTrue(viewed.getAllValues().get(1).safeDetailsJson().contains("\"filtered\":false"));
    }

    @Test
    void aValueThatNamesNoLevelIsRefusedWithItsCodeAndNothingIsRead() {
        for (String bad : List.of("confidential", " CONFIDENTIAL", "SECRET", "CONFIDENTIAL,STANDARD", " ")) {
            RuntimeException refused = assertThrows(RuntimeException.class, () -> service.search(TENANT, "ops", level(bad), "c"), bad);
            assertEquals("SDV-SEN-019", assertInstanceOf(CodedRefusal.class, refused).errorCode().code(), bad);
            RuntimeException export = assertThrows(RuntimeException.class, () -> service.export(TENANT, "ops",
                    new AuditQueryService.ExportRequest(level(bad), null, null), "c"), bad);
            assertEquals("SDV-SEN-019", assertInstanceOf(CodedRefusal.class, export).errorCode().code(), bad);
            assertInstanceOf(CodedRefusal.BadRequest.class, export, "a 400, " + bad);
        }
        verifyNoInteractions(query);
        verifyNoInteractions(audit);
    }

    @Test
    void anExportRecordsTheLevelFilterItUsed() {
        when(query.findRows(eq(TENANT), any(), anyInt())).thenReturn(List.of());
        service.export(TENANT, "ops", new AuditQueryService.ExportRequest(level("CONFIDENTIAL"), null, null), "c");
        ArgumentCaptor<AuditEvent> exported = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(exported.capture());
        assertEquals("AUDIT_EXPORTED", exported.getValue().action());
        assertTrue(exported.getValue().safeDetailsJson().contains("\"sensitivityLevel\":\"CONFIDENTIAL\""),
                exported.getValue().safeDetailsJson());
    }
}
