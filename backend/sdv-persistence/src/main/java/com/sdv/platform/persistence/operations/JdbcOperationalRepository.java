package com.sdv.platform.persistence.operations;

import com.sdv.platform.application.port.AuditQueryPort;
import com.sdv.platform.application.port.OperationalRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcOperationalRepository implements AuditQueryPort, OperationalRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOperationalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AuditEvent> find(UUID tenantId, AuditQueryPort.Filter filter, int limitPlusOne) {
        StringBuilder sql = new StringBuilder("""
                select audit_event_id, tenant_id, actor_type, actor_id, action, target_type, target_id,
                       occurred_at, correlation_id, outcome, safe_details::text as safe_details_json
                  from audit_events
                 where tenant_id=:tenantId
                """);
        MapSqlParameterSource p = new MapSqlParameterSource("tenantId", tenantId).addValue("limit", limitPlusOne);
        exact(sql, p, "action", filter.action());
        exact(sql, p, "actor_type", "actorType", filter.actorType());
        exact(sql, p, "actor_id", "actorId", filter.actorId());
        exact(sql, p, "target_type", "targetType", filter.targetType());
        exact(sql, p, "target_id", "targetId", filter.targetId());
        exact(sql, p, "correlation_id", "correlationId", filter.correlationId());
        if (filter.outcome() != null) {
            sql.append(" and outcome=:outcome");
            p.addValue("outcome", filter.outcome().name());
        }
        if (filter.from() != null) {
            sql.append(" and occurred_at>=:from");
            p.addValue("from", Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            sql.append(" and occurred_at<=:to");
            p.addValue("to", Timestamp.from(filter.to()));
        }
        if (filter.beforeOccurredAt() != null && filter.beforeEventId() != null) {
            sql.append(" and (occurred_at < :beforeTime or (occurred_at=:beforeTime and audit_event_id < :beforeId))");
            p.addValue("beforeTime", Timestamp.from(filter.beforeOccurredAt()));
            p.addValue("beforeId", filter.beforeEventId());
        }
        sql.append(" order by occurred_at desc, audit_event_id desc limit :limit");
        return jdbc.query(sql.toString(), p, (rs, row) -> new AuditEvent(
                rs.getObject("audit_event_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("actor_type"), rs.getString("actor_id"), rs.getString("action"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("correlation_id"), AuditOutcome.valueOf(rs.getString("outcome")),
                rs.getString("safe_details_json")));
    }

    @Override
    public boolean databaseAvailable() {
        Integer one = jdbc.getJdbcTemplate().queryForObject("select 1", Integer.class);
        return one != null && one == 1;
    }

    @Override
    public long countProcessingFailures(UUID tenantId) {
        return count("select count(*) from documents where tenant_id=:tenantId and status='PROCESSING_FAILED'", tenantId);
    }

    @Override
    public List<ProcessingFailure> findProcessingFailures(UUID tenantId, int limit) {
        return jdbc.query("""
                select document_id, submission_id, source_id, processing_error_code,
                       processing_attempt_count, ingested_at
                  from documents
                 where tenant_id=:tenantId and status='PROCESSING_FAILED'
                 order by ingested_at desc, document_id
                 limit :limit
                """, params(tenantId, limit), (rs, row) -> new ProcessingFailure(
                rs.getObject("document_id", UUID.class), rs.getObject("submission_id", UUID.class),
                rs.getObject("source_id", UUID.class), rs.getString("processing_error_code"),
                rs.getInt("processing_attempt_count"), rs.getTimestamp("ingested_at").toInstant()));
    }

    @Override
    public long countStuckDocuments(UUID tenantId, Instant processingCutoff, Instant transitionCutoff) {
        Long value = jdbc.queryForObject("""
                select count(*)
                  from documents d
                  left join document_redaction_operations r
                    on r.tenant_id=d.tenant_id and r.document_id=d.document_id
                 where d.tenant_id=:tenantId
                   and (
                     (d.status='PROCESSING' and d.ingested_at<=:processingCutoff)
                     or (d.status='REDACTION_PROCESSING' and coalesce(r.created_at,d.ingested_at)<=:transitionCutoff)
                     or (d.status='RETENTION_DELETING' and coalesce(d.retention_delete_started_at,d.ingested_at)<=:transitionCutoff)
                   )
                """, stuckParams(tenantId, processingCutoff, transitionCutoff), Long.class);
        return value == null ? 0L : value;
    }

    @Override
    public List<StuckDocument> findStuckDocuments(UUID tenantId, Instant processingCutoff, Instant transitionCutoff, int limit) {
        return queryStuck(tenantId, processingCutoff, transitionCutoff, limit);
    }

    private List<StuckDocument> queryStuck(UUID tenantId, Instant processingCutoff, Instant transitionCutoff, int limit) {
        String sql = """
                select d.document_id, d.submission_id, d.source_id, d.status, d.processing_error_code,
                       case
                         when d.status='REDACTION_PROCESSING' then coalesce(r.created_at, d.ingested_at)
                         when d.status='RETENTION_DELETING' then coalesce(d.retention_delete_started_at, d.ingested_at)
                         else d.ingested_at
                       end as state_since
                  from documents d
                  left join document_redaction_operations r
                    on r.tenant_id=d.tenant_id and r.document_id=d.document_id
                 where d.tenant_id=:tenantId
                   and (
                     (d.status='PROCESSING' and d.ingested_at<=:processingCutoff)
                     or (d.status='REDACTION_PROCESSING' and coalesce(r.created_at,d.ingested_at)<=:transitionCutoff)
                     or (d.status='RETENTION_DELETING' and coalesce(d.retention_delete_started_at,d.ingested_at)<=:transitionCutoff)
                   )
                 order by state_since, d.document_id
                 limit :limit
                """;
        MapSqlParameterSource p = stuckParams(tenantId, processingCutoff, transitionCutoff).addValue("limit", limit);
        return jdbc.query(sql, p, (rs, row) -> new StuckDocument(
                rs.getObject("document_id", UUID.class), rs.getObject("submission_id", UUID.class),
                rs.getObject("source_id", UUID.class), rs.getString("status"),
                rs.getTimestamp("state_since").toInstant(), rs.getString("processing_error_code")));
    }

    @Override
    public long countAttentionSubmissions(UUID tenantId, Instant now) {
        Long value = jdbc.queryForObject("""
                select count(*)
                  from submissions s
                  join ingestion_sources src on src.tenant_id=s.tenant_id and src.source_id=s.source_id
                  join tenants t on t.tenant_id=s.tenant_id
                 where s.tenant_id=:tenantId
                   and (
                     s.status='INCOMPLETE'
                     or (
                       s.status in ('OPEN','RECEIVING')
                       and s.last_activity_at
                             + (least(t.incomplete_attention_hours,
                                      coalesce(src.incomplete_attention_hours, t.incomplete_attention_hours))
                                * interval '1 hour')
                           <= cast(:now as timestamptz)
                       and (select count(*) from documents d
                             where d.tenant_id=s.tenant_id and d.submission_id=s.submission_id) < s.expected_document_count
                     )
                   )
                """, attentionParams(tenantId, now), Long.class);
        return value == null ? 0L : value;
    }

    @Override
    public List<AttentionSubmission> findAttentionSubmissions(UUID tenantId, Instant now, int limit) {
        return jdbc.query("""
                select s.submission_id, s.source_id, s.source_submission_id, s.status,
                       s.expected_document_count, s.last_activity_at,
                       least(t.incomplete_attention_hours,
                             coalesce(src.incomplete_attention_hours, t.incomplete_attention_hours)) as attention_hours,
                       (select count(*) from documents d
                         where d.tenant_id=s.tenant_id and d.submission_id=s.submission_id) as received_count
                  from submissions s
                  join ingestion_sources src on src.tenant_id=s.tenant_id and src.source_id=s.source_id
                  join tenants t on t.tenant_id=s.tenant_id
                 where s.tenant_id=:tenantId
                   and (
                     s.status='INCOMPLETE'
                     or (
                       s.status in ('OPEN','RECEIVING')
                       and s.last_activity_at
                             + (least(t.incomplete_attention_hours,
                                      coalesce(src.incomplete_attention_hours, t.incomplete_attention_hours))
                                * interval '1 hour')
                           <= cast(:now as timestamptz)
                       and (select count(*) from documents d
                             where d.tenant_id=s.tenant_id and d.submission_id=s.submission_id) < s.expected_document_count
                     )
                   )
                 order by s.last_activity_at, s.submission_id
                 limit :limit
                """, attentionParams(tenantId, now).addValue("limit", limit),
                (rs, row) -> new AttentionSubmission(
                        rs.getObject("submission_id", UUID.class), rs.getObject("source_id", UUID.class),
                        rs.getString("source_submission_id"), rs.getString("status"), rs.getInt("expected_document_count"),
                        rs.getLong("received_count"), rs.getTimestamp("last_activity_at").toInstant(), rs.getInt("attention_hours")));
    }

    private long count(String sql, UUID tenantId) {
        Long value = jdbc.queryForObject(sql, new MapSqlParameterSource("tenantId", tenantId), Long.class);
        return value == null ? 0L : value;
    }

    private static MapSqlParameterSource params(UUID tenantId, int limit) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("limit", limit);
    }

    private static MapSqlParameterSource stuckParams(UUID tenantId, Instant processingCutoff, Instant transitionCutoff) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("processingCutoff", Timestamp.from(processingCutoff))
                .addValue("transitionCutoff", Timestamp.from(transitionCutoff));
    }

    private static MapSqlParameterSource attentionParams(UUID tenantId, Instant now) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("now", Timestamp.from(now));
    }

    private static void exact(StringBuilder sql, MapSqlParameterSource p, String column, String value) {
        exact(sql, p, column, column, value);
    }

    private static void exact(StringBuilder sql, MapSqlParameterSource p, String column, String parameter, String value) {
        if (value != null) {
            sql.append(" and ").append(column).append("=:").append(parameter);
            p.addValue(parameter, value);
        }
    }
}
