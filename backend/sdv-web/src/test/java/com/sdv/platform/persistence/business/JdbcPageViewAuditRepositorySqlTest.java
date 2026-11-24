package com.sdv.platform.persistence.business;

import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The SQL behind "one page event per page per viewing" (task 0007). */
class JdbcPageViewAuditRepositorySqlTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOC = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID VIEWING = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final Instant NOT_BEFORE = Instant.parse("2026-11-18T04:00:00Z");

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcPageViewAuditRepository repository = new JdbcPageViewAuditRepository(jdbc);

    private static String flat(String sql) { return sql.replaceAll("\\s+", " "); }

    @Test
    void aViewingMustBeThisPersonsOwnRecentOpenOfThisDocumentInThisTenant() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(true);
        assertTrue(repository.isViewing(TENANT, "steward", DOC, VIEWING, NOT_BEFORE));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).queryForObject(sql.capture(), params.capture(), eq(Boolean.class));
        String s = flat(sql.getValue());
        for (String part : List.of("audit_event_id=:viewingId", "tenant_id=:tenantId", "actor_type='USER'", "actor_id=:actorId",
                "action='DOCUMENT_VIEW_OPENED'", "target_type='DOCUMENT'", "target_id=:documentId", "outcome='SUCCESS'",
                "occurred_at>=:notBefore")) {
            assertTrue(s.contains(part), part + " missing in: " + s);
        }
        assertEquals("steward", params.getValue().getValue("actorId"));
        assertEquals(DOC.toString(), params.getValue().getValue("documentId"));
    }

    @Test
    void thePageEventIsInsertedOnlyWhenThisViewingHasNoEventForThatPageUnderALock() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        AuditEvent event = new AuditEvent(UUID.randomUUID(), TENANT, "USER", "steward", "DOCUMENT_PAGE_VIEWED", "DOCUMENT",
                DOC.toString(), Instant.parse("2026-11-18T12:00:00Z"), "c", AuditOutcome.SUCCESS,
                "{\"page\":3,\"scale\":1.00,\"viewingId\":\"" + VIEWING + "\"}");
        assertTrue(repository.appendPageEventOnce(event, VIEWING, 3, NOT_BEFORE));

        ArgumentCaptor<String> lockSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> lockParams = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).queryForObject(lockSql.capture(), lockParams.capture(), eq(Object.class));
        assertTrue(lockSql.getValue().contains("pg_advisory_xact_lock"));
        assertEquals("sdv-page-view:" + VIEWING + ":3", lockParams.getValue().getValue("key"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());
        String s = flat(sql.getValue());
        assertTrue(s.contains("insert into audit_events"), s);
        for (String part : List.of("where not exists", "tenant_id=:tenantId", "actor_id=:actorId", "action=:action",
                "occurred_at>=:notBefore", "target_id=:targetId", "safe_details->>'viewingId'=:viewingId", "safe_details->>'page'=:page")) {
            assertTrue(s.contains(part), part + " missing in: " + s);
        }
        assertEquals("3", params.getValue().getValue("page"));
        assertEquals("DOCUMENT_PAGE_VIEWED", params.getValue().getValue("action"), "the same action is looked for as is written");
        assertEquals(VIEWING.toString(), params.getValue().getValue("viewingId"));
    }

    @Test
    void anAlreadyRecordedPageReportsNotWritten() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        AuditEvent event = new AuditEvent(UUID.randomUUID(), TENANT, "USER", "steward", "DOCUMENT_PAGE_VIEWED", "DOCUMENT",
                DOC.toString(), Instant.now(), "c", AuditOutcome.SUCCESS, "{}");
        assertFalse(repository.appendPageEventOnce(event, VIEWING, 3, NOT_BEFORE));
    }
}
