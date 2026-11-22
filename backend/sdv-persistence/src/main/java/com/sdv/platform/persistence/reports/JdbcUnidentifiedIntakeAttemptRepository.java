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
import java.util.Objects;
import java.util.UUID;

/**
 * Task 0039: unidentified_intake_attempts (changeset 033). A tenant reads its own attempts and those with no tenant.
 * The tenant is written only when it exists (a verified sign-in can name a tenant Vero doesn't have).
 * <p>
 * Task 0039 part 2 (changeset 034): one row per tenant, caller address, method, address, status and code per UTC
 * minute. A request is added to its minute's row (attempt_count + 1, last_seen_at); only a request with a new key
 * writes a row, and only then are rows older than 30 days removed. Past {@link #MAX_ROWS_PER_MINUTE} rows in a minute,
 * new keys get the caller address {@link #MANY_CALLERS}. The unique index uq_unidentified_intake_attempts_minute
 * keeps it one row when two requests race ("insert ... on conflict ... do update").
 */
@Repository
public class JdbcUnidentifiedIntakeAttemptRepository implements UnidentifiedIntakeAttemptRepository {
    /** The tenant as stored: the one named, when Vero has it; null otherwise. */
    private static final String STORED_TENANT =
            "(select t.tenant_id from tenants t where t.tenant_id=cast(:tenantId as uuid))";
    private static final String SAME_KEY = """
             tenant_id is not distinct from %s
             and caller_address is not distinct from cast(:caller as varchar)
             and method=:method and address=:address and code=:code and status=:status
             and occurred_at >= :minute and occurred_at < :minuteEnd
            """.formatted(STORED_TENANT);

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUnidentifiedIntakeAttemptRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void record(Attempt a) {
        Instant minute = a.occurredAt().truncatedTo(ChronoUnit.MINUTES);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", a.attemptId())
                .addValue("tenantId", a.tenantId())
                .addValue("at", Timestamp.from(a.occurredAt()))
                .addValue("caller", a.callerAddress())
                .addValue("method", a.method())
                .addValue("address", a.address())
                .addValue("status", a.status())
                .addValue("code", a.code())
                .addValue("reference", a.reference())
                .addValue("minute", Timestamp.from(minute))
                .addValue("minuteEnd", Timestamp.from(minute.plus(1, ChronoUnit.MINUTES)));
        if (addToRow(p)) return;
        Long rows = jdbc.queryForObject("""
                select count(*) from unidentified_intake_attempts where occurred_at >= :minute and occurred_at < :minuteEnd
                """, p, Long.class);
        if (rows != null && rows >= MAX_ROWS_PER_MINUTE && !Objects.equals(a.callerAddress(), MANY_CALLERS)) {
            p.addValue("caller", MANY_CALLERS);
            if (addToRow(p)) return;
        }
        jdbc.update("""
                insert into unidentified_intake_attempts(attempt_id, tenant_id, occurred_at, last_seen_at, attempt_count,
                                                         caller_address, method, address, status, code, reference)
                values (:id, %s, :at, :at, 1, :caller, :method, :address, :status, :code, :reference)
                on conflict (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), coalesce(caller_address, ''),
                             method, address, code, status, date_trunc('minute', occurred_at at time zone 'UTC'))
                do update set attempt_count = unidentified_intake_attempts.attempt_count + 1,
                              last_seen_at = greatest(unidentified_intake_attempts.last_seen_at, excluded.last_seen_at)
                """.formatted(STORED_TENANT), p);
        jdbc.update("delete from unidentified_intake_attempts where occurred_at < :cutoff",
                new MapSqlParameterSource("cutoff", Timestamp.from(a.occurredAt().minus(KEEP_DAYS, ChronoUnit.DAYS))));
    }

    /** Adds the request to its minute's row; false when there is none yet. */
    private boolean addToRow(MapSqlParameterSource p) {
        return jdbc.update("""
                update unidentified_intake_attempts
                   set attempt_count = attempt_count + 1, last_seen_at = greatest(last_seen_at, :at)
                 where""" + SAME_KEY, p) > 0;
    }

    @Override
    public long count(UUID tenantId, Instant since) {
        Long n = jdbc.queryForObject("""
                select coalesce(sum(attempt_count), 0) from unidentified_intake_attempts
                 where (tenant_id=:tenantId or tenant_id is null) and occurred_at >= :firstSince and last_seen_at >= :since
                """, params(tenantId, since), Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<Attempt> findRecent(UUID tenantId, Instant since, int limit) {
        return jdbc.query("""
                select attempt_id, tenant_id, occurred_at, last_seen_at, attempt_count, caller_address, method, address,
                       status, code, reference
                  from unidentified_intake_attempts
                 where (tenant_id=:tenantId or tenant_id is null) and occurred_at >= :firstSince and last_seen_at >= :since
                 order by last_seen_at desc, attempt_id
                 limit :limit
                """, params(tenantId, since).addValue("limit", limit), (rs, row) -> new Attempt(
                rs.getObject("attempt_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant(),
                rs.getInt("attempt_count"), rs.getString("caller_address"), rs.getString("method"),
                rs.getString("address"), rs.getInt("status"), rs.getString("code"), rs.getString("reference")));
    }

    /** A row's first request is at most a minute before its last, so occurred_at narrows the search (index). */
    private static MapSqlParameterSource params(UUID tenantId, Instant since) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("since", Timestamp.from(since))
                .addValue("firstSince", Timestamp.from(since.minus(1, ChronoUnit.MINUTES)));
    }
}
