package com.sdv.platform.persistence.reports;

import com.sdv.platform.application.port.StorageReportRepository;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.persistence.quarantine.JdbcQuarantineRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0053: the Storage and retention report's reads. Only sending system IDs, levels, retention lengths and counts
 * are selected: never content, file names, titles or record identifiers. Every query is limited to one tenant (but
 * {@link #findActiveTenants}). These reads go through the documents, so they run in the background job only; a screen
 * reads the kept counts ({@link #findKept}).
 * <p>
 * Levels are effective levels, worked out as the Sensitivity report does
 * ({@link JdbcSensitivityReportRepository#effectiveLevels}). Past retention and kept: the retention cleanup's own rules
 * (a legal hold in place; a quarantine row in force, or an open filter incident's block:
 * {@link JdbcQuarantineRepository#filterBlocked}).
 */
@Repository
public class JdbcStorageReportRepository implements StorageReportRepository {
    private static final String STORED = JdbcSensitivityReportRepository.STORED;
    /** The statuses the retention cleanup deletes from (JdbcRetentionRepository's due documents). */
    static final String DUE_STATUSES = "('RECEIVED', 'PROCESSING', 'AVAILABLE', 'PROCESSING_FAILED', 'RETENTION_DELETING')";
    private static final SensitivityLevel[] LEVELS = SensitivityLevel.values();

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcStorageReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Stored> countStored(UUID tenantId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenantId", tenantId);
        JdbcSensitivityReportRepository.LevelSql levels = JdbcSensitivityReportRepository.effectiveLevels(jdbc, tenantId, null, p);
        return jdbc.query(levels.with() + """
                select d.source_id,\s""" + JdbcSensitivityReportRepository.LEVEL_OF_D + """
                 as lvl, count(*) as n, coalesce(sum(d.page_count), 0) as pages,
                       coalesce(sum(d.content_size_bytes), 0) as bytes
                  from documents d
                  left join submission_levels sl on sl.submission_id=d.submission_id
                 where d.tenant_id=:tenantId and d.status in\s""" + STORED + """

                 group by 1, 2
                 order by 1, 2
                """, p, (rs, row) -> new Stored(rs.getObject("source_id", UUID.class), LEVELS[rs.getInt("lvl")],
                rs.getLong("n"), rs.getLong("pages"), rs.getLong("bytes")));
    }

    @Override
    public List<Month> countReceived(UUID tenantId, Instant since, String timeZone) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("since", Timestamp.from(since)).addValue("zone", timeZone);
        JdbcSensitivityReportRepository.LevelSql levels = JdbcSensitivityReportRepository.effectiveLevels(jdbc, tenantId, null, p);
        // Every document received in the month and kept at the time (a document whose processing failed was never
        // stored), whatever became of it since; at its level now.
        return jdbc.query(levels.with() + """
                select to_char(d.ingested_at at time zone :zone, 'YYYY-MM') as month, d.source_id,\s""" + JdbcSensitivityReportRepository.LEVEL_OF_D + """
                 as lvl, count(*) as n, coalesce(sum(d.page_count), 0) as pages,
                       coalesce(sum(d.content_size_bytes), 0) as bytes
                  from documents d
                  left join submission_levels sl on sl.submission_id=d.submission_id
                 where d.tenant_id=:tenantId and d.ingested_at >= :since and d.status <> 'PROCESSING_FAILED'
                 group by 1, 2, 3
                 order by 1, 2, 3
                """, p, (rs, row) -> new Month(rs.getString("month"), rs.getObject("source_id", UUID.class),
                LEVELS[rs.getInt("lvl")], rs.getLong("n"), rs.getLong("pages"), rs.getLong("bytes")));
    }

    @Override
    public List<Due> countDue(UUID tenantId, Instant now) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("now", Timestamp.from(now))
                .addValue("d30", Timestamp.from(now.plus(Duration.ofDays(30))))
                .addValue("d60", Timestamp.from(now.plus(Duration.ofDays(60))))
                .addValue("d90", Timestamp.from(now.plus(Duration.ofDays(90))));
        // Stored documents by the retention they were given; those whose retention ends after now and within 90 days.
        return jdbc.query("""
                select d.retention_days_snapshot as days, count(*) as stored,
                       count(*) filter (where d.expires_at > :now and d.expires_at <= :d30) as within30,
                       count(*) filter (where d.expires_at > :now and d.expires_at <= :d60) as within60,
                       count(*) filter (where d.expires_at > :now and d.expires_at <= :d90) as within90
                  from documents d
                 where d.tenant_id=:tenantId and d.status in\s""" + STORED + """

                 group by 1
                 order by 1 nulls last
                """, p, (rs, row) -> {
            int days = rs.getInt("days");
            Integer retention = rs.wasNull() ? null : days;
            return new Due(retention, rs.getLong("stored"), rs.getLong("within30"), rs.getLong("within60"),
                    rs.getLong("within90"));
        });
    }

    @Override
    public HeldBack countHeldBack(UUID tenantId, Instant now) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("now", Timestamp.from(now));
        // Past retention as the cleanup sees it (idx_documents_expires covers it), then why each one is kept.
        return jdbc.queryForObject("""
                select count(*) filter (where held and not quarantined) as hold,
                       count(*) filter (where quarantined and not held) as quarantine,
                       count(*) filter (where held and quarantined) as both
                  from (select exists (select 1 from legal_holds h
                                        where h.tenant_id=d.tenant_id and h.document_id=d.document_id and h.released_at is null) as held,
                               (exists (select 1 from quarantined_documents q
                                         where q.tenant_id=d.tenant_id and q.document_id=d.document_id and q.released_at is null)
                                or\s""" + JdbcQuarantineRepository.filterBlocked("d") + """
                ) as quarantined
                          from documents d
                         where d.tenant_id=:tenantId and d.expires_at is not null and d.expires_at <= :now
                           and d.status in\s""" + DUE_STATUSES + """
                ) x
                """, p, (rs, row) -> new HeldBack(rs.getLong("hold"), rs.getLong("quarantine"), rs.getLong("both")));
    }

    @Override
    public Map<Integer, String> findRuleNames(UUID tenantId) {
        Map<Integer, String> names = new LinkedHashMap<>();
        jdbc.query("""
                select retention_days, policy_name from retention_policy_versions
                 where tenant_id=:tenantId order by effective_from desc, version desc
                """, new MapSqlParameterSource("tenantId", tenantId), rs -> {
            int days = rs.getInt("retention_days");
            Integer key = rs.wasNull() ? null : days;
            names.putIfAbsent(key, rs.getString("policy_name"));
        });
        return names;
    }

    @Override
    public Optional<Kept> findKept(UUID tenantId) {
        return jdbc.query("select counted_at, took_ms, counts::text as counts from report_storage_counts where tenant_id=:tenantId",
                new MapSqlParameterSource("tenantId", tenantId),
                (rs, row) -> new Kept(rs.getTimestamp("counted_at").toInstant(), rs.getLong("took_ms"), rs.getString("counts")))
                .stream().findFirst();
    }

    @Override
    public void keep(UUID tenantId, Instant countedAt, long tookMs, String countsJson) {
        jdbc.update("""
                insert into report_storage_counts(tenant_id, counted_at, took_ms, counts)
                values (:tenantId, :at, :took, cast(:counts as jsonb))
                on conflict (tenant_id) do update set counted_at=excluded.counted_at, took_ms=excluded.took_ms, counts=excluded.counts
                 where report_storage_counts.counted_at <= excluded.counted_at
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("at", Timestamp.from(countedAt))
                .addValue("took", tookMs).addValue("counts", countsJson));
    }

    @Override
    public List<UUID> findActiveTenants() {
        return jdbc.query("select tenant_id from tenants where status='ACTIVE' order by tenant_id",
                new MapSqlParameterSource(), (rs, row) -> rs.getObject(1, UUID.class));
    }
}
