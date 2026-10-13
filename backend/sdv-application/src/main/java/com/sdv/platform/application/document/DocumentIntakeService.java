package com.sdv.platform.application.document;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.application.port.SubmissionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.storage.StoragePlacementService;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.DocumentStorageRef;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.source.IngestionSourceStatus;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionExpectedDocument;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

@Service
public class DocumentIntakeService {
    private final TenantRepository tenants;
    private final IngestionSourceRepository sources;
    private final SubmissionRepository submissions;
    private final DocumentIntakeRepository documents;
    private final StoragePlacementService placement;
    private final List<StorageProvider> storageProviders;
    private final StreamingCryptoPort crypto;
    private final DocumentFinalizationService finalization;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public DocumentIntakeService(TenantRepository tenants,
                                 IngestionSourceRepository sources,
                                 SubmissionRepository submissions,
                                 DocumentIntakeRepository documents,
                                 StoragePlacementService placement,
                                 List<StorageProvider> storageProviders,
                                 StreamingCryptoPort crypto,
                                 DocumentFinalizationService finalization,
                                 AuditEventPort audit,
                                 PlatformTransactionManager transactionManager) {
        this(tenants, sources, submissions, documents, placement, storageProviders, crypto,
                finalization, audit, new TransactionTemplate(transactionManager), Clock.systemUTC());
    }

