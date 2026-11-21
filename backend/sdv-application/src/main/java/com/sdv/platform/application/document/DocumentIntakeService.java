package com.sdv.platform.application.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.business.RecordKey;
import com.sdv.platform.application.business.RecordReferences;
import com.sdv.platform.application.port.AccessRequestRepository;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.DocumentIntakeRepository.DuplicateReceipt;
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
import com.sdv.platform.domain.document.SensitivityLevel;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

@Service
public class DocumentIntakeService {
    /** The upload header that carries the document's sensitivity level (task 0024). */
    public static final String SENSITIVITY_FIELD = "X-SDV-Sensitivity-Level";
    /** Task 0031: the acknowledgement of an exact duplicate, answered with the document Vero already had. */
    public static final String DUPLICATE_ALREADY_STORED = "DUPLICATE_ALREADY_STORED";
    public static final String DUPLICATE_ACTION = "DOCUMENT_DUPLICATE_RECEIVED";
    /** The reason in the level-change history when a duplicate raises the document it duplicates. */
    static final String DUPLICATE_RAISE_REASON = "Sent again by the sending system with a higher level";
    private static final Set<DocumentStatus> GONE = EnumSet.of(DocumentStatus.EXPIRED_DELETED, DocumentStatus.RETENTION_DELETING);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TenantRepository tenants;
    private final IngestionSourceRepository sources;
    private final SubmissionRepository submissions;
    private final DocumentIntakeRepository documents;
    private final StoragePlacementService placement;
    private final List<StorageProvider> storageProviders;
    private final StreamingCryptoPort crypto;
    private final DocumentFinalizationService finalization;
    private final AuditEventPort audit;
    private final RecordReferences records;
    private final AccessRequestRepository levels;
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
                                 RecordReferences records,
                                 AccessRequestRepository levels,
                                 PlatformTransactionManager transactionManager) {
        this(tenants, sources, submissions, documents, placement, storageProviders, crypto,
                finalization, audit, records, levels, new TransactionTemplate(transactionManager), Clock.systemUTC());
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
                          RecordReferences records,
                          AccessRequestRepository levels,
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
        this.records = Objects.requireNonNull(records, "records");
        this.levels = Objects.requireNonNull(levels, "levels");
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
                                   String sensitivityLevel,
                                   long declaredContentLength,
                                   InputStream input,
                                   String sourceSubject,
                                   String correlationId) throws IOException {
        // Task 0024: checked first, before anything is read or stored. Missing means STANDARD; anything unknown is 400.
        SensitivityLevel level = SensitivityLevel.fromRequest(sensitivityLevel, SENSITIVITY_FIELD);
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
            ensureReplayMatches(existing, filename, replayInput.sizeBytes(), replayInput.sha256(), level);

            Document finalized = existing.status() == DocumentStatus.AVAILABLE
                    ? existing
                    : finalization.finalizeDocument(
                            tenantId, sourceId, submissionId, existing.documentId(), sourceSubject, correlationId);
            return new DocumentReceipt(finalized, "RECEIVED", true, false);
        }

        // Task 0031, case 1 for a duplicate: a retry of a delivery that was an exact duplicate gets the same answer.
        DuplicateReceipt earlier = documents.findDuplicateReceipt(tenantId, submissionId, normalizedSourceDocumentId,
                normalizedSourceDocumentId == null ? normalizedIdempotencyKey : null).orElse(null);
        if (earlier != null) {
            ValidatedPdfInputStream replayInput = new ValidatedPdfInputStream(input, maxPdfBytes);
            replayInput.finish();
            return duplicateReplay(tenantId, earlier, replayInput.sizeBytes(), replayInput.sha256(), level);
        }

        // Task 0031: the record this submission belongs to (grouped as search groups it), for the duplicate check.
        String recordKey = records.ofSubmission(tenantId, sourceId, submissionId).recordKey();

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
                    filename, normalizedMediaType, level, contentSha256, contentSizeBytes, candidateDocumentId,
                    volume, stored, envelope, now, sourceSubject, correlationId, recordKey));
        } catch (RuntimeException ex) {
            safeDelete(provider, tenantId, volume, stored.objectKey());
            throw ex;
        }
        if (commitResult == null) {
            safeDelete(provider, tenantId, volume, stored.objectKey());
            throw new IllegalStateException("Document receipt transaction did not return a result.");
        }

        if (commitResult.duplicate() != null) {
            // Task 0031, case 2: nothing of this delivery is kept. The encrypted object written while the checksum was
            // worked out is removed now; no document, storage or key row was written for it.
            safeDelete(provider, tenantId, volume, stored.objectKey());
            return commitResult.duplicateReplay()
                    ? duplicateReplay(tenantId, commitResult.duplicate(), contentSizeBytes, contentSha256, level)
                    : new DocumentReceipt(commitResult.document(), DUPLICATE_ALREADY_STORED, false, true,
                            commitResult.duplicate().sourceDocumentId());
        }

        if (!commitResult.usedNewObject()) {
            safeDelete(provider, tenantId, volume, stored.objectKey());
            Document replay = commitResult.document();
            ensureReplayMatches(replay, filename, contentSizeBytes, contentSha256, level);
            Document finalized = replay.status() == DocumentStatus.AVAILABLE
                    ? replay
                    : finalization.finalizeDocument(
                            tenantId, sourceId, submissionId, replay.documentId(), sourceSubject, correlationId);
            return new DocumentReceipt(finalized, "RECEIVED", true, false);
        }

        Document finalized = finalization.finalizeDocument(
                tenantId, sourceId, submissionId, candidateDocumentId, sourceSubject, correlationId);
        return new DocumentReceipt(finalized, "RECEIVED", false, false);
    }

    /**
     * A retry of a delivery that was an exact duplicate: the same content and level as then, or it is refused like any
     * retry (409; SDV-SEN-003 for another level). The file name is not compared: a duplicate keeps none.
     */
    private DocumentReceipt duplicateReplay(UUID tenantId, DuplicateReceipt earlier, long sizeBytes, String sha256,
                                            SensitivityLevel level) {
        if (earlier.contentSizeBytes() != sizeBytes || !earlier.contentSha256().equals(sha256)) {
            throw new IllegalStateException(
                    "The document retry identity already exists with different content or filename.");
        }
        if (earlier.sentLevel() != level) {
            throw new com.sdv.platform.domain.error.CodedRefusal.Conflict(
                    com.sdv.platform.domain.error.SdvErrorCode.SEN_003,
                    "The document retry identity already exists with sensitivity level "
                    + earlier.sentLevel().name() + "; send the same " + SENSITIVITY_FIELD + " when retrying.");
        }
        Document document = documents.findDocumentByIdForTenant(tenantId, earlier.existingDocumentId())
                .orElseThrow(() -> new IllegalStateException("The document this delivery duplicated could not be read."));
        return new DocumentReceipt(document, DUPLICATE_ALREADY_STORED, true, true, earlier.sourceDocumentId());
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
                                       SensitivityLevel sensitivityLevel,
                                       String contentSha256,
                                       long contentSizeBytes,
                                       UUID documentId,
                                       StorageVolume volume,
                                       StorageProvider.StoredObject stored,
                                       StreamingCryptoPort.CryptoEnvelope envelope,
                                       Instant now,
                                       String sourceSubject,
                                       String correlationId,
                                       String recordKey) {
        Submission locked = documents.lockSubmission(tenant.tenantId(), source.sourceId(), submissionId);

        Document raced = findExistingIdentity(
                tenant.tenantId(), source.sourceId(), submissionId, sourceDocumentId, producerIdempotencyKey);
        if (raced != null) return new CommitResult(raced, false, null, false);
        String duplicateKey = sourceDocumentId == null ? producerIdempotencyKey : null;
        DuplicateReceipt racedDuplicate = documents.findDuplicateReceipt(
                tenant.tenantId(), submissionId, sourceDocumentId, duplicateKey).orElse(null);
        if (racedDuplicate != null) return new CommitResult(null, false, racedDuplicate, true);

        // Task 0031: a duplicate takes a place too, so it counts against the declared number like a document.
        long count = documents.countReceived(tenant.tenantId(), submissionId);
        if (count >= locked.expectedDocumentCount()) {
            throw new IllegalStateException("Submission already contains its declared expected number of documents.");
        }

        // Task 0031, case 2: the same file for the same record from this sending system is already kept. The lock makes
        // two such deliveries at once take turns; the record's submissions are read after it.
        documents.lockDuplicateCheck(tenant.tenantId(), source.sourceId(), recordKey, contentSha256);
        List<UUID> recordSubmissions = records.ofSubmission(tenant.tenantId(), source.sourceId(), submissionId).submissionIds();
        Document original = documents.findDuplicateOf(
                tenant.tenantId(), source.sourceId(), recordSubmissions, contentSha256).orElse(null);
        AccessRequestRepository.LockedDocument lockedOriginal = original == null ? null
                : levels.lockDocument(tenant.tenantId(), original.documentId()).orElse(null);
        if (lockedOriginal != null && !GONE.contains(DocumentStatus.valueOf(lockedOriginal.status()))) {
            return recordDuplicate(tenant.tenantId(), source.sourceId(), submissionId, locked, sourceDocumentId,
                    duplicateKey, original, lockedOriginal, sensitivityLevel, contentSha256, contentSizeBytes,
                    recordKey, now, sourceSubject, correlationId);
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
                retentionDays, now, expiresAt, null, 0, null, null, sensitivityLevel);

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
                        + "\",\"contentSizeBytes\":" + contentSizeBytes
                        + ",\"sensitivityLevel\":\"" + sensitivityLevel.name() + "\"}"));

        return new CommitResult(document, true, null, false);
    }

    /**
     * Task 0031, case 2: records an exact duplicate instead of a second copy, in the receipt's transaction. Its
     * manifest slot is marked received (the duplicate row names the document it points at), the submission's progress
     * is updated, a higher level raises the existing document (a source-system raise: level history and audit), a
     * lower or equal one is ignored (and said in the audit event), and the arrival is audited.
     */
    private CommitResult recordDuplicate(UUID tenantId, UUID sourceId, UUID submissionId, Submission locked,
                                         String sourceDocumentId, String duplicateKey, Document original,
                                         AccessRequestRepository.LockedDocument lockedOriginal,
                                         SensitivityLevel sentLevel, String contentSha256, long contentSizeBytes,
                                         String recordKey, Instant now, String sourceSubject, String correlationId) {
        SensitivityLevel own = lockedOriginal.ownLevel();
        boolean raise = sentLevel.compareTo(own) > 0;
        DuplicateReceipt receipt = new DuplicateReceipt(UUID.randomUUID(), tenantId, sourceId, submissionId,
                sourceDocumentId, duplicateKey, original.documentId(), contentSha256, contentSizeBytes, sentLevel,
                raise ? DuplicateReceipt.LevelOutcome.RAISED : DuplicateReceipt.LevelOutcome.KEPT, now);
        documents.insertDuplicateReceipt(receipt);
        if (sourceDocumentId != null) {
            documents.markExpectedDocumentReceived(tenantId, submissionId, sourceDocumentId, null, now);
        }
        documents.updateSubmissionAfterReceipt(tenantId, sourceId, submissionId, locked.expectedDocumentCount(), now);

        SensitivityLevel ownAfter = own;
        if (raise) {
            String who = sourceSubject == null || sourceSubject.isBlank() ? "SOURCE" : sourceSubject;
            levels.updateDocumentLevel(tenantId, original.documentId(), sentLevel);
            UUID changeId = UUID.randomUUID();
            levels.insertLevelChange(new AccessRequestRepository.LevelChange(changeId, tenantId,
                    AccessRequestRepository.LevelChange.Scope.DOCUMENT, original.documentId(), null, null,
                    AccessRequestRepository.LevelChange.Direction.RAISED, own, sentLevel, DUPLICATE_RAISE_REASON,
                    who, null, now, null));
            Map<String, Object> raised = new LinkedHashMap<>();
            raised.put("scope", "DOCUMENT");
            raised.put("from", own.name());
            raised.put("to", sentLevel.name());
            raised.put("levelChangeId", changeId.toString());
            raised.put("setBy", "SOURCE");
            raised.put("duplicateId", receipt.duplicateId().toString());
            audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "SOURCE", sourceSubject,
                    "DOCUMENT_SENSITIVITY_RAISED", "DOCUMENT", original.documentId().toString(), now, correlationId,
                    AuditOutcome.SUCCESS, json(raised)));
            ownAfter = sentLevel;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestId", sourceDocumentId != null ? sourceDocumentId : duplicateKey);
        details.put("requestIdKind", sourceDocumentId != null ? "SOURCE_DOCUMENT_ID" : "IDEMPOTENCY_KEY");
        details.put("sourceId", sourceId.toString());
        details.put("submissionId", submissionId.toString());
        details.put("recordKeySha256", RecordKey.sha256(recordKey));
        details.put("contentSha256", contentSha256);
        details.put("existingDocumentId", original.documentId().toString());
        details.put("duplicateId", receipt.duplicateId().toString());
        details.put("sentLevel", sentLevel.name());
        details.put("levelOutcome", receipt.levelOutcome().name());
        details.put("documentLevel", ownAfter.name());
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "SOURCE", sourceSubject, DUPLICATE_ACTION,
                "DOCUMENT", original.documentId().toString(), now, correlationId, AuditOutcome.SUCCESS, json(details)));
        return new CommitResult(withLevel(original, ownAfter), false, receipt, false);
    }

    private static Document withLevel(Document d, SensitivityLevel level) {
        return new Document(d.documentId(), d.tenantId(), d.submissionId(), d.sourceId(), d.sourceDocumentId(),
                d.producerIdempotencyKey(), d.originalFilename(), d.mediaType(), d.contentSha256(), d.contentSizeBytes(),
                d.status(), d.retentionDaysSnapshot(), d.ingestedAt(), d.expiresAt(), d.availableAt(),
                d.processingAttemptCount(), d.processingErrorCode(), d.deletedAt(), level);
    }

    private static String json(Map<String, Object> details) {
        try {
            return JSON.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("The duplicate could not be audited, so nothing was recorded.", ex);
        }
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
                                            String contentSha256,
                                            SensitivityLevel sensitivityLevel) {
        if (!existing.originalFilename().equals(filename)
                || existing.contentSizeBytes() == null
                || existing.contentSizeBytes() != contentSizeBytes
                || !existing.contentSha256().equals(contentSha256)) {
            throw new IllegalStateException(
                    "The document retry identity already exists with different content or filename.");
        }
        // A retry must repeat the level it was first sent with; the stored level is never changed by a retry.
        if (existing.sensitivityLevel() != sensitivityLevel) {
            throw new com.sdv.platform.domain.error.CodedRefusal.Conflict(
                    com.sdv.platform.domain.error.SdvErrorCode.SEN_003,
                    "The document retry identity already exists with sensitivity level "
                    + existing.sensitivityLevel().name() + "; send the same " + SENSITIVITY_FIELD + " when retrying.");
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

    /**
     * document: the stored document, the one a retry found, or (duplicate set) the document duplicated, with its level
     * after this delivery. duplicate: set when nothing of this delivery is kept (task 0031); duplicateReplay: the
     * delivery was found as an earlier duplicate (a retry racing this one) rather than recorded now.
     */
    private record CommitResult(Document document, boolean usedNewObject, DuplicateReceipt duplicate,
                                boolean duplicateReplay) { }
}
