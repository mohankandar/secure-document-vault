package com.sdv.platform.persistence.retention;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.application.port.RetentionRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcRetentionRepository implements RetentionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRetentionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void updateDefaultRetentionDays(UUID tenantId, Integer defaultRetentionDays, Instant updatedAt) {
        int updated = jdbc.update("""
                update tenants
                   set default_retention_days=:days,
                       updated_at=:updatedAt
                 where tenant_id=:tenantId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("days", defaultRetentionDays)
                .addValue("updatedAt", Timestamp.from(updatedAt)));
        if (updated != 1) throw new InternalCheckFailed("Retention-policy update did not affect exactly one tenant.");
    }


    @Override
    public java.util.Optional<PolicyVersion> findCurrentPolicy(UUID tenantId) {
        return jdbc.query("""
                select policy_version_id, tenant_id, version, policy_name, description,
                       retention_days, effective_from, effective_until, created_by, created_by_display_name, created_at
                  from retention_policy_versions
                 where tenant_id=:tenantId and effective_until is null
                 order by version desc
                 limit 1
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, rowNum) -> new PolicyVersion(
                rs.getObject("policy_version_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getInt("version"),
                rs.getString("policy_name"),
                rs.getString("description"),
                rs.getObject("retention_days", Integer.class),
                rs.getTimestamp("effective_from").toInstant(),
                rs.getTimestamp("effective_until") == null ? null : rs.getTimestamp("effective_until").toInstant(),
                rs.getString("created_by"),
                rs.getString("created_by_display_name"),
                rs.getTimestamp("created_at").toInstant())).stream().findFirst();
    }

    @Override
    public List<PolicyVersion> findPolicyHistory(UUID tenantId, int limit) {
        return jdbc.query("""
                select policy_version_id, tenant_id, version, policy_name, description,
                       retention_days, effective_from, effective_until, created_by, created_by_display_name, created_at
                  from retention_policy_versions
                 where tenant_id=:tenantId
                 order by effective_from desc, version desc
                 limit :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", limit), (rs, rowNum) -> new PolicyVersion(
                rs.getObject("policy_version_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getInt("version"),
                rs.getString("policy_name"),
                rs.getString("description"),
                rs.getObject("retention_days", Integer.class),
                rs.getTimestamp("effective_from").toInstant(),
                rs.getTimestamp("effective_until") == null ? null : rs.getTimestamp("effective_until").toInstant(),
                rs.getString("created_by"),
                rs.getString("created_by_display_name"),
                rs.getTimestamp("created_at").toInstant()));
    }

    @Override
    public int nextPolicyVersion(UUID tenantId) {
        Integer next = jdbc.queryForObject("""
                select coalesce(max(version), 0) + 1
                  from retention_policy_versions
                 where tenant_id=:tenantId
                """, new MapSqlParameterSource("tenantId", tenantId), Integer.class);
        return next == null ? 1 : next;
    }

    @Override
    public void closeCurrentPolicy(UUID tenantId, Instant effectiveUntil) {
        jdbc.update("""
                update retention_policy_versions
                   set effective_until=:effectiveUntil
                 where tenant_id=:tenantId
                   and effective_until is null
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("effectiveUntil", Timestamp.from(effectiveUntil)));
    }

    @Override
    public void insertPolicyVersion(PolicyVersion policy) {
        jdbc.update("""
                insert into retention_policy_versions(
                    policy_version_id, tenant_id, version, policy_name, description,
                    retention_days, effective_from, effective_until, created_by, created_by_display_name, created_at)
                values (
                    :policyVersionId, :tenantId, :version, :policyName, :description,
                    :retentionDays, :effectiveFrom, :effectiveUntil, :createdBy, :createdByDisplayName, :createdAt)
                """, new MapSqlParameterSource()
                .addValue("policyVersionId", policy.policyVersionId())
                .addValue("tenantId", policy.tenantId())
                .addValue("version", policy.version())
                .addValue("policyName", policy.policyName())
                .addValue("description", policy.description())
                .addValue("retentionDays", policy.retentionDays())
                .addValue("effectiveFrom", Timestamp.from(policy.effectiveFrom()))
                .addValue("effectiveUntil", policy.effectiveUntil() == null ? null : Timestamp.from(policy.effectiveUntil()))
                .addValue("createdBy", policy.createdBy())
                .addValue("createdByDisplayName", policy.createdByDisplayName())
                .addValue("createdAt", Timestamp.from(policy.createdAt())));
    }

    /** Task 0027: the document has a legal hold in place (uq_legal_holds_active covers this lookup). */
    static final String ACTIVE_HOLD = """
            select 1 from legal_holds h
             where h.tenant_id=documents.tenant_id and h.document_id=documents.document_id and h.released_at is null""";

    /** Due for deletion by retention, as findDueDocuments and claimDueDocument see it, apart from holds. */
    private static final String DUE = """
                   expires_at is not null
                   and expires_at <= current_timestamp
                   and status in ('RECEIVED','PROCESSING','AVAILABLE','PROCESSING_FAILED','RETENTION_DELETING')
            """;

    @Override
    public List<DueDocument> findDueDocuments(int limit) {
        // Task 0027: a document on legal hold is never a candidate.
        return jdbc.query("""
                select tenant_id, document_id
                  from documents
                 where %s
                   and not exists (%s)
                 order by case when status='RETENTION_DELETING' then 0 else 1 end,
                          expires_at,
                          document_id
                 limit :limit
                """.formatted(DUE, ACTIVE_HOLD), new MapSqlParameterSource("limit", limit),
                (rs, rowNum) -> new DueDocument(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("document_id", UUID.class)));
    }

    @Override
    public long countHeldDueDocuments() {
        Long count = jdbc.queryForObject("""
                select count(*)
                  from documents
                 where %s
                   and exists (%s)
                """.formatted(DUE, ACTIVE_HOLD), new MapSqlParameterSource(), Long.class);
        return count == null ? 0L : count;
    }

    /**
     * Task 0027: the caller runs this in one transaction. The document row is locked first, then the claim is a new
     * statement, so it sees every legal hold committed before the lock was granted. Placing a hold takes the same lock
     * (JdbcLegalHoldRepository.lockDocument), so a hold and a claim can't pass each other: a hold placed first stops
     * the claim; a claim made first makes the hold refuse (the document is being deleted).
     */
    @Override
    public boolean claimDueDocument(UUID tenantId, UUID documentId, Instant startedAt) {
        List<UUID> locked = jdbc.query("""
                select document_id from documents
                 where tenant_id=:tenantId and document_id=:documentId
                 for update
                """, params(tenantId, documentId), (rs, rowNum) -> rs.getObject("document_id", UUID.class));
        if (locked.isEmpty()) return false;
        int updated = jdbc.update("""
                update documents
                   set status='RETENTION_DELETING',
                       retention_delete_started_at=:startedAt,
                       processing_error_code=null
                 where tenant_id=:tenantId
                   and document_id=:documentId
                   and expires_at is not null
                   and expires_at <= current_timestamp
                   and (
                        status in ('RECEIVED','PROCESSING','AVAILABLE','PROCESSING_FAILED')
                        or (
                            status='RETENTION_DELETING'
                            and (
                                retention_delete_started_at is null
                                or retention_delete_started_at <= current_timestamp - interval '10 minutes'
                            )
                        )
                   )
                   and not exists (%s)
                """.formatted(ACTIVE_HOLD), new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("documentId", documentId)
                .addValue("startedAt", Timestamp.from(startedAt)));
        return updated == 1;
    }

    @Override
    public void finalizeExpiredDocument(UUID tenantId, UUID documentId, Instant deletedAt, String deletionReason) {
        // Protected content/key references are removed, while the document tombstone itself remains.
        jdbc.update("""
                delete from document_crypto_metadata
                 where tenant_id=:tenantId and document_id=:documentId
                """, params(tenantId, documentId));
        jdbc.update("""
                delete from document_storage_refs
                 where tenant_id=:tenantId and document_id=:documentId
                """, params(tenantId, documentId));

        int updated = jdbc.update("""
                update documents
                   set status='EXPIRED_DELETED',
                       deleted_at=:deletedAt,
                       deletion_reason=:deletionReason,
                       retention_delete_started_at=null,
                       processing_error_code=null
                 where tenant_id=:tenantId
                   and document_id=:documentId
                   and status='RETENTION_DELETING'
                   and expires_at is not null
                   and expires_at <= current_timestamp
                """, params(tenantId, documentId)
                .addValue("deletedAt", Timestamp.from(deletedAt))
                .addValue("deletionReason", deletionReason));
        if (updated != 1) {
            throw new InternalCheckFailed("Retention finalization did not update exactly one RETENTION_DELETING document.");
        }
    }

    private static MapSqlParameterSource params(UUID tenantId, UUID documentId) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("documentId", documentId);
    }
}
