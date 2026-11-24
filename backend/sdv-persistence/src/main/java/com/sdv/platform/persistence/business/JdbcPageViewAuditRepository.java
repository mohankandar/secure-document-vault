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
        return viewing(tenantId, actorId, documentId, null, viewingId, notBefore);
    }

    @Override
    public boolean isSealedViewing(UUID tenantId, String actorId, UUID documentId, UUID sealedId, UUID viewingId, Instant notBefore) {
        return sealedId != null && viewing(tenantId, actorId, documentId, sealedId, viewingId, notBefore);
    }

    @Override
    public boolean isApprovedPrint(UUID tenantId, String actorId, UUID documentId, UUID printId, Instant notBefore) {
        return printId != null && Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(
                    select 1 from audit_events
                     where audit_event_id=:printId and tenant_id=:tenantId and actor_type='USER' and actor_id=:actorId
                       and action='DOCUMENT_PRINTED' and target_type='DOCUMENT' and target_id=:documentId
                       and outcome='SUCCESS' and occurred_at>=:notBefore and safe_details->>'accessRequestId' is not null)
                """, new MapSqlParameterSource().addValue("printId", printId).addValue("tenantId", tenantId)
                .addValue("actorId", actorId).addValue("documentId", documentId.toString())
                .addValue("notBefore", Timestamp.from(notBefore)), Boolean.class));
    }

    private boolean viewing(UUID tenantId, String actorId, UUID documentId, UUID sealedId, UUID viewingId, Instant notBefore) {
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
                       and safe_details->>'sealedOriginalId' is not distinct from cast(:sealedId as text)
                       and occurred_at>=:notBefore)
                """, new MapSqlParameterSource()
                .addValue("viewingId", viewingId)
                .addValue("sealedId", sealedId == null ? null : sealedId.toString())
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("documentId", documentId.toString())
                .addValue("notBefore", Timestamp.from(notBefore)), Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    @Override
    @Transactional
    public boolean appendPreviewEventOnce(AuditEvent event, UUID viewingId, Instant notBefore) {
        return appendOnce(event, viewingId, null, notBefore);
    }

    @Override
    @Transactional
    public boolean appendPageEventOnce(AuditEvent event, UUID viewingId, int pageNumber, Instant notBefore) {
        return appendOnce(event, viewingId, Integer.toString(pageNumber), notBefore);
    }

    private boolean appendOnce(AuditEvent event, UUID viewingId, String page, Instant notBefore) {
        // Serialise concurrent requests for the same viewing and page (e.g. a page fetched ahead and then shown),
        // so the check and the insert cannot interleave. The lock ends with the transaction.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:key, 0))",
                new MapSqlParameterSource("key", "sdv-page-view:" + viewingId + ":" + (page == null ? "previews" : page)), Object.class);
        // Uses idx_audit_events_tenant_actor_action_time (tenant_id, actor_id, action, occurred_at) from migration 018.
        int written = jdbc.update("""
                insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, action, target_type,
                                         target_id, occurred_at, correlation_id, outcome, safe_details, actor_display_name)
                select :id, :tenantId, :actorType, :actorId, :action, :targetType,
                       :targetId, :occurredAt, :correlationId, :outcome, cast(:safeDetails as jsonb), :actorDisplayName
                 where not exists (
                    select 1 from audit_events
                     where tenant_id=:tenantId
                       and actor_id=:actorId
                       and action=:action
                       and occurred_at>=:notBefore
                       and target_type='DOCUMENT'
                       and target_id=:targetId
                       and safe_details->>'viewingId'=:viewingId
                       and (cast(:page as text) is null or safe_details->>'page'=:page))
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
                .addValue("actorDisplayName", event.actorDisplayName())
                .addValue("notBefore", Timestamp.from(notBefore))
                .addValue("viewingId", viewingId.toString())
                .addValue("page", page));
        return written > 0;
    }
}
