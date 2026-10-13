package com.sdv.platform.persistence.source;

import com.sdv.platform.application.port.SourceOperationalQueryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.UUID;

@Repository
public class JdbcSourceOperationalQueryRepository implements SourceOperationalQueryPort {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSourceOperationalQueryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SourceOperationalSummary summarize(UUID tenantId, UUID sourceId) {
        return jdbc.queryForObject("""
                select
                    (select count(*) from submissions s where s.tenant_id=:tenantId and s.source_id=:sourceId) as submission_count,
                    (select count(*) from documents d where d.tenant_id=:tenantId and d.source_id=:sourceId) as document_count,
                    (select max(s.last_activity_at) from submissions s where s.tenant_id=:tenantId and s.source_id=:sourceId) as last_activity_at,
                    (select max(d.available_at) from documents d where d.tenant_id=:tenantId and d.source_id=:sourceId and d.available_at is not null) as last_available_at,
                    (select max(d.ingested_at) from documents d where d.tenant_id=:tenantId and d.source_id=:sourceId and d.status='PROCESSING_FAILED') as last_failure_at
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("sourceId", sourceId),
                (rs, rowNum) -> new SourceOperationalSummary(
                        rs.getLong("submission_count"),
                        rs.getLong("document_count"),
                        toInstant(rs.getTimestamp("last_activity_at")),
                        toInstant(rs.getTimestamp("last_available_at")),
                        toInstant(rs.getTimestamp("last_failure_at"))));
    }

    private static java.time.Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
