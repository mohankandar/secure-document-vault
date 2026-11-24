package com.sdv.platform.application.document;

import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.application.access.ActiveTenant;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.application.source.ActiveSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.business.RecordKey;
import com.sdv.platform.application.business.RecordReferences;
import com.sdv.platform.application.port.AccessRequestRepository;
import com.sdv.platform.application.pdf.EncryptedScratch;
import com.sdv.platform.application.pdf.PdfIntakeChecks;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.DocumentIntakeRepository.DuplicateReceipt;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.application.port.SubmissionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.storage.StoragePlacementService;
import com.sdv.platform.application.storage.StorageProviderMissingException;
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
    /**
     * Task 0032 (0): a file that matches what a document of the record was before a permanent corrective redaction.
     * Treated as a duplicate of the corrected document (nothing is stored), with this event instead of
     * DOCUMENT_DUPLICATE_RECEIVED.
     */
    public static final String UNCORRECTED_COPY_ACTION = "DOCUMENT_UNCORRECTED_COPY_RECEIVED";
    /** Task 0039 (SDV-INT-018, 404): retry-finalization names no document of this submission. */
    static final String NO_SUCH_DOCUMENT = "No document of this submission has this ID, so it can't be finished again.";
    /** The reason in the level-change history when a duplicate raises the document it duplicates. */
    static final String DUPLICATE_RAISE_REASON = "Sent again by the sending system with a higher level";
    private static final Set<DocumentStatus> GONE = EnumSet.of(DocumentStatus.EXPIRED_DELETED, DocumentStatus.RETENTION_DELETING);
    private static final ObjectMapper JSON = VeroJson.mapper();
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
    private final PdfIntakeChecks pdfChecks;
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
                                 PlatformTransactionManager transactionManager,
                                 PdfIntakeChecks pdfChecks) {
        this(tenants, sources, submissions, documents, placement, storageProviders, crypto,
                finalization, audit, records, levels, new TransactionTemplate(transactionManager), Clock.systemUTC(), pdfChecks);
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
                          Clock clock,
                          PdfIntakeChecks pdfChecks) {
        this.pdfChecks = Objects.requireNonNull(pdfChecks, "pdfChecks");
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
                .orElseThrow(() -> ActiveSource.noSuchSubmission(submissionId));

        String normalizedSourceDocumentId = optionalText(sourceDocumentId, "X-SDV-Source-Document-Id", 200);
        String normalizedIdempotencyKey = optionalText(producerIdempotencyKey, "Idempotency-Key", 200);
        String filename = requireFilename(originalFilename);
        String normalizedMediaType = normalizeMediaType(mediaType);

        List<SubmissionExpectedDocument> expected = submissions.findExpectedDocuments(tenantId, submissionId);
        validateIdentityAgainstManifest(submission, expected, normalizedSourceDocumentId, normalizedIdempotencyKey);

        long maxPdfBytes = effectiveMaxPdfBytes(tenant, source);
        if (declaredContentLength > maxPdfBytes) {
            throw new PdfTooLargeException(maxPdfBytes);
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
        StorageProvider.StoredObject stored;
        // Task 0063: while the upload is encrypted into storage, the same bytes go to encrypted working space, and the
        // PDF is checked there before anything is committed. A refused one leaves nothing behind.
        try (EncryptedScratch scratch = pdfChecks.scratch(); EncryptedScratch.Buffer copy = scratch.createBuffer()) {
            stored = provider.store(tenantId, volume, output -> {
                StreamingCryptoPort.CryptoEnvelope envelope = crypto.encrypt(tenantId, new CopyingInputStream(validated, copy), output);
                validated.finish();
                envelopeHolder[0] = envelope;
            });
            try {
                copy.seek(0);
                pdfChecks.check(tenantId, source, effectiveMaxPages(tenant, source), copy, scratch);
            } catch (IOException | RuntimeException | Error ex) {
                safeDelete(provider, tenantId, volume, stored.objectKey());
                throw ex;
            }
        }
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
            throw new InternalCheckFailed("Document receipt transaction did not return a result.");
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
            throw retryDiffers(earlier.sourceDocumentId(), "content differs from the earlier duplicate");
        }
        if (earlier.sentLevel() != level) {
            throw new com.sdv.platform.domain.error.CodedRefusal.Conflict(
                    com.sdv.platform.domain.error.SdvErrorCode.SEN_003,
                    "The document retry identity already exists with sensitivity level "
                    + earlier.sentLevel().name() + "; send the same " + SENSITIVITY_FIELD + " when retrying.");
        }
        Document document = documents.findDocumentByIdForTenant(tenantId, earlier.existingDocumentId())
                .orElseThrow(() -> new InternalCheckFailed("The document this delivery duplicated could not be read."));
        return new DocumentReceipt(document, DUPLICATE_ALREADY_STORED, true, true, earlier.sourceDocumentId());
    }

    public List<Document> list(UUID tenantId, UUID sourceId, UUID submissionId) {
        requireActiveTenant(tenantId);
        requireActiveSource(tenantId, sourceId);
        submissions.findById(tenantId, sourceId, submissionId)
                .orElseThrow(() -> ActiveSource.noSuchSubmission(submissionId));
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
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.INT_018, NO_SUCH_DOCUMENT,
                        "documentId=" + documentId + " submissionId=" + submissionId));
        if (document.status() != DocumentStatus.PROCESSING_FAILED
                && document.status() != DocumentStatus.RECEIVED
                && document.status() != DocumentStatus.AVAILABLE) {
            throw new CodedRefusal.Conflict(SdvErrorCode.INT_018, "This document can't be finished again in its "
                    + "current state (" + document.status().name() + ").",
                    "documentId=" + documentId + " status=" + document.status().name());
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
            throw new CodedRefusal.Conflict(SdvErrorCode.INT_016,
                    "Submission already contains its declared expected number of documents.",
                    "submissionId=" + submissionId + " expected=" + locked.expectedDocumentCount());
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
                    recordKey, now, sourceSubject, correlationId, false);
        }

        // Task 0032 (0): the file is what a document of this record was before a permanent corrective redaction. Storing
        // it would bring the redacted content back, so it is a duplicate of the corrected document: only the corrected
        // version is kept. Same scope as an exact duplicate (tenant, sending system, record), under the same lock.
        Document corrected = documents.findCorrectedOriginalOf(
                tenant.tenantId(), source.sourceId(), recordSubmissions, contentSha256).orElse(null);
        AccessRequestRepository.LockedDocument lockedCorrected = corrected == null ? null
                : levels.lockDocument(tenant.tenantId(), corrected.documentId()).orElse(null);
        if (lockedCorrected != null && !GONE.contains(DocumentStatus.valueOf(lockedCorrected.status()))) {
            return recordDuplicate(tenant.tenantId(), source.sourceId(), submissionId, locked, sourceDocumentId,
                    duplicateKey, corrected, lockedCorrected, sensitivityLevel, contentSha256, contentSizeBytes,
                    recordKey, now, sourceSubject, correlationId, true);
        }

        long aggregateLimit = effectiveMaxAggregateBytes(tenant, source);
        long aggregate = documents.sumContentBytes(tenant.tenantId(), submissionId);
        if (contentSizeBytes > aggregateLimit - aggregate) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.INT_016,
                    "Document would exceed the configured aggregate submission size limit.",
                    "submissionId=" + submissionId + " limitBytes=" + aggregateLimit);
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
                                         String recordKey, Instant now, String sourceSubject, String correlationId,
                                         boolean uncorrectedCopy) {
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
        // Task 0032 (0): an uncorrected copy is told apart by its event; its checksum is the one before correction, and
        // the document it points at is the corrected one. The record's stewards are told (business notices).
        if (uncorrectedCopy) details.put("correctedContentSha256", original.contentSha256());
        UUID eventId = UUID.randomUUID();
        audit.append(new AuditEvent(eventId, tenantId, "SOURCE", sourceSubject,
                uncorrectedCopy ? UNCORRECTED_COPY_ACTION : DUPLICATE_ACTION,
                "DOCUMENT", original.documentId().toString(), now, correlationId, AuditOutcome.SUCCESS, json(details)));
        // Task 0033 (0.1): a security event, so the stewards' notice is stored, in the same transaction as the receipt.
        if (uncorrectedCopy) {
            documents.insertUncorrectedCopyNotice(UUID.randomUUID(), tenantId, sourceId, submissionId, eventId, now);
        }
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
        } catch (JacksonException ex) {
            throw InternalCheckFailed.auditNotWritten("The duplicate could not be audited, so nothing was recorded.", ex);
        }
    }

    private static void validateIdentityAgainstManifest(Submission submission,
                                                        List<SubmissionExpectedDocument> expected,
                                                        String sourceDocumentId,
                                                        String idempotencyKey) {
        if (sourceDocumentId == null && idempotencyKey == null) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.INT_012,
                    "Idempotency-Key is required when sourceDocumentId is not supplied.", "no document ID");
        }
        boolean manifestIsComplete = expected.size() == submission.expectedDocumentCount();
        if (manifestIsComplete) {
            if (sourceDocumentId == null) {
                throw new CodedRefusal.BadRequest(SdvErrorCode.INT_012,
                        "sourceDocumentId is required because the submission declared a complete manifest.",
                        "no X-SDV-Source-Document-Id, complete list");
            }
            boolean known = expected.stream().anyMatch(item -> item.sourceDocumentId().equals(sourceDocumentId));
            if (!known) {
                throw new CodedRefusal.BadRequest(SdvErrorCode.INT_012,
                        "sourceDocumentId is not part of the submission's declared complete manifest.",
                        "document ID not in the complete list of submissionId=" + submission.submissionId());
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
            throw retryDiffers(existing.sourceDocumentId(), "documentId=" + existing.documentId()
                    + (existing.originalFilename().equals(filename) ? " content differs" : " file name differs"));
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
        Tenant tenant = ActiveTenant.require(tenants, tenantId);
        return tenant;
    }

    private IngestionSource requireActiveSource(UUID tenantId, UUID sourceId) {
        return ActiveSource.require(sources, tenantId, sourceId);
    }

    /** Task 0039 (SDV-INT-017): the same document ID again with other content or another file name. */
    private static CodedRefusal.Conflict retryDiffers(String sourceDocumentId, String why) {
        return new CodedRefusal.Conflict(SdvErrorCode.INT_017,
                "The document retry identity already exists with different content or filename.",
                "sourceDocumentId=" + (sourceDocumentId == null ? "-" : sourceDocumentId) + " " + why);
    }

    private StorageProvider provider(String providerId) {
        return storageProviders.stream()
                .filter(candidate -> candidate.providerId().equals(providerId))
                .findFirst()
                // Task 0040 part 2: SDV-STO-004, 503 (Vero's setup); it was 409 CONFLICT.
                .orElseThrow(() -> new StorageProviderMissingException(
                        "Configured storage provider is unavailable: " + providerId));
    }

    private static long effectiveMaxPdfBytes(Tenant tenant, IngestionSource source) {
        long tenantMax = tenant.guardrails().maxPdfBytes();
        Long sourceMax = source.limits() == null ? null : source.limits().maxPdfBytes();
        return sourceMax == null ? tenantMax : Math.min(tenantMax, sourceMax);
    }

    /** Task 0063: the most pages a PDF of this sending system may have. */
    static int effectiveMaxPages(Tenant tenant, IngestionSource source) {
        int tenantMax = tenant.guardrails().maxPagesPerPdf();
        Integer sourceMax = source.limits() == null ? null : source.limits().maxPagesPerPdf();
        return sourceMax == null ? tenantMax : Math.min(tenantMax, sourceMax);
    }

    /** Hands on what it reads, and keeps a copy in the working space. */
    private static final class CopyingInputStream extends java.io.FilterInputStream {
        private final EncryptedScratch.Buffer copy;

        CopyingInputStream(InputStream in, EncryptedScratch.Buffer copy) {
            super(in);
            this.copy = copy;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) copy.write(b);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) copy.write(b, off, n);
            return n;
        }

        @Override
        public long skip(long n) {
            return 0;
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }

    private static long effectiveMaxAggregateBytes(Tenant tenant, IngestionSource source) {
        long tenantMax = tenant.guardrails().maxAggregateSubmissionBytes();
        Long sourceMax = source.limits() == null ? null : source.limits().maxAggregateSubmissionBytes();
        return sourceMax == null ? tenantMax : Math.min(tenantMax, sourceMax);
    }

    private static String normalizeMediaType(String mediaType) {
        if (mediaType == null) throw badHeader("Content-Type application/pdf is required.", "Content-Type");
        String value = mediaType.split(";", 2)[0].trim().toLowerCase();
        if (!"application/pdf".equals(value)) {
            throw badHeader("Only application/pdf document uploads are supported.", "Content-Type");
        }
        return value;
    }

    private static String requireFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw badHeader("X-SDV-Filename is required.", "X-SDV-Filename");
        }
        String value = filename.trim();
        if (value.length() > 500) throw badHeader("Filename exceeds 500 characters.", "X-SDV-Filename");
        if (value.contains("/") || value.contains("\\") || value.chars().anyMatch(Character::isISOControl)) {
            throw badHeader("Filename must be a simple display filename, not a filesystem path.", "X-SDV-Filename");
        }
        return value;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw badHeader(field + " exceeds " + maxLength + " characters.", field);
        }
        return trimmed;
    }

    /** Task 0039 (SDV-INT-013): an upload header that isn't valid; the log line names the header, never its value. */
    private static CodedRefusal.BadRequest badHeader(String message, String header) {
        return new CodedRefusal.BadRequest(SdvErrorCode.INT_013, message, "header=" + header);
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
