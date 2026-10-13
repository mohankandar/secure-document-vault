package com.sdv.platform.persistence.redaction;

import com.sdv.platform.application.port.DocumentRedactionRepository;
import com.sdv.platform.application.redaction.RedactionOperation;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.DocumentStorageRef;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcDocumentRedactionRepository implements DocumentRedactionRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDocumentRedactionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Document> lockDocument(UUID tenantId, UUID documentId) {
        return jdbc.query(documentSelect() + """
                 where tenant_id=:tenantId and document_id=:documentId
                 for update
                """, params(tenantId, documentId), documentMapper()).stream().findFirst();
    }

    @Override
    public Optional<RedactionOperation> findPending(UUID tenantId, UUID documentId) {
        return jdbc.query(operationSelect() + """
                 where tenant_id=:tenantId and document_id=:documentId
                """, params(tenantId, documentId), operationMapper()).stream().findFirst();
    }

    @Override
    public Optional<RedactionOperation> lockPending(UUID tenantId, UUID documentId) {
        return jdbc.query(operationSelect() + """
                 where tenant_id=:tenantId and document_id=:documentId
                 for update
                """, params(tenantId, documentId), operationMapper()).stream().findFirst();
    }

    @Override
    public Optional<RedactionOperation> claimPendingForCompletion(UUID tenantId, UUID documentId, UUID claimToken) {
        return jdbc.query("""
                with claimed as (
                    update document_redaction_operations
                       set recovery_claimed_at=current_timestamp,
                           recovery_claim_token=:claimToken
                     where tenant_id=:tenantId
                       and document_id=:documentId
                       and (recovery_claimed_at is null
                            or recovery_claimed_at <= current_timestamp - interval '10 minutes')
                       and exists (
                           select 1 from documents d
                            where d.tenant_id=document_redaction_operations.tenant_id
                              and d.document_id=document_redaction_operations.document_id
                              and d.status='REDACTION_PROCESSING'
                       )
                 returning operation_id
                )
                """ + operationSelect() + """
                 where operation_id in (select operation_id from claimed)
                """, params(tenantId, documentId).addValue("claimToken", claimToken), operationMapper()).stream().findFirst();
    }

    @Override
    public List<RedactionOperation> claimStaleForRecovery(int staleAfterMinutes, int limit, UUID claimToken) {
        if (staleAfterMinutes < 1 || staleAfterMinutes > 1440) {
            throw new IllegalArgumentException("staleAfterMinutes must be between 1 and 1440.");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100.");
        }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("staleAfterMinutes", staleAfterMinutes)
                .addValue("limit", limit)
                .addValue("claimToken", claimToken);
        return jdbc.query("""
                with candidates as (
                    select r.operation_id
                      from document_redaction_operations r
                      join documents d on d.tenant_id=r.tenant_id and d.document_id=r.document_id
                     where d.status='REDACTION_PROCESSING'
                       and r.created_at <= current_timestamp - (:staleAfterMinutes * interval '1 minute')
                       and (r.recovery_claimed_at is null
                            or r.recovery_claimed_at <= current_timestamp - interval '10 minutes')
                     order by r.created_at, r.operation_id
                     for update of r skip locked
                     limit :limit
                ), claimed as (
                    update document_redaction_operations r
                       set recovery_claimed_at=current_timestamp,
                           recovery_claim_token=:claimToken
                      from candidates c
                     where r.operation_id=c.operation_id
                 returning r.operation_id
                )
                """ + operationSelect() + """
                 where operation_id in (select operation_id from claimed)
                 order by created_at, operation_id
                """, p, operationMapper());
    }

    @Override
    public void releaseRecoveryClaim(UUID tenantId, UUID operationId, UUID claimToken) {
        jdbc.update("""
                update document_redaction_operations
                   set recovery_claimed_at=null, recovery_claim_token=null
                 where tenant_id=:tenantId and operation_id=:operationId
                   and recovery_claim_token=:claimToken
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("operationId", operationId)
                .addValue("claimToken", claimToken));
    }

    @Override
    public void insertPending(RedactionOperation op) {
        jdbc.update("""
                insert into document_redaction_operations(
                    operation_id, tenant_id, document_id, source_id, submission_id,
                    actor_id, request_sha256, reason, regions_json,
                    old_provider_id, old_storage_volume_id, old_object_key,
                    old_encrypted_size_bytes, old_integrity_value, old_content_sha256, old_content_size_bytes,
                    new_provider_id, new_storage_volume_id, new_object_key,
                    new_encrypted_size_bytes, new_integrity_value,
                    new_crypto_profile, new_crypto_version, new_key_provider_id,
                    new_protected_data_key_reference, new_key_provider_version,
                    new_content_sha256, new_content_size_bytes, created_at)
                values (
                    :operationId, :tenantId, :documentId, :sourceId, :submissionId,
                    :actorId, :requestSha256, :reason, cast(:regionsJson as jsonb),
                    :oldProviderId, :oldVolumeId, :oldObjectKey,
                    :oldEncryptedSize, :oldIntegrity, :oldContentSha, :oldContentSize,
                    :newProviderId, :newVolumeId, :newObjectKey,
                    :newEncryptedSize, :newIntegrity,
                    :newCryptoProfile, :newCryptoVersion, :newKeyProviderId,
                    :newProtectedReference, :newKeyProviderVersion,
                    :newContentSha, :newContentSize, current_timestamp)
                """, operationParams(op));
    }

    @Override
    public void markRedactionProcessing(UUID tenantId, UUID documentId, String expectedContentSha256) {
        int updated = jdbc.update("""
                update documents
                   set status='REDACTION_PROCESSING'
                 where tenant_id=:tenantId
                   and document_id=:documentId
                   and status='AVAILABLE'
                   and content_sha256=:expectedContentSha
                """, params(tenantId, documentId).addValue("expectedContentSha", expectedContentSha256));
        if (updated != 1) throw new IllegalStateException("Document changed before corrective redaction could be staged.");
    }

    @Override
    public void finalizeReplacement(UUID tenantId, UUID documentId, String expectedOldContentSha256,
                                    String newContentSha256, long newContentSizeBytes,
                                    DocumentStorageRef storage, DocumentCryptoMetadata crypto,
                                    Instant completedAt) {
        int docUpdated = jdbc.update("""
                update documents
                   set content_sha256=:newContentSha,
                       content_size_bytes=:newContentSize,
                       status='AVAILABLE',
                       processing_error_code=null
                 where tenant_id=:tenantId
                   and document_id=:documentId
                   and status='REDACTION_PROCESSING'
                   and content_sha256=:expectedOldContentSha
                """, params(tenantId, documentId)
                .addValue("newContentSha", newContentSha256)
                .addValue("newContentSize", newContentSizeBytes)
                .addValue("expectedOldContentSha", expectedOldContentSha256));
        if (docUpdated != 1) throw new IllegalStateException("Corrective redaction finalization did not update exactly one staged document.");

        int storageUpdated = jdbc.update("""
                update document_storage_refs
                   set provider_id=:providerId, storage_volume_id=:volumeId, object_key=:objectKey,
                       encrypted_size_bytes=:encryptedSize, integrity_value=:integrity
                 where tenant_id=:tenantId and document_id=:documentId
                """, params(tenantId, documentId)
                .addValue("providerId", storage.providerId())
                .addValue("volumeId", storage.storageVolumeId())
                .addValue("objectKey", storage.objectKey())
                .addValue("encryptedSize", storage.encryptedSizeBytes())
                .addValue("integrity", storage.integrityValue()));
        if (storageUpdated != 1) throw new IllegalStateException("Corrective redaction storage switch failed.");

        int cryptoUpdated = jdbc.update("""
                update document_crypto_metadata
                   set crypto_profile=:profile, crypto_version=:version, key_provider_id=:providerId,
                       protected_data_key_reference=:protectedReference, key_provider_version=:providerVersion
                 where tenant_id=:tenantId and document_id=:documentId
                """, params(tenantId, documentId)
                .addValue("profile", crypto.cryptoProfile())
                .addValue("version", crypto.cryptoVersion())
                .addValue("providerId", crypto.keyProviderId())
                .addValue("protectedReference", crypto.protectedDataKeyReference())
                .addValue("providerVersion", crypto.keyProviderVersion()));
        if (cryptoUpdated != 1) throw new IllegalStateException("Corrective redaction crypto switch failed.");
    }

    @Override
    public void deletePending(UUID tenantId, UUID operationId) {
        int deleted = jdbc.update("""
                delete from document_redaction_operations
                 where tenant_id=:tenantId and operation_id=:operationId
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("operationId", operationId));
        if (deleted != 1) throw new IllegalStateException("Corrective redaction pending-state cleanup failed.");
    }

    private static MapSqlParameterSource params(UUID tenantId, UUID documentId) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("documentId", documentId);
    }

    private static MapSqlParameterSource operationParams(RedactionOperation op) {
        return new MapSqlParameterSource()
                .addValue("operationId", op.operationId()).addValue("tenantId", op.tenantId())
                .addValue("documentId", op.documentId()).addValue("sourceId", op.sourceId())
                .addValue("submissionId", op.submissionId()).addValue("actorId", op.actorId())
                .addValue("requestSha256", op.requestSha256()).addValue("reason", op.reason())
                .addValue("regionsJson", op.regionsJson()).addValue("oldProviderId", op.oldProviderId())
                .addValue("oldVolumeId", op.oldStorageVolumeId()).addValue("oldObjectKey", op.oldObjectKey())
                .addValue("oldEncryptedSize", op.oldEncryptedSizeBytes()).addValue("oldIntegrity", op.oldIntegrityValue())
                .addValue("oldContentSha", op.oldContentSha256()).addValue("oldContentSize", op.oldContentSizeBytes())
                .addValue("newProviderId", op.newProviderId()).addValue("newVolumeId", op.newStorageVolumeId())
                .addValue("newObjectKey", op.newObjectKey()).addValue("newEncryptedSize", op.newEncryptedSizeBytes())
                .addValue("newIntegrity", op.newIntegrityValue()).addValue("newCryptoProfile", op.newCryptoProfile())
                .addValue("newCryptoVersion", op.newCryptoVersion()).addValue("newKeyProviderId", op.newKeyProviderId())
                .addValue("newProtectedReference", op.newProtectedDataKeyReference())
                .addValue("newKeyProviderVersion", op.newKeyProviderVersion())
                .addValue("newContentSha", op.newContentSha256()).addValue("newContentSize", op.newContentSizeBytes());
    }

    private static String documentSelect() {
        return """
                select document_id, tenant_id, submission_id, source_id, source_document_id,
                       producer_idempotency_key, original_filename, media_type, content_sha256,
                       content_size_bytes, status, retention_days_snapshot, ingested_at, expires_at,
                       available_at, processing_attempt_count, processing_error_code, deleted_at
                  from documents
                """;
    }

    private static RowMapper<Document> documentMapper() {
        return (rs, rowNum) -> new Document(
                rs.getObject("document_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("submission_id", UUID.class), rs.getObject("source_id", UUID.class),
                rs.getString("source_document_id"), rs.getString("producer_idempotency_key"),
                rs.getString("original_filename"), rs.getString("media_type"), rs.getString("content_sha256"),
                (Long) rs.getObject("content_size_bytes"), DocumentStatus.valueOf(rs.getString("status")),
                (Integer) rs.getObject("retention_days_snapshot"), rs.getTimestamp("ingested_at").toInstant(),
                rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("available_at") == null ? null : rs.getTimestamp("available_at").toInstant(),
                rs.getInt("processing_attempt_count"), rs.getString("processing_error_code"),
                rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant());
    }

    private static String operationSelect() {
        return """
                select operation_id, tenant_id, document_id, source_id, submission_id,
                       actor_id, request_sha256, reason, regions_json::text as regions_json,
                       old_provider_id, old_storage_volume_id, old_object_key,
                       old_encrypted_size_bytes, old_integrity_value, old_content_sha256, old_content_size_bytes,
                       new_provider_id, new_storage_volume_id, new_object_key,
                       new_encrypted_size_bytes, new_integrity_value, new_crypto_profile, new_crypto_version,
                       new_key_provider_id, new_protected_data_key_reference, new_key_provider_version,
                       new_content_sha256, new_content_size_bytes, created_at
                  from document_redaction_operations
                """;
    }

    private static RowMapper<RedactionOperation> operationMapper() {
        return (rs, rowNum) -> new RedactionOperation(
                rs.getObject("operation_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getObject("source_id", UUID.class),
                rs.getObject("submission_id", UUID.class), rs.getString("actor_id"),
                rs.getString("request_sha256"), rs.getString("reason"), rs.getString("regions_json"),
                rs.getString("old_provider_id"), rs.getObject("old_storage_volume_id", UUID.class),
                rs.getString("old_object_key"), rs.getLong("old_encrypted_size_bytes"),
                rs.getString("old_integrity_value"), rs.getString("old_content_sha256"),
                rs.getLong("old_content_size_bytes"), rs.getString("new_provider_id"),
                rs.getObject("new_storage_volume_id", UUID.class), rs.getString("new_object_key"),
                rs.getLong("new_encrypted_size_bytes"), rs.getString("new_integrity_value"),
                rs.getString("new_crypto_profile"), rs.getInt("new_crypto_version"),
                rs.getString("new_key_provider_id"), rs.getString("new_protected_data_key_reference"),
                rs.getString("new_key_provider_version"), rs.getString("new_content_sha256"),
                rs.getLong("new_content_size_bytes"), rs.getTimestamp("created_at").toInstant());
    }
}
