package com.sdv.platform.persistence.reports;

import com.sdv.platform.application.port.UnidentifiedIntakeAttemptRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Task 0039: unidentified_intake_attempts (changeset 033). A tenant reads its own attempts and those with no tenant.
 * The tenant is written only when it exists (a verified sign-in can name a tenant Vero doesn't have).
 */
@Repository
public class JdbcUnidentifiedIntakeAttemptRepository implements UnidentifiedIntakeAttemptRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUnidentifiedIntakeAttemptRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void record(Attempt a) {
        jdbc.update("""
                insert into unidentified_intake_attempts(attempt_id, tenant_id, occurred_at, caller_address, method,
                                                         address, status, code, reference)
                values (:id, (select t.tenant_id from tenants t where t.tenant_id=cast(:tenantId as uuid)), :at, :caller, :method,
                        :address, :status, :code, :reference)
                """, new MapSqlParameterSource()
                .addValue("id", a.attemptId())
                .addValue("tenantId", a.tenantId())
                .addValue("at", Timestamp.from(a.occurredAt()))
                .addValue("caller", a.callerAddress())
                .addValue("method", a.method())
                .addValue("address", a.address())
                .addValue("status", a.status())
                .addValue("code", a.code())
                .addValue("reference", a.reference()));
        jdbc.update("delete from unidentified_intake_attempts where occurred_at < :cutoff",
                new MapSqlParameterSource("cutoff", Timestamp.from(a.occurredAt().minus(KEEP_DAYS, ChronoUnit.DAYS))));
    }

    @Override
    public long count(UUID tenantId, Instant since) {
        Long n = jdbc.queryForObject("""
                select count(*) from unidentified_intake_attempts
                 where (tenant_id=:tenantId or tenant_id is null) and occurred_at >= :since
                """, params(tenantId, since), Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<Attempt> findRecent(UUID tenantId, Instant since, int limit) {
        return jdbc.query("""
                select attempt_id, tenant_id, occurred_at, caller_address, method, address, status, code, reference
                  from unidentified_intake_attempts
                 where (tenant_id=:tenantId or tenant_id is null) and occurred_at >= :since
                 order by occurred_at desc, attempt_id
                 limit :limit
                """, params(tenantId, since).addValue("limit", limit), (rs, row) -> new Attempt(
                rs.getObject("attempt_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("caller_address"), rs.getString("method"),
                rs.getString("address"), rs.getInt("status"), rs.getString("code"), rs.getString("reference")));
    }

    private static MapSqlParameterSource params(UUID tenantId, Instant since) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("since", Timestamp.from(since));
    }
}
