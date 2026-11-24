package com.sdv.platform.persistence.redaction;

import com.sdv.platform.application.port.HeldRedactionRepository;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStorageRef;
import com.sdv.platform.domain.error.InternalCheckFailed;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcHeldRedactionRepository implements HeldRedactionRepository {
    private static final String SEALED = """
            select s.sealed_id, s.tenant_id, s.document_id, s.request_id, s.provider_id, s.storage_volume_id, s.object_key,
                   s.encrypted_size_bytes, s.integrity_value, s.crypto_profile, s.crypto_version, s.key_provider_id,
                   s.protected_data_key_reference, s.key_provider_version, s.content_sha256, s.content_size_bytes, s.sealed_at
              from sealed_originals s
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcHeldRedactionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean onHold(UUID tenantId, UUID documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from legal_holds
                               where tenant_id=:tenantId and document_id=:documentId and released_at is null)
                """, document(tenantId, documentId), Boolean.class));
    }

    @Override
    public List<Approved> claimApproved(int limit) {
        return jdbc.query("""
                with due as (
                    select h.request_id
                      from held_redactions h
                      join access_requests r on r.request_id=h.request_id and r.status='APPROVED'
                     where h.state='APPROVED'
                       and (h.claimed_at is null or h.claimed_at <= current_timestamp - interval '15 minutes')
                     order by r.decided_at, h.request_id
                     limit :limit
                       for update of h skip locked)
                update held_redactions h
                   set claimed_at=current_timestamp, attempts=h.attempts+1
                  from due, access_requests r
                 where h.request_id=due.request_id and r.request_id=h.request_id
                returning h.request_id, h.tenant_id, h.document_id, r.requested_by, r.reason,
                          h.regions_json::text as regions_json, h.content_sha256, h.attempts
                """, new MapSqlParameterSource("limit", limit), (rs, row) -> new Approved(
                rs.getObject("request_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getString("requested_by"), rs.getString("reason"),
                rs.getString("regions_json"), rs.getString("content_sha256"), rs.getInt("attempts")));
    }

    @Override
    public void releaseClaim(UUID tenantId, UUID requestId) {
        jdbc.update("""
                update held_redactions set claimed_at=null, attempts=greatest(0, attempts-1)
                 where tenant_id=:tenantId and request_id=:id and state='APPROVED'
                """, request(tenantId, requestId));
    }

    @Override
    public void markApplied(UUID tenantId, UUID requestId, Instant at) {
        int changed = jdbc.update("""
                update held_redactions set state='APPLIED', applied_at=:at
                 where tenant_id=:tenantId and request_id=:id and state in ('APPROVED', 'FAILED')
                """, request(tenantId, requestId).addValue("at", Timestamp.from(at)));
        if (changed != 1) throw new InternalCheckFailed("The approved redaction could not be marked applied.");
    }

    @Override
    public void giveUp(UUID tenantId, UUID requestId) {
        jdbc.update("update held_redactions set state='FAILED' where tenant_id=:tenantId and request_id=:id and state='APPROVED'",
                request(tenantId, requestId));
    }

    @Override
    public void insertSealed(Sealed s) {
        jdbc.update("""
                insert into sealed_originals(sealed_id, tenant_id, document_id, request_id, provider_id, storage_volume_id,
                    object_key, encrypted_size_bytes, integrity_value, crypto_profile, crypto_version, key_provider_id,
                    protected_data_key_reference, key_provider_version, content_sha256, content_size_bytes, sealed_at, state)
                values (:id, :tenantId, :documentId, :requestId, :provider, :volume, :objectKey, :encryptedSize, :integrity,
                    :profile, :version, :keyProvider, :keyReference, :keyVersion, :sha, :size, :sealedAt, 'SEALED')
                """, document(s.tenantId(), s.documentId())
                .addValue("id", s.sealedId()).addValue("requestId", s.requestId())
                .addValue("provider", s.storage().providerId()).addValue("volume", s.storage().storageVolumeId())
                .addValue("objectKey", s.storage().objectKey()).addValue("encryptedSize", s.storage().encryptedSizeBytes())
                .addValue("integrity", s.storage().integrityValue()).addValue("profile", s.crypto().cryptoProfile())
                .addValue("version", s.crypto().cryptoVersion()).addValue("keyProvider", s.crypto().keyProviderId())
                .addValue("keyReference", s.crypto().protectedDataKeyReference())
                .addValue("keyVersion", s.crypto().keyProviderVersion()).addValue("sha", s.contentSha256())
                .addValue("size", s.contentSizeBytes()).addValue("sealedAt", Timestamp.from(s.sealedAt())));
    }

    @Override
    public List<Sealed> findSealed(UUID tenantId, UUID documentId) {
        return jdbc.query(SEALED + " where s.tenant_id=:tenantId and s.document_id=:documentId and s.state='SEALED' order by s.sealed_at, s.sealed_id",
                document(tenantId, documentId), sealed());
    }

    @Override
    public Optional<Sealed> findSealed(UUID tenantId, UUID documentId, UUID sealedId) {
        return jdbc.query(SEALED + " where s.tenant_id=:tenantId and s.document_id=:documentId and s.sealed_id=:id and s.state='SEALED'",
                document(tenantId, documentId).addValue("id", sealedId), sealed()).stream().findFirst();
    }

    @Override
    public List<Sealed> findToDestroy(int limit) {
        return jdbc.query(SEALED + """
                 where s.state='DESTROYING'
                    or (s.state='SEALED' and not exists (
                        select 1 from legal_holds h
                         where h.tenant_id=s.tenant_id and h.document_id=s.document_id and h.released_at is null))
                 order by s.sealed_at, s.sealed_id
                 limit :limit
                """, new MapSqlParameterSource("limit", limit), sealed());
    }

    @Override
    public boolean isDestroying(UUID tenantId, UUID sealedId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from sealed_originals where tenant_id=:tenantId and sealed_id=:id and state='DESTROYING')
                """, request(tenantId, sealedId), Boolean.class));
    }

    @Override
    public boolean startDestroying(UUID tenantId, UUID sealedId, Instant at) {
        return jdbc.update("""
                update sealed_originals set state='DESTROYING', destroying_at=:at
                 where tenant_id=:tenantId and sealed_id=:id and state='SEALED'
                """, request(tenantId, sealedId).addValue("at", Timestamp.from(at))) == 1;
    }

    @Override
    public boolean markDestroyed(UUID tenantId, UUID sealedId, Instant at) {
        return jdbc.update("""
                update sealed_originals
                   set state='DESTROYED', destroyed_at=:at, protected_data_key_reference=null
                 where tenant_id=:tenantId and sealed_id=:id and state='DESTROYING'
                """, request(tenantId, sealedId).addValue("at", Timestamp.from(at))) == 1;
    }

    private static MapSqlParameterSource document(UUID tenantId, UUID documentId) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("documentId", documentId);
    }

    private static MapSqlParameterSource request(UUID tenantId, UUID id) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", id);
    }

    private static RowMapper<Sealed> sealed() {
        return (rs, row) -> {
            UUID tenant = rs.getObject("tenant_id", UUID.class), document = rs.getObject("document_id", UUID.class);
            return new Sealed(rs.getObject("sealed_id", UUID.class), rs.getObject("request_id", UUID.class),
                    new DocumentStorageRef(document, tenant, rs.getString("provider_id"),
                            rs.getObject("storage_volume_id", UUID.class), rs.getString("object_key"),
                            rs.getLong("encrypted_size_bytes"), rs.getString("integrity_value")),
                    new DocumentCryptoMetadata(document, tenant, rs.getString("crypto_profile"), rs.getInt("crypto_version"),
                            rs.getString("key_provider_id"), rs.getString("protected_data_key_reference"),
                            rs.getString("key_provider_version")),
                    rs.getString("content_sha256"), rs.getLong("content_size_bytes"), rs.getTimestamp("sealed_at").toInstant());
        };
    }
}
