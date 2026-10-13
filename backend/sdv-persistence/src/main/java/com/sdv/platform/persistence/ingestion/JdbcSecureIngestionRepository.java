package com.sdv.platform.persistence.ingestion;

import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.application.port.SubmissionRepository;
import com.sdv.platform.domain.document.BusinessMetadataValue;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.DocumentStorageRef;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import com.sdv.platform.domain.submission.ExpectedDocumentState;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionExpectedDocument;
import com.sdv.platform.domain.submission.SubmissionStatus;
import org.springframework.jdbc.core.RowMapper;
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

@Repository
public class JdbcSecureIngestionRepository implements SubmissionRepository, StorageVolumeRepository, DocumentIntakeRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSecureIngestionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public InsertResult insertIfAbsent(Submission candidate) {
        var p = submissionParams(candidate);
        int inserted = jdbc.update("""
                insert into submissions(
                    submission_id, tenant_id, source_id, source_submission_id, expected_document_count,
                    create_request_sha256, status, created_at, last_activity_at, completed_at)
                values (:submissionId, :tenantId, :sourceId, :sourceSubmissionId, :expectedDocumentCount,
                    :createRequestSha256, :status, :createdAt, :lastActivityAt, :completedAt)
                on conflict (tenant_id, source_id, source_submission_id) do nothing
                """, p);
        if (inserted == 1) return new InsertResult(candidate, true);
        Submission existing = findBySourceIdentity(candidate.tenantId(), candidate.sourceId(), candidate.sourceSubmissionId())
                .orElseThrow(() -> new IllegalStateException("Submission idempotency conflict could not be resolved."));
        return new InsertResult(existing, false);
    }

    @Override
    public Optional<Submission> findById(UUID tenantId, UUID sourceId, UUID submissionId) {
        var p = new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("sourceId", sourceId).addValue("submissionId", submissionId);
        return jdbc.query(submissionSelect() + " where tenant_id=:tenantId and source_id=:sourceId and submission_id=:submissionId",
                p, submissionMapper()).stream().findFirst();
    }

    @Override
    public Optional<Submission> findBySourceIdentity(UUID tenantId, UUID sourceId, String sourceSubmissionId) {
        var p = new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("sourceId", sourceId).addValue("sourceSubmissionId", sourceSubmissionId);
        return jdbc.query(submissionSelect() + " where tenant_id=:tenantId and source_id=:sourceId and source_submission_id=:sourceSubmissionId",
                p, submissionMapper()).stream().findFirst();
    }

    @Override
    public List<SubmissionExpectedDocument> findExpectedDocuments(UUID tenantId, UUID submissionId) {
        var p = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("submissionId", submissionId);
        return jdbc.query("""
                select expected_document_id, tenant_id, submission_id, source_document_id, state,
                       received_document_id, created_at, received_at
                  from submission_expected_documents
                 where tenant_id=:tenantId and submission_id=:submissionId
                 order by created_at, source_document_id
                """, p, expectedDocumentMapper());
    }

    @Override
    public List<BusinessMetadataValue> findBusinessMetadata(UUID tenantId, UUID sourceId, UUID submissionId) {
        var p = new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("sourceId", sourceId).addValue("submissionId", submissionId);
        return jdbc.query("""
                select metadata_value_id, tenant_id, submission_id, source_id, definition_id,
                       field_key, definition_version, stored_value, normalized_value
                  from business_metadata_values
                 where tenant_id=:tenantId and source_id=:sourceId and submission_id=:submissionId
                 order by field_key
                """, p, businessMetadataMapper());
    }

    @Override
    public void insertExpectedDocuments(List<SubmissionExpectedDocument> expectedDocuments) {
        for (SubmissionExpectedDocument expected : expectedDocuments) {
            var p = new MapSqlParameterSource()
                    .addValue("id", expected.expectedDocumentId())
                    .addValue("tenantId", expected.tenantId())
                    .addValue("submissionId", expected.submissionId())
                    .addValue("sourceDocumentId", expected.sourceDocumentId())
                    .addValue("state", expected.state().name())
                    .addValue("receivedDocumentId", expected.receivedDocumentId())
                    .addValue("createdAt", Timestamp.from(expected.createdAt()))
                    .addValue("receivedAt", expected.receivedAt() == null ? null : Timestamp.from(expected.receivedAt()));
            jdbc.update("""
                    insert into submission_expected_documents(
                        expected_document_id, tenant_id, submission_id, source_document_id, state,
                        received_document_id, created_at, received_at)
                    values (:id, :tenantId, :submissionId, :sourceDocumentId, :state,
                        :receivedDocumentId, :createdAt, :receivedAt)
                    """, p);
        }
    }

    @Override
    public void insertBusinessMetadata(List<BusinessMetadataValue> metadataValues) {
        for (BusinessMetadataValue value : metadataValues) {
            var p = new MapSqlParameterSource()
                    .addValue("id", value.metadataValueId())
                    .addValue("tenantId", value.tenantId())
                    .addValue("submissionId", value.submissionId())
                    .addValue("sourceId", value.sourceId())
                    .addValue("definitionId", value.definitionId())
                    .addValue("fieldKey", value.fieldKey())
                    .addValue("definitionVersion", value.definitionVersion())
                    .addValue("storedValue", value.storedValue())
                    .addValue("normalizedValue", value.normalizedValue());
            jdbc.update("""
                    insert into business_metadata_values(
                        metadata_value_id, tenant_id, submission_id, source_id, definition_id,
                        field_key, definition_version, stored_value, normalized_value)
                    values (:id, :tenantId, :submissionId, :sourceId, :definitionId,
                        :fieldKey, :definitionVersion, :storedValue, :normalizedValue)
                    """, p);
        }
    }

    @Override
    public List<StorageVolume> findAll(UUID tenantId) {
        return jdbc.query(storageVolumeSelect() + " where tenant_id=:tenantId order by priority, display_name, volume_id",
                new MapSqlParameterSource("tenantId", tenantId), storageVolumeMapper());
    }

    @Override
    public Optional<StorageVolume> findById(UUID tenantId, UUID volumeId) {
        var p = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("volumeId", volumeId);
        return jdbc.query(storageVolumeSelect() + " where tenant_id=:tenantId and volume_id=:volumeId",
                p, storageVolumeMapper()).stream().findFirst();
    }

    @Override
    public void lockConfiguration() {
        jdbc.getJdbcTemplate().execute("lock table storage_volumes in share row exclusive mode");
    }

    @Override
    public List<ConfiguredRoot> findConfiguredRoots(String providerId) {
        return jdbc.query(
                "select volume_id, root_path from storage_volumes where provider_id=:providerId",
                new MapSqlParameterSource("providerId", providerId),
                (rs, rowNum) -> new ConfiguredRoot(rs.getObject("volume_id", UUID.class), rs.getString("root_path")));
    }

    @Override
    public void insert(StorageVolume volume) {
        jdbc.update("""
                insert into storage_volumes(
                    volume_id, tenant_id, provider_id, display_name, root_path, state, priority,
                    warning_percent, stop_write_percent, created_at, updated_at)
                values (:volumeId, :tenantId, :providerId, :displayName, :rootPath, :state, :priority,
                    :warningPercent, :stopWritePercent, :createdAt, :updatedAt)
                """, storageVolumeParams(volume));
    }

    @Override
    public void update(StorageVolume volume) {
        int updated = jdbc.update("""
                update storage_volumes
                   set display_name=:displayName, state=:state, priority=:priority,
                       warning_percent=:warningPercent, stop_write_percent=:stopWritePercent,
                       updated_at=:updatedAt
                 where tenant_id=:tenantId and volume_id=:volumeId
                """, storageVolumeParams(volume));
        if (updated != 1) throw new IllegalStateException("Storage volume update did not affect exactly one tenant-owned row.");
    }


    @Override
    public Submission lockSubmission(UUID tenantId, UUID sourceId, UUID submissionId) {
        var p = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("submissionId", submissionId);
        return jdbc.query(submissionSelect()
                        + " where tenant_id=:tenantId and source_id=:sourceId and submission_id=:submissionId for update",
                p, submissionMapper()).stream()
                .findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("Submission not found."));
    }

    @Override
    public Optional<Document> findDocumentById(UUID tenantId, UUID sourceId, UUID submissionId, UUID documentId) {
        var p = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("submissionId", submissionId)
                .addValue("documentId", documentId);
        return jdbc.query(documentSelect()
                        + " where tenant_id=:tenantId and source_id=:sourceId and submission_id=:submissionId and document_id=:documentId",
                p, documentMapper()).stream().findFirst();
    }

    @Override
    public Optional<Document> findDocumentByIdForTenant(UUID tenantId, UUID documentId) {
        var p = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("documentId", documentId);
        return jdbc.query(documentSelect()
                        + " where tenant_id=:tenantId and document_id=:documentId",
                p, documentMapper()).stream().findFirst();
    }

    @Override
    public Optional<Document> findBySourceDocumentId(UUID tenantId, UUID sourceId, UUID submissionId, String sourceDocumentId) {
        var p = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("submissionId", submissionId)
                .addValue("sourceDocumentId", sourceDocumentId);
        return jdbc.query(documentSelect()
                        + " where tenant_id=:tenantId and source_id=:sourceId and submission_id=:submissionId and source_document_id=:sourceDocumentId",
                p, documentMapper()).stream().findFirst();
    }

    @Override
    public Optional<Document> findByProducerIdempotencyKey(UUID tenantId, UUID sourceId, UUID submissionId, String producerIdempotencyKey) {
        var p = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("submissionId", submissionId)
                .addValue("idempotencyKey", producerIdempotencyKey);
        return jdbc.query(documentSelect()
                        + " where tenant_id=:tenantId and source_id=:sourceId and submission_id=:submissionId and producer_idempotency_key=:idempotencyKey",
                p, documentMapper()).stream().findFirst();
    }

    @Override
    public List<Document> findDocuments(UUID tenantId, UUID sourceId, UUID submissionId) {
        var p = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("submissionId", submissionId);
        return jdbc.query(documentSelect()
                        + " where tenant_id=:tenantId and source_id=:sourceId and submission_id=:submissionId order by ingested_at, document_id",
                p, documentMapper());
    }

    @Override
    public long countDocuments(UUID tenantId, UUID submissionId) {
        Long count = jdbc.queryForObject(
                "select count(*) from documents where tenant_id=:tenantId and submission_id=:submissionId",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("submissionId", submissionId),
                Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public long sumContentBytes(UUID tenantId, UUID submissionId) {
        Long total = jdbc.queryForObject(
                "select coalesce(sum(content_size_bytes),0) from documents where tenant_id=:tenantId and submission_id=:submissionId",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("submissionId", submissionId),
                Long.class);
        return total == null ? 0L : total;
    }

    @Override
    public boolean hasExactDuplicateContent(UUID tenantId, UUID sourceId, String contentSha256, UUID excludingDocumentId) {
        Long count = jdbc.queryForObject("""
                select count(*)
                  from documents
                 where tenant_id=:tenantId
                   and source_id=:sourceId
                   and content_sha256=:sha
                   and document_id<>:documentId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("sha", contentSha256)
                .addValue("documentId", excludingDocumentId), Long.class);
        return count != null && count > 0;
    }

    @Override
    public void insertDocument(Document document) {
        jdbc.update("""
                insert into documents(
                    document_id, tenant_id, submission_id, source_id, source_document_id,
                    producer_idempotency_key, original_filename, media_type, content_sha256,
                    content_size_bytes, status, retention_days_snapshot, ingested_at, expires_at,
                    available_at, processing_attempt_count, processing_error_code, deleted_at)
                values (
                    :documentId, :tenantId, :submissionId, :sourceId, :sourceDocumentId,
                    :producerIdempotencyKey, :originalFilename, :mediaType, :contentSha256,
                    :contentSizeBytes, :status, :retentionDaysSnapshot, :ingestedAt, :expiresAt,
                    :availableAt, :processingAttemptCount, :processingErrorCode, :deletedAt)
                """, documentParams(document));
    }

    @Override
    public void insertStorageRef(DocumentStorageRef storageRef) {
        jdbc.update("""
                insert into document_storage_refs(
                    document_id, tenant_id, provider_id, storage_volume_id, object_key,
                    encrypted_size_bytes, integrity_value)
                values (:documentId, :tenantId, :providerId, :volumeId, :objectKey,
                        :encryptedSizeBytes, :integrityValue)
                """, new MapSqlParameterSource()
                .addValue("documentId", storageRef.documentId())
                .addValue("tenantId", storageRef.tenantId())
                .addValue("providerId", storageRef.providerId())
                .addValue("volumeId", storageRef.storageVolumeId())
                .addValue("objectKey", storageRef.objectKey())
                .addValue("encryptedSizeBytes", storageRef.encryptedSizeBytes())
                .addValue("integrityValue", storageRef.integrityValue()));
    }

    @Override
    public void insertCryptoMetadata(DocumentCryptoMetadata cryptoMetadata) {
        jdbc.update("""
                insert into document_crypto_metadata(
                    document_id, tenant_id, crypto_profile, crypto_version, key_provider_id,
                    protected_data_key_reference, key_provider_version)
                values (:documentId, :tenantId, :profile, :version, :providerId,
                        :protectedReference, :providerVersion)
                """, new MapSqlParameterSource()
                .addValue("documentId", cryptoMetadata.documentId())
                .addValue("tenantId", cryptoMetadata.tenantId())
                .addValue("profile", cryptoMetadata.cryptoProfile())
                .addValue("version", cryptoMetadata.cryptoVersion())
                .addValue("providerId", cryptoMetadata.keyProviderId())
                .addValue("protectedReference", cryptoMetadata.protectedDataKeyReference())
                .addValue("providerVersion", cryptoMetadata.keyProviderVersion()));
    }

    @Override
    public Optional<DocumentStorageRef> findStorageRef(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                select document_id, tenant_id, provider_id, storage_volume_id, object_key,
                       encrypted_size_bytes, integrity_value
                  from document_storage_refs
                 where tenant_id=:tenantId and document_id=:documentId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("documentId", documentId),
                (rs, rowNum) -> new DocumentStorageRef(
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("provider_id"),
                        rs.getObject("storage_volume_id", UUID.class),
                        rs.getString("object_key"),
                        rs.getLong("encrypted_size_bytes"),
                        rs.getString("integrity_value")))
                .stream().findFirst();
    }

    @Override
    public Optional<DocumentCryptoMetadata> findCryptoMetadata(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                select document_id, tenant_id, crypto_profile, crypto_version, key_provider_id,
                       protected_data_key_reference, key_provider_version
                  from document_crypto_metadata
                 where tenant_id=:tenantId and document_id=:documentId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("documentId", documentId),
                (rs, rowNum) -> new DocumentCryptoMetadata(
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("crypto_profile"),
                        rs.getInt("crypto_version"),
                        rs.getString("key_provider_id"),
                        rs.getString("protected_data_key_reference"),
                        rs.getString("key_provider_version")))
                .stream().findFirst();
    }

    @Override
    public void markExpectedDocumentReceived(UUID tenantId,
                                             UUID submissionId,
                                             String sourceDocumentId,
                                             UUID documentId,
                                             Instant receivedAt) {
        int updated = jdbc.update("""
                update submission_expected_documents
                   set state='RECEIVED', received_document_id=:documentId, received_at=:receivedAt
                 where tenant_id=:tenantId
                   and submission_id=:submissionId
                   and source_document_id=:sourceDocumentId
                   and state='EXPECTED'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("submissionId", submissionId)
                .addValue("sourceDocumentId", sourceDocumentId)
                .addValue("documentId", documentId)
                .addValue("receivedAt", Timestamp.from(receivedAt)));
        if (updated > 1) {
            throw new IllegalStateException("Expected-document receipt updated more than one row.");
        }
    }

    @Override
    public void updateSubmissionAfterReceipt(UUID tenantId,
                                             UUID sourceId,
                                             UUID submissionId,
                                             int expectedDocumentCount,
                                             Instant activityAt) {
        long received = countDocuments(tenantId, submissionId);
        Long specificallyMissing = jdbc.queryForObject("""
                select count(*)
                  from submission_expected_documents
                 where tenant_id=:tenantId
                   and submission_id=:submissionId
                   and state='EXPECTED'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("submissionId", submissionId), Long.class);
        boolean complete = received >= expectedDocumentCount
                && (specificallyMissing == null || specificallyMissing == 0L);
        int updated = jdbc.update("""
                update submissions
                   set status=:status,
                       last_activity_at=:activityAt,
                       completed_at=:completedAt
                 where tenant_id=:tenantId and source_id=:sourceId and submission_id=:submissionId
                """, new MapSqlParameterSource()
                .addValue("status", complete ? "COMPLETE" : "RECEIVING")
                .addValue("activityAt", Timestamp.from(activityAt))
                .addValue("completedAt", complete ? Timestamp.from(activityAt) : null)
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("submissionId", submissionId));
        if (updated != 1) throw new IllegalStateException("Submission receipt update did not affect exactly one tenant-owned row.");
    }

    @Override
    public boolean transitionDocumentToProcessing(UUID tenantId,
                                                  UUID sourceId,
                                                  UUID submissionId,
                                                  UUID documentId,
                                                  Instant processingAt) {
        int updated = jdbc.update("""
                update documents
                   set status='PROCESSING',
                       processing_attempt_count=processing_attempt_count+1,
                       processing_error_code=null
                 where tenant_id=:tenantId
                   and source_id=:sourceId
                   and submission_id=:submissionId
                   and document_id=:documentId
                   and status in ('RECEIVED','PROCESSING_FAILED')
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("submissionId", submissionId)
                .addValue("documentId", documentId));
        return updated == 1;
    }

    @Override
    public void markDocumentAvailable(UUID tenantId,
                                      UUID sourceId,
                                      UUID submissionId,
                                      UUID documentId,
                                      Instant availableAt) {
        int updated = jdbc.update("""
                update documents
                   set status='AVAILABLE', available_at=:availableAt, processing_error_code=null
                 where tenant_id=:tenantId
                   and source_id=:sourceId
                   and submission_id=:submissionId
                   and document_id=:documentId
                   and status='PROCESSING'
                """, new MapSqlParameterSource()
                .addValue("availableAt", Timestamp.from(availableAt))
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("submissionId", submissionId)
                .addValue("documentId", documentId));
        if (updated != 1) throw new IllegalStateException("Document availability transition did not affect exactly one PROCESSING row.");
    }

    @Override
    public void markDocumentProcessingFailed(UUID tenantId,
                                             UUID sourceId,
                                             UUID submissionId,
                                             UUID documentId,
                                             String errorCode) {
        int updated = jdbc.update("""
                update documents
                   set status='PROCESSING_FAILED', processing_error_code=:errorCode
                 where tenant_id=:tenantId
                   and source_id=:sourceId
                   and submission_id=:submissionId
                   and document_id=:documentId
                   and status='PROCESSING'
                """, new MapSqlParameterSource()
                .addValue("errorCode", errorCode)
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("submissionId", submissionId)
                .addValue("documentId", documentId));
        if (updated != 1) throw new IllegalStateException("Document failure transition did not affect exactly one PROCESSING row.");
    }

    @Override
    public void markSubmissionIncompleteIfDue(UUID tenantId,
                                              UUID sourceId,
                                              UUID submissionId,
                                              Instant cutoff) {
        jdbc.update("""
                update submissions
                   set status='INCOMPLETE'
                 where tenant_id=:tenantId
                   and source_id=:sourceId
                   and submission_id=:submissionId
                   and status in ('OPEN','RECEIVING')
                   and last_activity_at <= :cutoff
                   and (select count(*) from documents d
                         where d.tenant_id=submissions.tenant_id
                           and d.submission_id=submissions.submission_id) < expected_document_count
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("submissionId", submissionId)
                .addValue("cutoff", Timestamp.from(cutoff)));
    }

    private static String submissionSelect() {
        return """
                select submission_id, tenant_id, source_id, source_submission_id, expected_document_count,
                       create_request_sha256, status, created_at, last_activity_at, completed_at
                  from submissions
                """;
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

    private static String storageVolumeSelect() {
        return """
                select volume_id, tenant_id, provider_id, display_name, root_path, state, priority,
                       warning_percent, stop_write_percent, created_at, updated_at
                  from storage_volumes
                """;
    }

    private static MapSqlParameterSource submissionParams(Submission submission) {
        return new MapSqlParameterSource()
                .addValue("submissionId", submission.submissionId())
                .addValue("tenantId", submission.tenantId())
                .addValue("sourceId", submission.sourceId())
                .addValue("sourceSubmissionId", submission.sourceSubmissionId())
                .addValue("expectedDocumentCount", submission.expectedDocumentCount())
                .addValue("createRequestSha256", submission.createRequestSha256())
                .addValue("status", submission.status().name())
                .addValue("createdAt", Timestamp.from(submission.createdAt()))
                .addValue("lastActivityAt", Timestamp.from(submission.lastActivityAt()))
                .addValue("completedAt", submission.completedAt() == null ? null : Timestamp.from(submission.completedAt()));
    }


    private static MapSqlParameterSource documentParams(Document document) {
        return new MapSqlParameterSource()
                .addValue("documentId", document.documentId())
                .addValue("tenantId", document.tenantId())
                .addValue("submissionId", document.submissionId())
                .addValue("sourceId", document.sourceId())
                .addValue("sourceDocumentId", document.sourceDocumentId())
                .addValue("producerIdempotencyKey", document.producerIdempotencyKey())
                .addValue("originalFilename", document.originalFilename())
                .addValue("mediaType", document.mediaType())
                .addValue("contentSha256", document.contentSha256())
                .addValue("contentSizeBytes", document.contentSizeBytes())
                .addValue("status", document.status().name())
                .addValue("retentionDaysSnapshot", document.retentionDaysSnapshot())
                .addValue("ingestedAt", Timestamp.from(document.ingestedAt()))
                .addValue("expiresAt", document.expiresAt() == null ? null : Timestamp.from(document.expiresAt()))
                .addValue("availableAt", document.availableAt() == null ? null : Timestamp.from(document.availableAt()))
                .addValue("processingAttemptCount", document.processingAttemptCount())
                .addValue("processingErrorCode", document.processingErrorCode())
                .addValue("deletedAt", document.deletedAt() == null ? null : Timestamp.from(document.deletedAt()));
    }

    private static MapSqlParameterSource storageVolumeParams(StorageVolume volume) {
        return new MapSqlParameterSource()
                .addValue("volumeId", volume.volumeId())
                .addValue("tenantId", volume.tenantId())
                .addValue("providerId", volume.providerId())
                .addValue("displayName", volume.displayName())
                .addValue("rootPath", volume.rootPath())
                .addValue("state", volume.state().name())
                .addValue("priority", volume.priority())
                .addValue("warningPercent", volume.warningPercent())
                .addValue("stopWritePercent", volume.stopWritePercent())
                .addValue("createdAt", Timestamp.from(volume.createdAt()))
                .addValue("updatedAt", Timestamp.from(volume.updatedAt()));
    }

    private static RowMapper<Submission> submissionMapper() {
        return (rs, rowNum) -> new Submission(
                rs.getObject("submission_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("source_id", UUID.class), rs.getString("source_submission_id"),
                rs.getInt("expected_document_count"), rs.getString("create_request_sha256"),
                SubmissionStatus.valueOf(rs.getString("status")), instant(rs, "created_at"),
                instant(rs, "last_activity_at"), instant(rs, "completed_at"));
    }

    private static RowMapper<SubmissionExpectedDocument> expectedDocumentMapper() {
        return (rs, rowNum) -> new SubmissionExpectedDocument(
                rs.getObject("expected_document_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("submission_id", UUID.class), rs.getString("source_document_id"),
                ExpectedDocumentState.valueOf(rs.getString("state")), rs.getObject("received_document_id", UUID.class),
                instant(rs, "created_at"), instant(rs, "received_at"));
    }

    private static RowMapper<BusinessMetadataValue> businessMetadataMapper() {
        return (rs, rowNum) -> new BusinessMetadataValue(
                rs.getObject("metadata_value_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("submission_id", UUID.class), rs.getObject("source_id", UUID.class),
                rs.getObject("definition_id", UUID.class), rs.getString("field_key"),
                rs.getInt("definition_version"), rs.getString("stored_value"), rs.getString("normalized_value"));
    }


    private static RowMapper<Document> documentMapper() {
        return (rs, rowNum) -> new Document(
                rs.getObject("document_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("submission_id", UUID.class),
                rs.getObject("source_id", UUID.class),
                rs.getString("source_document_id"),
                rs.getString("producer_idempotency_key"),
                rs.getString("original_filename"),
                rs.getString("media_type"),
                rs.getString("content_sha256"),
                rs.getObject("content_size_bytes", Long.class),
                DocumentStatus.valueOf(rs.getString("status")),
                rs.getObject("retention_days_snapshot", Integer.class),
                instant(rs, "ingested_at"),
                instant(rs, "expires_at"),
                instant(rs, "available_at"),
                rs.getInt("processing_attempt_count"),
                rs.getString("processing_error_code"),
                instant(rs, "deleted_at"));
    }

    private static RowMapper<StorageVolume> storageVolumeMapper() {
        return (rs, rowNum) -> new StorageVolume(
                rs.getObject("volume_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("provider_id"), rs.getString("display_name"), rs.getString("root_path"),
                StorageVolumeState.valueOf(rs.getString("state")), rs.getInt("priority"),
                rs.getInt("warning_percent"), rs.getInt("stop_write_percent"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
