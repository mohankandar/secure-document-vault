package com.sdv.platform.persistence.reports;

import com.sdv.platform.application.port.StewardNoticeRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0033 (0.1): steward_notices (changeset 032). Every query is limited to one tenant. A person's read mark is their
 * own key in read_by; the first time stays when they mark a notice read again.
 */
@Repository
public class JdbcStewardNoticeRepository implements StewardNoticeRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcStewardNoticeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StoredNotice> findUnread(UUID tenantId, String subject, int limit) {
        return jdbc.query("""
                select n.notice_id, n.kind, n.created_at, n.source_id, src.display_name, n.record_identifier,
                       n.record_identifier_label
                  from steward_notices n
                  left join ingestion_sources src on src.tenant_id=n.tenant_id and src.source_id=n.source_id
                 where n.tenant_id=:tenantId and (n.read_by ->> cast(:subject as text)) is null
                 order by n.created_at desc, n.notice_id
                 limit :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("subject", subject)
                .addValue("limit", limit), (rs, row) -> new StoredNotice(
                rs.getObject("notice_id", UUID.class), rs.getString("kind"), rs.getTimestamp("created_at").toInstant(),
                rs.getObject("source_id", UUID.class), rs.getString("display_name"), rs.getString("record_identifier"),
                rs.getString("record_identifier_label")));
    }

    @Override
    public int countUnread(UUID tenantId, String subject) {
        Integer n = jdbc.queryForObject("""
                select count(*) from steward_notices where tenant_id=:tenantId and (read_by ->> cast(:subject as text)) is null
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("subject", subject), Integer.class);
        return n == null ? 0 : n;
    }

    @Override
    public Set<UUID> findExisting(UUID tenantId, Collection<UUID> noticeIds) {
        if (noticeIds.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.queryForList("""
                select notice_id from steward_notices where tenant_id=:tenantId and notice_id in (:ids)
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("ids", List.copyOf(noticeIds)),
                UUID.class));
    }

    @Override
    public int markRead(UUID tenantId, Collection<UUID> noticeIds, String subject, Instant at) {
        if (noticeIds.isEmpty()) return 0;
        // One statement per call: concurrent marks by two stewards each add their own key (jsonb || is applied to the
        // row as it is when the update locks it).
        return jdbc.update("""
                update steward_notices
                   set read_by = read_by || jsonb_build_object(cast(:subject as text), cast(:at as text))
                 where tenant_id=:tenantId and notice_id in (:ids) and (read_by ->> cast(:subject as text)) is null
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("ids", List.copyOf(noticeIds))
                .addValue("subject", subject)
                .addValue("at", at.toString()));
    }

}