    DocumentIntakeService(TenantRepository tenants,
                          IngestionSourceRepository sources,
                          SubmissionRepository submissions,
                          DocumentIntakeRepository documents,
                          StoragePlacementService placement,
                          List<StorageProvider> storageProviders,
                          StreamingCryptoPort crypto,
                          DocumentFinalizationService finalization,
                          AuditEventPort audit,
                          TransactionTemplate transactions,
                          Clock clock) {
        this.tenants = tenants;
        this.sources = sources;
        this.submissions = submissions;
        this.documents = documents;
        this.placement = placement;
        this.storageProviders = List.copyOf(storageProviders);
        this.crypto = crypto;
        this.finalization = finalization;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    public DocumentReceipt receive(UUID tenantId,
                                   UUID sourceId,
                                   UUID submissionId,
                                   String sourceDocumentId,
                                   String producerIdempotencyKey,
                                   String originalFilename,
                                   String mediaType,
                                   long declaredContentLength,
                                   InputStream input,
                                   String sourceSubject,
                                   String correlationId) throws IOException {
        Tenant tenant = requireActiveTenant(tenantId);
        IngestionSource source = requireActiveSource(tenantId, sourceId);
        Submission submission = submissions.findById(tenantId, sourceId, submissionId)
                .orElseThrow(() -> new NoSuchElementException("Submission not found."));

        String normalizedSourceDocumentId = optionalText(sourceDocumentId, "sourceDocumentId", 200);
        String normalizedIdempotencyKey = optionalText(producerIdempotencyKey, "Idempotency-Key", 200);
        String filename = requireFilename(originalFilename);
        String normalizedMediaType = normalizeMediaType(mediaType);

        List<SubmissionExpectedDocument> expected = submissions.findExpectedDocuments(tenantId, submissionId);
        validateIdentityAgainstManifest(submission, expected, normalizedSourceDocumentId, normalizedIdempotencyKey);

        long maxPdfBytes = effectiveMaxPdfBytes(tenant, source);
        if (declaredContentLength > maxPdfBytes) {
            throw new PdfTooLargeException("PDF exceeds the configured maximum of " + maxPdfBytes + " bytes.");
        }

        Document existing = findExistingIdentity(
                tenantId, sourceId, submissionId, normalizedSourceDocumentId, normalizedIdempotencyKey);
        if (existing != null) {
            ValidatedPdfInputStream replayInput = new ValidatedPdfInputStream(input, maxPdfBytes);
            replayInput.finish();
            ensureReplayMatches(existing, filename, replayInput.sizeBytes(), replayInput.sha256());

            Document finalized = existing.status() == DocumentStatus.AVAILABLE
                    ? existing
                    : finalization.finalizeDocument(
                            tenantId, sourceId, submissionId, existing.documentId(), sourceSubject, correlationId);
            boolean duplicate = documents.hasExactDuplicateContent(
                    tenantId, sourceId, existing.contentSha256(), existing.documentId());
            return new DocumentReceipt(finalized, "RECEIVED", true, duplicate);
        }

        long placementBytes = estimateEncryptedSize(
                declaredContentLength >= 0 ? declaredContentLength : maxPdfBytes);
        StoragePlacementService.PlacementDecision decision = placement.select(tenantId, placementBytes);
        StorageVolume volume = decision.volume();
        StorageProvider provider = provider(volume.providerId());

        ValidatedPdfInputStream validated = new ValidatedPdfInputStream(input, maxPdfBytes);
        final StreamingCryptoPort.CryptoEnvelope[] envelopeHolder = new StreamingCryptoPort.CryptoEnvelope[1];
        StorageProvider.StoredObject stored = provider.store(tenantId, volume, output -> {
            StreamingCryptoPort.CryptoEnvelope envelope = crypto.encrypt(tenantId, validated, output);
            validated.finish();
            envelopeHolder[0] = envelope;
        });
        StreamingCryptoPort.CryptoEnvelope envelope = Objects.requireNonNull(
                envelopeHolder[0], "Crypto operation did not produce an envelope.");

        if (stored.sizeBytes() != envelope.encryptedSizeBytes()) {
            safeDelete(provider, tenantId, volume, stored.objectKey());
            throw new IOException("Encrypted object size does not match crypto envelope metadata.");
        }

        String contentSha256 = validated.sha256();
        long contentSizeBytes = validated.sizeBytes();
        Instant now = clock.instant();
        UUID candidateDocumentId = UUID.randomUUID();

        CommitResult commitResult;
        try {
            commitResult = transactions.execute(status -> commitReceipt(
                    tenant, source, submissionId, expected, normalizedSourceDocumentId, normalizedIdempotencyKey,
                    filename, normalizedMediaType, contentSha256, contentSizeBytes, candidateDocumentId,
                    volume, stored, envelope, now, sourceSubject, correlationId));
        } catch (RuntimeException ex) {
            safeDelete(provider, tenantId, volume, stored.objectKey());
            throw ex;
        }
        if (commitResult == null) {
            safeDelete(provider, tenantId, volume, stored.objectKey());
            throw new IllegalStateException("Document receipt transaction did not return a result.");
        }

        if (!commitResult.usedNewObject()) {
            safeDelete(provider, tenantId, volume, stored.objectKey());
            Document replay = commitResult.document();
            ensureReplayMatches(replay, filename, contentSizeBytes, contentSha256);
            Document finalized = replay.status() == DocumentStatus.AVAILABLE
                    ? replay
                    : finalization.finalizeDocument(
                            tenantId, sourceId, submissionId, replay.documentId(), sourceSubject, correlationId);
            boolean duplicate = documents.hasExactDuplicateContent(
                    tenantId, sourceId, replay.contentSha256(), replay.documentId());
            return new DocumentReceipt(finalized, "RECEIVED", true, duplicate);
        }

        Document finalized = finalization.finalizeDocument(
                tenantId, sourceId, submissionId, candidateDocumentId, sourceSubject, correlationId);
        boolean duplicate = documents.hasExactDuplicateContent(
                tenantId, sourceId, contentSha256, candidateDocumentId);
        return new DocumentReceipt(finalized, "RECEIVED", false, duplicate);
    }

    public List<Document> list(UUID tenantId, UUID sourceId, UUID submissionId) {
        requireActiveTenant(tenantId);
        requireActiveSource(tenantId, sourceId);
        submissions.findById(tenantId, sourceId, submissionId)
                .orElseThrow(() -> new NoSuchElementException("Submission not found."));
        return documents.findDocuments(tenantId, sourceId, submissionId);
    }

    public Document retryFinalization(UUID tenantId,
                                      UUID sourceId,
                                      UUID submissionId,
                                      UUID documentId,
                                      String sourceSubject,
                                      String correlationId) {
        requireActiveTenant(tenantId);
        requireActiveSource(tenantId, sourceId);
        Document document = documents.findDocumentById(tenantId, sourceId, submissionId, documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found."));
        if (document.status() != DocumentStatus.PROCESSING_FAILED
                && document.status() != DocumentStatus.RECEIVED
                && document.status() != DocumentStatus.AVAILABLE) {
            throw new IllegalStateException("Document is not eligible for finalization retry.");
        }
        return finalization.finalizeDocument(
                tenantId, sourceId, submissionId, documentId, sourceSubject, correlationId);
    }

    private CommitResult commitReceipt(Tenant tenant,
                                       IngestionSource source,
                                       UUID submissionId,
                                       List<SubmissionExpectedDocument> expected,
                                       String sourceDocumentId,
                                       String producerIdempotencyKey,
                                       String filename,
                                       String mediaType,
                                       String contentSha256,
                                       long contentSizeBytes,
                                       UUID documentId,
                                       StorageVolume volume,
                                       StorageProvider.StoredObject stored,
                                       StreamingCryptoPort.CryptoEnvelope envelope,
                                       Instant now,
                                       String sourceSubject,
                                       String correlationId) {
        Submission locked = documents.lockSubmission(tenant.tenantId(), source.sourceId(), submissionId);

        Document raced = findExistingIdentity(
                tenant.tenantId(), source.sourceId(), submissionId, sourceDocumentId, producerIdempotencyKey);
        if (raced != null) return new CommitResult(raced, false);

        long count = documents.countDocuments(tenant.tenantId(), submissionId);
        if (count >= locked.expectedDocumentCount()) {
            throw new IllegalStateException("Submission already contains its declared expected number of documents.");
        }

        long aggregateLimit = effectiveMaxAggregateBytes(tenant, source);
        long aggregate = documents.sumContentBytes(tenant.tenantId(), submissionId);
        if (contentSizeBytes > aggregateLimit - aggregate) {
            throw new IllegalArgumentException("Document would exceed the configured aggregate submission size limit.");
        }

        Integer retentionDays = tenant.guardrails().defaultRetentionDays();
        Instant expiresAt = retentionDays == null ? null : now.plus(retentionDays, ChronoUnit.DAYS);
        String storedIdempotencyKey = sourceDocumentId == null ? producerIdempotencyKey : null;
        Document document = new Document(
                documentId, tenant.tenantId(), submissionId, source.sourceId(),
                sourceDocumentId, storedIdempotencyKey, filename, mediaType,
                contentSha256, contentSizeBytes, DocumentStatus.RECEIVED,
                retentionDays, now, expiresAt, null, 0, null, null);

        documents.insertDocument(document);
        documents.insertStorageRef(new DocumentStorageRef(
                documentId, tenant.tenantId(), volume.providerId(), volume.volumeId(),
                stored.objectKey(), envelope.encryptedSizeBytes(), envelope.ciphertextSha256()));
        documents.insertCryptoMetadata(new DocumentCryptoMetadata(
                documentId, tenant.tenantId(), envelope.cryptoProfile(), envelope.cryptoVersion(),
                envelope.keyProviderId(), envelope.protectedDataKeyReference(), envelope.keyProviderVersion()));

        if (sourceDocumentId != null) {
            documents.markExpectedDocumentReceived(
                    tenant.tenantId(), submissionId, sourceDocumentId, documentId, now);
        }
        documents.updateSubmissionAfterReceipt(
                tenant.tenantId(), source.sourceId(), submissionId, locked.expectedDocumentCount(), now);

        audit.append(new AuditEvent(
                UUID.randomUUID(), tenant.tenantId(), "SOURCE", sourceSubject,
                "DOCUMENT_RECEIVED", "DOCUMENT", documentId.toString(), now, correlationId,
                AuditOutcome.SUCCESS,
                "{\"submissionId\":\"" + submissionId + "\",\"sourceId\":\"" + source.sourceId()
                        + "\",\"contentSizeBytes\":" + contentSizeBytes + "}"));

        return new CommitResult(document, true);
    }

    private static void validateIdentityAgainstManifest(Submission submission,
                                                        List<SubmissionExpectedDocument> expected,
                                                        String sourceDocumentId,
                                                        String idempotencyKey) {
        if (sourceDocumentId == null && idempotencyKey == null) {
            throw new IllegalArgumentException(
                    "Idempotency-Key is required when sourceDocumentId is not supplied.");
        }
        boolean manifestIsComplete = expected.size() == submission.expectedDocumentCount();
        if (manifestIsComplete) {
            if (sourceDocumentId == null) {
                throw new IllegalArgumentException(
                        "sourceDocumentId is required because the submission declared a complete manifest.");
            }
            boolean known = expected.stream().anyMatch(item -> item.sourceDocumentId().equals(sourceDocumentId));
            if (!known) {
                throw new IllegalArgumentException(
                        "sourceDocumentId is not part of the submission's declared complete manifest.");
            }
        }
    }

    private Document findExistingIdentity(UUID tenantId,
                                          UUID sourceId,
                                          UUID submissionId,
                                          String sourceDocumentId,
                                          String producerIdempotencyKey) {
        if (sourceDocumentId != null) {
            return documents.findBySourceDocumentId(tenantId, sourceId, submissionId, sourceDocumentId)
                    .orElse(null);
        }
        return documents.findByProducerIdempotencyKey(
                tenantId, sourceId, submissionId, producerIdempotencyKey).orElse(null);
    }

    private static void ensureReplayMatches(Document existing,
                                            String filename,
                                            long contentSizeBytes,
                                            String contentSha256) {
        if (!existing.originalFilename().equals(filename)
                || existing.contentSizeBytes() == null
                || existing.contentSizeBytes() != contentSizeBytes
                || !existing.contentSha256().equals(contentSha256)) {
            throw new IllegalStateException(
                    "The document retry identity already exists with different content or filename.");
        }
    }

    private Tenant requireActiveTenant(UUID tenantId) {
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found."));
        if (tenant.status() != TenantStatus.ACTIVE) throw new IllegalStateException("Tenant is not active.");
        return tenant;
    }

    private IngestionSource requireActiveSource(UUID tenantId, UUID sourceId) {
        IngestionSource source = sources.findById(tenantId, sourceId)
                .orElseThrow(() -> new NoSuchElementException("Ingestion source not found."));
        if (source.status() != IngestionSourceStatus.ACTIVE) {
            throw new IllegalStateException("Ingestion source is not active.");
        }
        return source;
    }

    private StorageProvider provider(String providerId) {
        return storageProviders.stream()
                .filter(candidate -> candidate.providerId().equals(providerId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Configured storage provider is unavailable: " + providerId));
    }

    private static long effectiveMaxPdfBytes(Tenant tenant, IngestionSource source) {
        long tenantMax = tenant.guardrails().maxPdfBytes();
        Long sourceMax = source.limits() == null ? null : source.limits().maxPdfBytes();
        return sourceMax == null ? tenantMax : Math.min(tenantMax, sourceMax);
    }

    private static long effectiveMaxAggregateBytes(Tenant tenant, IngestionSource source) {
        long tenantMax = tenant.guardrails().maxAggregateSubmissionBytes();
        Long sourceMax = source.limits() == null ? null : source.limits().maxAggregateSubmissionBytes();
        return sourceMax == null ? tenantMax : Math.min(tenantMax, sourceMax);
    }

    private static String normalizeMediaType(String mediaType) {
        if (mediaType == null) throw new IllegalArgumentException("Content-Type application/pdf is required.");
        String value = mediaType.split(";", 2)[0].trim().toLowerCase();
        if (!"application/pdf".equals(value)) {
            throw new IllegalArgumentException("Only application/pdf document uploads are supported.");
        }
        return value;
    }

    private static String requireFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("X-SDV-Filename is required.");
        }
        String value = filename.trim();
        if (value.length() > 500) throw new IllegalArgumentException("Filename exceeds 500 characters.");
        if (value.contains("/") || value.contains("\\") || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Filename must be a simple display filename, not a filesystem path.");
        }
        return value;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters.");
        }
        return trimmed;
    }

    private static long estimateEncryptedSize(long plaintextBytes) {
        long chunks = Math.max(1L, (plaintextBytes + (64L * 1024L) - 1L) / (64L * 1024L));
        long overhead;
        try {
            overhead = Math.addExact(64L, Math.multiplyExact(chunks, 32L));
            return Math.addExact(plaintextBytes, overhead);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private static void safeDelete(StorageProvider provider,
                                   UUID tenantId,
                                   StorageVolume volume,
                                   String objectKey) {
        try {
            provider.delete(tenantId, volume, objectKey);
        } catch (Exception ignored) {
            // The receipt still fails closed. Later operational reconciliation owns rare orphan cleanup.
        }
    }

    private record CommitResult(Document document, boolean usedNewObject) { }
}
