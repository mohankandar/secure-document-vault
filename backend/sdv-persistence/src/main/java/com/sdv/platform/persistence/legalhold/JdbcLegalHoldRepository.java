package com.sdv.platform.persistence.legalhold;

import com.sdv.platform.persistence.business.PrimaryIdentifierSql;
import com.sdv.platform.persistence.fields.CaseFieldSql;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.application.port.LegalHoldRepository;
import com.sdv.platform.domain.document.SensitivityLevel;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Task 0027: legal_holds (migration 026). Every statement is limited to one tenant. */
@Repository
public class JdbcLegalHoldRepository implements LegalHoldRepository {
    /** The record's primary identifier of document d (the one rule, task 0056), and its hold in place. */
    static final String PRIMARY_IDENTIFIER = PrimaryIdentifierSql.join("d.tenant_id", "d.submission_id") + """
            left join legal_holds ah
              on ah.tenant_id=d.tenant_id and ah.document_id=d.document_id and ah.released_at is null
            """;
    static final String DOCUMENT_COLUMNS = """
            d.document_id, d.source_id, d.submission_id, d.status, d.sensitivity_level, d.ingested_at, d.page_count,
            pid.stored_value as primary_identifier, pid.display_label as primary_identifier_label,
            ah.hold_id as active_hold_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLegalHoldRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DocumentFacts> lockDocument(UUID tenantId, UUID documentId) {
        // The lock first, on the documents row alone (for update can't lock the outer side of a join), then the facts
        // in a new statement, which sees every hold committed before the lock was granted.
        List<UUID> locked = jdbc.query("""
                select document_id from documents
                 where tenant_id=:tenantId and document_id=:documentId
                 for update
                """, document(tenantId, documentId), (rs, row) -> rs.getObject("document_id", UUID.class));
        if (locked.isEmpty()) return Optional.empty();
        return jdbc.query("select " + DOCUMENT_COLUMNS + " from documents d " + PRIMARY_IDENTIFIER
                        + " where d.tenant_id=:tenantId and d.document_id=:documentId",
                document(tenantId, documentId), (rs, row) -> facts(rs)).stream().findFirst();
    }

    @Override
    public Optional<LegalHold> lockHold(UUID tenantId, UUID holdId) {
        return jdbc.query("""
                select hold_id, tenant_id, document_id, reason, placed_by, placed_by_display_name, placed_at,
                       released_by, released_by_display_name, released_at
                  from legal_holds
                 where tenant_id=:tenantId and hold_id=:holdId
                 for update
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("holdId", holdId),
                (rs, row) -> hold(rs, "")).stream().findFirst();
    }

    @Override
    public void insert(LegalHold hold) {
        jdbc.update("""
                insert into legal_holds(hold_id, tenant_id, document_id, reason, placed_by, placed_by_display_name, placed_at)
                values (:holdId, :tenantId, :documentId, :reason, :placedBy, :placedByName, :placedAt)
                """, new MapSqlParameterSource()
                .addValue("holdId", hold.holdId())
                .addValue("tenantId", hold.tenantId())
                .addValue("documentId", hold.documentId())
                .addValue("reason", hold.reason())
                .addValue("placedBy", hold.placedBy())
                .addValue("placedByName", hold.placedByName())
                .addValue("placedAt", Timestamp.from(hold.placedAt())));
    }

    @Override
    public boolean release(UUID tenantId, UUID holdId, String releasedBy, String releasedByName, Instant releasedAt) {
        int updated = jdbc.update("""
                update legal_holds
                   set released_by=:releasedBy, released_by_display_name=:releasedByName, released_at=:releasedAt
                 where tenant_id=:tenantId and hold_id=:holdId and released_at is null
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("holdId", holdId)
                .addValue("releasedBy", releasedBy)
                .addValue("releasedByName", releasedByName)
                .addValue("releasedAt", Timestamp.from(releasedAt)));
        if (updated > 1) throw new InternalCheckFailed("The legal hold release changed more than one active hold.");
        return updated == 1;
    }

    @Override
    public List<DocumentFacts> findByPrimaryIdentifier(UUID tenantId, String value, int limit) {
        // First the submissions with an identifier value equal to the input (the lookup index covers the normalized
        // value), then only those where that value is the primary identifier.
        return jdbc.query("select " + DOCUMENT_COLUMNS + " from documents d " + PRIMARY_IDENTIFIER + """
                 where d.tenant_id=:tenantId
                   and d.submission_id in (""" + CaseFieldSql.submissionsByIdentifier(":value") + """
)
                   and pid.search_value=:value
                   and d.status not in ('EXPIRED_DELETED', 'RETENTION_DELETING')
                 order by d.ingested_at desc, d.document_id
                 limit :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("value", CaseFieldSql.searchForm(value))
                .addValue("limit", limit), (rs, row) -> facts(rs));
    }

    @Override
    public List<HoldRow> findActive(UUID tenantId, int limit) {
        return jdbc.query("select h.hold_id as h_hold_id, h.tenant_id as h_tenant_id, h.document_id as h_document_id, "
                        + "h.reason as h_reason, h.placed_by as h_placed_by, h.placed_by_display_name as h_placed_by_display_name, "
                        + "h.placed_at as h_placed_at, h.released_by_display_name as h_released_by_display_name, "
                        + "h.released_by as h_released_by, h.released_at as h_released_at, " + DOCUMENT_COLUMNS
                        + " from legal_holds h join documents d on d.tenant_id=h.tenant_id and d.document_id=h.document_id "
                        + PRIMARY_IDENTIFIER + """
                 where h.tenant_id=:tenantId and h.released_at is null
                 order by h.placed_at desc, h.hold_id
                 limit :limit
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("limit", limit),
                (rs, row) -> new HoldRow(hold(rs, "h_"), facts(rs)));
    }

    private static DocumentFacts facts(ResultSet rs) throws SQLException {
        String level = rs.getString("sensitivity_level");
        Timestamp received = rs.getTimestamp("ingested_at");
        return new DocumentFacts(
                rs.getObject("document_id", UUID.class),
                rs.getObject("source_id", UUID.class),
                rs.getObject("submission_id", UUID.class),
                rs.getString("status"),
                level == null ? null : SensitivityLevel.valueOf(level),
                received == null ? null : received.toInstant(),
                rs.getObject("page_count", Integer.class),
                rs.getString("primary_identifier"),
                rs.getString("primary_identifier_label"),
                rs.getObject("active_hold_id", UUID.class));
    }

    private static LegalHold hold(ResultSet rs, String prefix) throws SQLException {
        Timestamp released = rs.getTimestamp(prefix + "released_at");
        return new LegalHold(
                rs.getObject(prefix + "hold_id", UUID.class),
                rs.getObject(prefix + "tenant_id", UUID.class),
                rs.getObject(prefix + "document_id", UUID.class),
                rs.getString(prefix + "reason"),
                rs.getString(prefix + "placed_by"),
                rs.getString(prefix + "placed_by_display_name"),
                rs.getTimestamp(prefix + "placed_at").toInstant(),
                rs.getString(prefix + "released_by"),
                rs.getString(prefix + "released_by_display_name"),
                released == null ? null : released.toInstant());
    }

    private static MapSqlParameterSource document(UUID tenantId, UUID documentId) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("documentId", documentId);
    }
}
