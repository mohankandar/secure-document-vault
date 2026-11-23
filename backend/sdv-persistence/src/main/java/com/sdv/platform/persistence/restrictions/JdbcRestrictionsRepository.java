package com.sdv.platform.persistence.restrictions;

import com.sdv.platform.persistence.business.PrimaryIdentifierSql;
import com.sdv.platform.application.port.RestrictionsRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 0055: the Restrictions page's reads. Selected: sending system IDs, record keys (read for their primary
 * identifier only), levels, times, who changed a level, counts. Never a title, file name, content, reason or document
 * ID. Pages use idx_record_levels_restricted and idx_level_changes_recent (changeset 042); the counts use the indexes
 * already there (holds in place, pending requests, quarantine incident counts kept since task 0052).
 */
@Repository
public class JdbcRestrictionsRepository implements RestrictionsRepository {
    private static final String RESTRICTED = "sensitivity_level in ('CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL')";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRestrictionsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long countRestrictedRecords(UUID tenantId) {
        Long n = jdbc.queryForObject("select count(*) from record_sensitivity_levels where tenant_id=:t and " + RESTRICTED,
                new MapSqlParameterSource("t", tenantId), Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<RecordLevel> findRestrictedRecords(UUID tenantId, RecordLevel after, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", tenantId).addValue("limit", limit);
        String from = "";
        if (after != null) {
            from = " and (set_at, record_level_id) < (:at, :id)";
            p.addValue("at", Timestamp.from(after.setAt())).addValue("id", after.levelId());
        }
        return jdbc.query("select record_level_id, source_id, record_key, sensitivity_level, set_at from record_sensitivity_levels"
                        + " where tenant_id=:t and " + RESTRICTED + from + " order by set_at desc, record_level_id desc limit :limit", p,
                (rs, row) -> new RecordLevel(rs.getObject("record_level_id", UUID.class), rs.getObject("source_id", UUID.class),
                        rs.getString("record_key"), rs.getString("sensitivity_level"), instant(rs, "set_at")));
    }

    @Override
    public List<LevelChange> findLevelChanges(UUID tenantId, Instant since, LevelChange after, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", tenantId).addValue("since", Timestamp.from(since)).addValue("limit", limit);
        String from = "";
        if (after != null) {
            from = " and (c.changed_at, c.change_id) < (:at, :id)";
            p.addValue("at", Timestamp.from(after.changedAt())).addValue("id", after.changeId());
        }
        // A document's change names its record through the document's submission (never the document ID).
        return jdbc.query("""
                select c.change_id, coalesce(nullif(btrim(c.changed_by_display_name), ''), c.changed_by) as who, c.changed_at,
                       c.scope, c.from_level, c.to_level, c.source_id, c.record_key, d.submission_id
                  from sensitivity_level_changes c
                  left join documents d on d.tenant_id=c.tenant_id and d.document_id=c.document_id
                 where c.tenant_id=:t and c.changed_at >= :since""" + from + """

                 order by c.changed_at desc, c.change_id desc limit :limit
                """, p, (rs, row) -> new LevelChange(rs.getObject("change_id", UUID.class), rs.getString("who"),
                instant(rs, "changed_at"), rs.getString("scope"), rs.getString("from_level"), rs.getString("to_level"),
                rs.getObject("source_id", UUID.class), rs.getString("record_key"), rs.getObject("submission_id", UUID.class)));
    }

    @Override
    public long countLevelChanges(UUID tenantId, Instant since) {
        Long n = jdbc.queryForObject("select count(*) from sensitivity_level_changes where tenant_id=:t and changed_at >= :since",
                new MapSqlParameterSource("t", tenantId).addValue("since", Timestamp.from(since)), Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public Map<UUID, Identifier> findSubmissionIdentifiers(UUID tenantId, Collection<UUID> submissionIds) {
        Map<UUID, Identifier> out = new LinkedHashMap<>();
        if (submissionIds == null || submissionIds.isEmpty()) return out;
        jdbc.query("select s.submission_id, pid.stored_value, pid.display_label from submissions s "
                        + PrimaryIdentifierSql.join("s.tenant_id", "s.submission_id") + """
                 where s.tenant_id=:t and s.submission_id in (:ids) and pid.stored_value is not null
                """, new MapSqlParameterSource("t", tenantId).addValue("ids", List.copyOf(submissionIds)),
                rs -> { out.put(rs.getObject("submission_id", UUID.class), new Identifier(rs.getString("display_label"), rs.getString("stored_value"))); });
        return out;
    }

    @Override
    public Map<UUID, Map<String, String>> findIdentifierLabels(UUID tenantId, Collection<UUID> sourceIds) {
        Map<UUID, Map<String, String>> out = new LinkedHashMap<>();
        if (sourceIds == null || sourceIds.isEmpty()) return out;
        jdbc.query("""
                select source_id, field_key, display_label from metadata_field_definitions
                 where tenant_id=:t and source_id in (:ids) and business_identifier
                 order by source_id, field_key, version desc
                """, new MapSqlParameterSource("t", tenantId).addValue("ids", List.copyOf(sourceIds)), rs -> {
            out.computeIfAbsent(rs.getObject("source_id", UUID.class), k -> new LinkedHashMap<>())
                    .putIfAbsent(rs.getString("field_key"), rs.getString("display_label"));
        });
        return out;
    }

    @Override
    public Map<UUID, List<String>> findDisplayOrders(UUID tenantId, Collection<UUID> sourceIds) {
        Map<UUID, List<String>> out = new LinkedHashMap<>();
        if (sourceIds == null || sourceIds.isEmpty()) return out;
        jdbc.query("""
                select source_id, field_key from metadata_field_display_order
                 where tenant_id=:t and source_id in (:ids)
                 order by source_id, display_position
                """, new MapSqlParameterSource("t", tenantId).addValue("ids", List.copyOf(sourceIds)), rs -> {
            out.computeIfAbsent(rs.getObject("source_id", UUID.class), k -> new ArrayList<>()).add(rs.getString("field_key"));
        });
        return out;
    }

    @Override
    public Waiting findWaitingLowerings(UUID tenantId) {
        return jdbc.queryForObject("""
                select count(*) as n, min(requested_at) as oldest from access_requests
                 where tenant_id=:t and status='PENDING' and kind in ('LOWER_LEVEL', 'LOWER_RECORD_LEVEL')
                """, new MapSqlParameterSource("t", tenantId), (rs, row) -> new Waiting(rs.getLong("n"), instant(rs, "oldest")));
    }

    @Override
    public Holds countHolds(UUID tenantId) {
        return jdbc.queryForObject("""
                select count(*) as holds, count(distinct document_id) as documents from legal_holds
                 where tenant_id=:t and released_at is null
                """, new MapSqlParameterSource("t", tenantId), (rs, row) -> new Holds(rs.getLong("holds"), rs.getLong("documents")));
    }

    @Override
    public Quarantine countQuarantine(UUID tenantId) {
        // The incidents' own counts (task 0052): never the documents themselves.
        return jdbc.queryForObject("""
                select count(*) as incidents,
                       coalesce(sum(case when filter_received_from is not null and prepared_at is null
                                         then greatest(expected_count, document_count - released_count)
                                         else document_count - released_count end), 0) as documents,
                       count(*) filter (where filter_received_from is not null and prepared_at is null) as preparing
                  from quarantine_incidents where tenant_id=:t and status='OPEN'
                """, new MapSqlParameterSource("t", tenantId),
                (rs, row) -> new Quarantine(rs.getLong("incidents"), rs.getLong("documents"), rs.getLong("preparing")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
