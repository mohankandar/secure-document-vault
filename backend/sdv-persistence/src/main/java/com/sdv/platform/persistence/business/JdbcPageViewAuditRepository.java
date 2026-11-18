package com.sdv.platform.persistence.business;

import com.sdv.platform.application.port.PageViewAuditPort;
import com.sdv.platform.domain.audit.AuditEvent;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcPageViewAuditRepository implements PageViewAuditPort {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPageViewAuditRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isViewing(UUID tenantId, String actorId, UUID documentId, UUID viewingId, Instant notBefore) {
        Boolean found = jdbc.queryForObject("""
                select exists(
                    select 1 from audit_events
                     where audit_event_id=:viewingId
                       and tenant_id=:tenantId
                       and actor_type='USER'
                       and actor_id=:actorId
                       and action='DOCUMENT_VIEW_OPENED'
                       and target_type='DOCUMENT'
                       and target_id=:documentId
                       and outcome='SUCCESS'
                       and occurred_at>=:notBefore)
                """, new MapSqlParameterSource()
                .addValue("viewingId", viewingId)
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("documentId", documentId.toString())
                .addValue("notBefore", Timestamp.from(notBefore)), Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    @Override
    @Transactional
    public boolean appendPageEventOnce(AuditEvent event, UUID viewingId, int pageNumber, Instant notBefore) {
        // Serialise concurrent requests for the same viewing and page (e.g. a page fetched ahead and then shown),
        // so the check and the insert cannot interleave. The lock ends with the transaction.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:key, 0))",
                new MapSqlParameterSource("key", "sdv-page-view:" + viewingId + ":" + pageNumber), Object.class);
        // Uses idx_audit_events_tenant_actor_action_time (tenant_id, actor_id, action, occurred_at) from migration 018.
        int written = jdbc.update("""
                insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, action, target_type,
                                         target_id, occurred_at, correlation_id, outcome, safe_details)
                select :id, :tenantId, :actorType, :actorId, :action, :targetType,
                       :targetId, :occurredAt, :correlationId, :outcome, cast(:safeDetails as jsonb)
                 where not exists (
                    select 1 from audit_events
                     where tenant_id=:tenantId
                       and actor_id=:actorId
                       and action='DOCUMENT_PAGE_VIEWED'
                       and occurred_at>=:notBefore
                       and target_type='DOCUMENT'
                       and target_id=:targetId
                       and safe_details->>'viewingId'=:viewingId
                       and safe_details->>'page'=:page)
                """, new MapSqlParameterSource()
                .addValue("id", event.auditEventId())
                .addValue("tenantId", event.tenantId())
                .addValue("actorType", event.actorType())
                .addValue("actorId", event.actorId())
                .addValue("action", event.action())
                .addValue("targetType", event.targetType())
                .addValue("targetId", event.targetId())
                .addValue("occurredAt", Timestamp.from(event.occurredAt()))
                .addValue("correlationId", event.correlationId())
                .addValue("outcome", event.outcome().name())
                .addValue("safeDetails", event.safeDetailsJson())
                .addValue("notBefore", Timestamp.from(notBefore))
                .addValue("viewingId", viewingId.toString())
                .addValue("page", Integer.toString(pageNumber)));
        return written > 0;
    }
}
