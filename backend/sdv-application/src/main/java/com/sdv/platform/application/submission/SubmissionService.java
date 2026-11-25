package com.sdv.platform.application.submission;

import com.sdv.platform.application.access.ActiveTenant;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.application.source.ActiveSource;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.RecordSensitivityRepository;
import com.sdv.platform.application.port.RecordValuesPort;
import com.sdv.platform.application.port.SubmissionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.source.IngestionSourceStatus;
import com.sdv.platform.domain.submission.ExpectedDocumentState;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionExpectedDocument;
import com.sdv.platform.domain.submission.SubmissionStatus;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class SubmissionService {
    private final TenantRepository tenants;
    private final IngestionSourceRepository sources;
    private final MetadataFieldDefinitionRepository definitions;
    private final SubmissionRepository submissions;
    private final DocumentIntakeRepository documents;
    private final RecordSensitivityRepository recordLevels;
    private final AuditEventPort audit;
    private final RecordValuesPort recordValues;
    /* Null in set-ups that read no values back. */
    private final BusinessRecordRepository records;
    private final Clock clock;

    /** The submission JSON field that carries the record's level (task 0024, part 2). */
    public static final String RECORD_SENSITIVITY_FIELD = "recordSensitivityLevel";

    @Autowired
    public SubmissionService(TenantRepository tenants,
                             IngestionSourceRepository sources,
                             MetadataFieldDefinitionRepository definitions,
                             SubmissionRepository submissions,
                             DocumentIntakeRepository documents,
                             RecordSensitivityRepository recordLevels,
                             AuditEventPort audit,
                             RecordValuesPort recordValues,
                             BusinessRecordRepository records) {
        this(tenants, sources, definitions, submissions, documents, recordLevels, audit, recordValues, records, Clock.systemUTC());
    }

    SubmissionService(TenantRepository tenants,
                      IngestionSourceRepository sources,
                      MetadataFieldDefinitionRepository definitions,
                      SubmissionRepository submissions,
                      DocumentIntakeRepository documents,
                      RecordSensitivityRepository recordLevels,
                      AuditEventPort audit,
                      Clock clock) {
        this(tenants, sources, definitions, submissions, documents, recordLevels, audit, RecordValuesPort.NONE, null, clock);
    }

    SubmissionService(TenantRepository tenants,
                      IngestionSourceRepository sources,
                      MetadataFieldDefinitionRepository definitions,
                      SubmissionRepository submissions,
                      DocumentIntakeRepository documents,
                      RecordSensitivityRepository recordLevels,
                      AuditEventPort audit,
                      RecordValuesPort recordValues,
                      BusinessRecordRepository records,
                      Clock clock) {
        this.tenants = tenants;
        this.sources = sources;
        this.definitions = definitions;
        this.submissions = submissions;
        this.documents = documents;
        this.recordLevels = recordLevels;
        this.audit = audit;
        this.recordValues = recordValues;
        this.records = records;
        this.clock = clock;
    }


    // Test-support constructor retained so frozen Checkpoint A unit tests do not need to be rewritten
    // simply because Unit 10 now reads document progress.
    SubmissionService(TenantRepository tenants,
                      IngestionSourceRepository sources,
                      MetadataFieldDefinitionRepository definitions,
                      SubmissionRepository submissions,
                      AuditEventPort audit,
                      Clock clock) {
        this(tenants, sources, definitions, submissions, new EmptyDocumentIntakeRepository(),
                new NoRecordLevelRepository(), audit, clock);
    }

    @Transactional
    public SubmissionDetails create(UUID tenantId,
                                    UUID sourceId,
                                    CreateSubmissionCommand command,
                                    String sourceSubject,
                                    String correlationId) {
        Tenant tenant = requireActiveTenant(tenantId);
        IngestionSource source = requireActiveSource(tenantId, sourceId);
        CanonicalRequest canonical = canonicalize(command);

        var existing = submissions.findBySourceIdentity(tenantId, sourceId, canonical.sourceSubmissionId());
        if (existing.isPresent()) {
            ensureSameCreateRequest(existing.get(), canonical);
            return details(existing.get(), true, canonical.metadata());
        }

        int maxDocuments = effectiveMaxDocuments(tenant, source);
        if (canonical.expectedDocumentCount() > maxDocuments) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.INT_007,
                    "expectedDocumentCount exceeds the configured submission limit of " + maxDocuments + ".",
                    "limit=" + maxDocuments);
        }
        if (canonical.expectedSourceDocumentIds().size() > canonical.expectedDocumentCount()) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.INT_007,
                    "Expected manifest entries cannot exceed expectedDocumentCount.", "more listed than the count");
        }

        List<MetadataFieldDefinition> currentDefinitions = definitions.findCurrent(tenantId, sourceId);
        validateMetadata(currentDefinitions, canonical.metadata());

        Instant now = clock.instant();
        Submission candidate = new Submission(
                UUID.randomUUID(), tenantId, sourceId, canonical.sourceSubmissionId(),
                canonical.expectedDocumentCount(), canonical.requestSha256(), SubmissionStatus.OPEN,
                now, now, null);

        SubmissionRepository.InsertResult insertResult = submissions.insertIfAbsent(candidate);
        if (!insertResult.inserted()) {
            ensureSameCreateRequest(insertResult.submission(), canonical);
            return details(insertResult.submission(), true, canonical.metadata());
        }

        UUID submissionId = candidate.submissionId();
        List<SubmissionExpectedDocument> expectedDocuments = canonical.expectedSourceDocumentIds().stream()
                .map(sourceDocumentId -> new SubmissionExpectedDocument(
                        UUID.randomUUID(), tenantId, submissionId, sourceDocumentId,
                        ExpectedDocumentState.EXPECTED, null, now, null))
                .toList();

        submissions.insertExpectedDocuments(expectedDocuments);
        UUID recordId = recordValues.store(tenantId, sourceId, submissionId, canonical.metadata());
        if (canonical.recordSensitivityLevel() != null) {
            applyRecordLevel(tenantId, sourceId, submissionId,
                    recordId,
                    canonical.recordSensitivityLevel(), sourceSubject, correlationId, now);
        }
        audit.append(new AuditEvent(
                UUID.randomUUID(), tenantId, "SOURCE", sourceSubject,
                "SUBMISSION_CREATED", "SUBMISSION", submissionId.toString(), now, correlationId,
                AuditOutcome.SUCCESS,
                "{\"sourceId\":\"" + sourceId + "\",\"expectedDocumentCount\":"
                        + canonical.expectedDocumentCount() + ",\"manifestCount\":" + expectedDocuments.size()
                        + ",\"metadataFieldCount\":" + canonical.metadata().size()
                        + ",\"fields\":[" + fieldNames(canonical.metadata()) + "]}"));

        return details(candidate, false, canonical.metadata());
    }

    public SubmissionDetails get(UUID tenantId, UUID sourceId, UUID submissionId) {
        requireActiveTenant(tenantId);
        requireActiveSource(tenantId, sourceId);
        Submission submission = submissions.findById(tenantId, sourceId, submissionId)
                .orElseThrow(() -> ActiveSource.noSuchSubmission(submissionId));
        return details(submission, false, null);
    }

    public SubmissionDetails getBySourceSubmissionId(UUID tenantId, UUID sourceId, String sourceSubmissionId) {
        requireActiveTenant(tenantId);
        requireActiveSource(tenantId, sourceId);
        String normalized = requireText(sourceSubmissionId, "sourceSubmissionId", 200);
        Submission submission = submissions.findBySourceIdentity(tenantId, sourceId, normalized)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.INT_004, ActiveSource.NO_SUCH_SUBMISSION,
                        "no submission with this sourceSubmissionId"));
        return details(submission, false, null);
    }

    /** The delivered fields' keys (letters, digits, '.', '_', '-'; they need no escaping). Never a value. */
    private static String fieldNames(Map<String, String> metadata) {
        return metadata.keySet().stream().sorted().map(k -> "\"" + k + "\"").collect(java.util.stream.Collectors.joining(","));
    }

    private Map<String, String> readable(Submission submission) {
        Map<String, String> out = new java.util.TreeMap<>();
        if (records == null) return out;
        records.findMetadataSnapshots(submission.tenantId(), submission.sourceId(), List.of(submission.submissionId()))
                .forEach(v -> out.put(v.fieldKey(), v.storedValue()));
        return out;
    }

    private SubmissionDetails details(Submission submission, boolean replay, Map<String, String> sent) {
        Submission effective = refreshIncompleteStatus(submission);
        List<SubmissionExpectedDocument> expectedDocuments =
                submissions.findExpectedDocuments(effective.tenantId(), effective.submissionId());
        List<Document> documentList =
                documents.findDocuments(effective.tenantId(), effective.sourceId(), effective.submissionId());

        SubmissionProgress progress = null;
        if (effective.status() != SubmissionStatus.COMPLETE) {
            // Task 0031: an exact duplicate fills its place too ("2 of 4 received"), though no second copy is stored.
            int received = (int) documents.countReceived(effective.tenantId(), effective.submissionId());
            List<String> missingIds = expectedDocuments.stream()
                    .filter(item -> item.state() == ExpectedDocumentState.EXPECTED)
                    .map(SubmissionExpectedDocument::sourceDocumentId)
                    .sorted()
                    .toList();
            progress = new SubmissionProgress(
                    effective.expectedDocumentCount(),
                    received,
                    Math.max(0, effective.expectedDocumentCount() - received),
                    missingIds);
        }

        return new SubmissionDetails(
                effective,
                expectedDocuments,
                sent != null ? sent : readable(effective),
                documentList,
                progress,
                replay);
    }

    private Submission refreshIncompleteStatus(Submission submission) {
        if (submission.status() == SubmissionStatus.COMPLETE || submission.status() == SubmissionStatus.INCOMPLETE) {
            return submission;
        }
        Tenant tenant = requireActiveTenant(submission.tenantId());
        IngestionSource source = requireActiveSource(submission.tenantId(), submission.sourceId());
        int hours = tenant.guardrails().incompleteAttentionHours();
        if (source.limits() != null && source.limits().incompleteAttentionHours() != null) {
            hours = Math.min(hours, source.limits().incompleteAttentionHours());
        }
        Instant cutoff = clock.instant().minus(hours, java.time.temporal.ChronoUnit.HOURS);
        documents.markSubmissionIncompleteIfDue(
                submission.tenantId(), submission.sourceId(), submission.submissionId(), cutoff);
        return submissions.findById(submission.tenantId(), submission.sourceId(), submission.submissionId())
                .orElseThrow(() -> ActiveSource.noSuchSubmission(submission.submissionId()));
    }

    private Tenant requireActiveTenant(UUID tenantId) {
        Tenant tenant = ActiveTenant.require(tenants, tenantId);
        return tenant;
    }

    private IngestionSource requireActiveSource(UUID tenantId, UUID sourceId) {
        return ActiveSource.require(sources, tenantId, sourceId);
    }

    private static int effectiveMaxDocuments(Tenant tenant, IngestionSource source) {
        int tenantMax = tenant.guardrails().maxDocumentsPerSubmission();
        Integer sourceMax = source.limits() == null ? null : source.limits().maxDocumentsPerSubmission();
        return sourceMax == null ? tenantMax : Math.min(tenantMax, sourceMax);
    }

    private static CanonicalRequest canonicalize(CreateSubmissionCommand command) {
        if (command == null) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.INT_006, "Submission request is required.", "no body");
        }
        String sourceSubmissionId = requireText(command.sourceSubmissionId(), "sourceSubmissionId", 200);
        if (command.expectedDocumentCount() < 1) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.INT_006, "expectedDocumentCount must be at least 1.",
                    "field=expectedDocumentCount");
        }

        List<String> manifest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (command.expectedSourceDocumentIds() != null) {
            for (String rawId : command.expectedSourceDocumentIds()) {
                String id = requireText(rawId, "sourceDocumentId", 200, SdvErrorCode.INT_007);
                if (!seen.add(id)) {
                    throw new CodedRefusal.BadRequest(SdvErrorCode.INT_007,
                            "Expected manifest contains duplicate sourceDocumentId '" + id + "'.", "a listed ID twice");
                }
                manifest.add(id);
            }
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        if (command.metadata() != null) {
            command.metadata().forEach((key, value) -> {
                String fieldKey = requireText(key, "metadata field key", 100, SdvErrorCode.INT_008);
                if (value == null) {
                    throw new CodedRefusal.BadRequest(SdvErrorCode.INT_008,
                            "Metadata field '" + fieldKey + "' cannot be null.", "field=" + fieldKey + " null");
                }
                metadata.put(fieldKey, value);
            });
        }

        // Checked before anything is read or stored: an unknown or wrongly cased value is refused, never guessed.
        SensitivityLevel recordLevel = SensitivityLevel.optionalFromRequest(
                command.recordSensitivityLevel(), RECORD_SENSITIVITY_FIELD);

        String requestSha = requestFingerprint(sourceSubmissionId, command.expectedDocumentCount(), manifest, metadata,
                recordLevel);
        return new CanonicalRequest(sourceSubmissionId, command.expectedDocumentCount(), List.copyOf(manifest),
                Map.copyOf(metadata), recordLevel, requestSha);
    }

    private static void validateMetadata(List<MetadataFieldDefinition> definitions, Map<String, String> supplied) {
        Map<String, MetadataFieldDefinition> byKey = new HashMap<>();
        definitions.forEach(definition -> byKey.put(definition.fieldKey(), definition));

        for (String suppliedKey : supplied.keySet()) {
            if (!byKey.containsKey(suppliedKey)) {
                throw new CodedRefusal.BadRequest(SdvErrorCode.INT_008,
                        "Metadata field '" + suppliedKey + "' is not configured for this ingestion source.",
                        "field=" + suppliedKey + " unknown");
            }
        }
        for (MetadataFieldDefinition definition : definitions) {
            if (definition.required() && (!supplied.containsKey(definition.fieldKey()) || supplied.get(definition.fieldKey()).isBlank())) {
                throw new CodedRefusal.BadRequest(SdvErrorCode.INT_008,
                        "Required metadata field '" + definition.fieldKey() + "' is missing.",
                        "field=" + definition.fieldKey() + " missing");
            }
        }

        supplied.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> MetadataValueValidator.validate(byKey.get(entry.getKey()), entry.getValue()));
    }

    /**
     * Task 0024, part 2: a delivery may set a record's first level or raise it, never lower it. A lower or equal
     * value is ignored and audited ("record level kept at ..."), not an error. The row is locked, so two deliveries
     * for one record can't both raise it from the same starting level.
     */
    private void applyRecordLevel(UUID tenantId, UUID sourceId, UUID submissionId, UUID recordId,
                                  SensitivityLevel requested, String sourceSubject, String correlationId, Instant now) {
        String where = "{\"sourceId\":\"" + sourceId + "\",\"submissionId\":\"" + submissionId + "\"";
        UUID newId = UUID.randomUUID();
        if (recordLevels.insertIfAbsent(new RecordSensitivityRepository.NewRecordLevel(
                newId, tenantId, sourceId, recordId, requested, null, "SOURCE", now))) {
            auditRecordLevel(tenantId, sourceSubject, "RECORD_SENSITIVITY_SET", newId, now, correlationId,
                    where + ",\"level\":\"" + requested.name() + "\",\"setBy\":\"SOURCE\"}");
            return;
        }
        RecordSensitivityRepository.StoredRecordLevel stored = recordLevels.lockByKey(tenantId, sourceId, recordId)
                .orElseThrow(() -> new InternalCheckFailed("The record's sensitivity level could not be read."));
        if (requested.compareTo(stored.level()) > 0) {
            recordLevels.raise(tenantId, stored.recordLevelId(), requested, "SOURCE", now);
            auditRecordLevel(tenantId, sourceSubject, "RECORD_SENSITIVITY_RAISED", stored.recordLevelId(), now,
                    correlationId, where + ",\"from\":\"" + stored.level().name() + "\",\"to\":\"" + requested.name()
                            + "\",\"setBy\":\"SOURCE\"}");
        } else {
            auditRecordLevel(tenantId, sourceSubject, "RECORD_SENSITIVITY_KEPT", stored.recordLevelId(), now,
                    correlationId, where + ",\"level\":\"" + stored.level().name() + "\",\"requested\":\""
                            + requested.name() + "\",\"note\":\"record level kept at " + stored.level().name() + "\"}");
        }
    }

    private void auditRecordLevel(UUID tenantId, String sourceSubject, String action, UUID recordLevelId,
                                  Instant now, String correlationId, String details) {
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "SOURCE", sourceSubject, action,
                "RECORD_SENSITIVITY", recordLevelId.toString(), now, correlationId, AuditOutcome.SUCCESS, details));
    }

    private static void ensureSameCreateRequest(Submission existing, CanonicalRequest request) {
        if (existing.createRequestSha256() == null) {
            throw new CodedRefusal.Conflict(SdvErrorCode.INT_011,
                    "The source submission identity already exists without a comparable create fingerprint.",
                    "submissionId=" + existing.submissionId() + " has no fingerprint");
        }
        if (existing.createRequestSha256().equals(request.requestSha256())) return;
        // Task 0024, 4a: when the record level is the only difference, say so with its own code. The fingerprint is
        // tried with each other possible level (or none); one matching means everything else is the same.
        List<SensitivityLevel> others = new ArrayList<>(java.util.Arrays.asList(SensitivityLevel.values()));
        others.add(null);
        others.remove(request.recordSensitivityLevel());
        for (SensitivityLevel first : others) {
            if (existing.createRequestSha256().equals(requestFingerprint(request.sourceSubmissionId(),
                    request.expectedDocumentCount(), request.expectedSourceDocumentIds(), request.metadata(), first))) {
                throw new com.sdv.platform.domain.error.CodedRefusal.Conflict(
                        com.sdv.platform.domain.error.SdvErrorCode.SEN_004,
                        "sourceSubmissionId already exists with " + (first == null
                                ? "no " + RECORD_SENSITIVITY_FIELD + "; leave it out"
                                : RECORD_SENSITIVITY_FIELD + " " + first.name() + "; send the same value")
                                + " when retrying.");
            }
        }
        throw new CodedRefusal.Conflict(SdvErrorCode.INT_011,
                "sourceSubmissionId already exists with a different submission create request.",
                "submissionId=" + existing.submissionId());
    }

    private static String requestFingerprint(String sourceSubmissionId,
                                             int expectedCount,
                                             List<String> manifest,
                                             Map<String, String> metadata,
                                             SensitivityLevel recordLevel) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "sourceSubmissionId", sourceSubmissionId);
            update(digest, "expectedCount", Integer.toString(expectedCount));
            List<String> sortedManifest = manifest.stream().sorted().toList();
            for (String id : sortedManifest) update(digest, "manifest", id);
            metadata.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        update(digest, "metadataKey", entry.getKey());
                        update(digest, "metadataValue", entry.getValue());
                    });
            // Only when sent, so every submission created before task 0024 keeps its fingerprint. A retry with a
            // different record level is then a different request (409), like a document retry with a different level.
            if (recordLevel != null) update(digest, "recordSensitivityLevel", recordLevel.name());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new InternalCheckFailed("SHA-256 is unavailable.", ex);
        }
    }

    private static void update(MessageDigest digest, String label, String value) {
        byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(intBytes(labelBytes.length));
        digest.update(labelBytes);
        digest.update(intBytes(valueBytes.length));
        digest.update(valueBytes);
    }

    private static byte[] intBytes(int value) {
        return new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
    }

    private static String requireText(String value, String field, int maxLength) {
        return requireText(value, field, maxLength, SdvErrorCode.INT_006);
    }

    /** Task 0039: a required text, refused with {@code code} (INT-006 for the request, INT-007 list, INT-008 field). */
    private static String requireText(String value, String field, int maxLength, SdvErrorCode code) {
        if (value == null || value.isBlank()) {
            throw new CodedRefusal.BadRequest(code, field + " is required.", "field=" + field + " missing");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new CodedRefusal.BadRequest(code, field + " exceeds " + maxLength + " characters.",
                    "field=" + field + " too long");
        }
        return trimmed;
    }


    private static final class EmptyDocumentIntakeRepository implements DocumentIntakeRepository {
        @Override public Submission lockSubmission(UUID tenantId, UUID sourceId, UUID submissionId) {
            throw new UnsupportedOperationException();
        }
        @Override public java.util.Optional<Document> findDocumentById(UUID tenantId, UUID sourceId, UUID submissionId, UUID documentId) {
            return java.util.Optional.empty();
        }
        @Override public java.util.Optional<Document> findDocumentByIdForTenant(UUID tenantId, UUID documentId) {
            return java.util.Optional.empty();
        }
        @Override public java.util.Optional<Document> findBySourceDocumentId(UUID tenantId, UUID sourceId, UUID submissionId, String sourceDocumentId) {
            return java.util.Optional.empty();
        }
        @Override public java.util.Optional<Document> findByProducerIdempotencyKey(UUID tenantId, UUID sourceId, UUID submissionId, String producerIdempotencyKey) {
            return java.util.Optional.empty();
        }
        @Override public List<Document> findDocuments(UUID tenantId, UUID sourceId, UUID submissionId) {
            return List.of();
        }
        @Override public long countDocuments(UUID tenantId, UUID submissionId) { return 0L; }
        @Override public long sumContentBytes(UUID tenantId, UUID submissionId) { return 0L; }
        @Override public long countReceived(UUID tenantId, UUID submissionId) { return 0L; }
        @Override public java.util.Optional<Document> findDuplicateOf(UUID tenantId, UUID sourceId, java.util.Collection<UUID> submissionIds, String contentSha256) { return java.util.Optional.empty(); }
        @Override public java.util.Optional<Document> findCorrectedOriginalOf(UUID tenantId, UUID sourceId, java.util.Collection<UUID> submissionIds, String contentSha256) { return java.util.Optional.empty(); }
        @Override public void lockDuplicateCheck(UUID tenantId, UUID sourceId, UUID recordId, String contentSha256) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<DuplicateReceipt> findDuplicateReceipt(UUID tenantId, UUID submissionId, String sourceDocumentId, String producerIdempotencyKey) { return java.util.Optional.empty(); }
        @Override public void insertDuplicateReceipt(DuplicateReceipt receipt) { throw new UnsupportedOperationException(); }
        @Override public void insertUncorrectedCopyNotice(UUID noticeId, UUID tenantId, UUID sourceId, UUID submissionId,
                                                         UUID auditEventId, java.time.Instant createdAt) {
            throw new UnsupportedOperationException();
        }
        @Override public void insertDocument(Document document) { throw new UnsupportedOperationException(); }
        @Override public void insertStorageRef(com.sdv.platform.domain.document.DocumentStorageRef storageRef) { throw new UnsupportedOperationException(); }
        @Override public void insertCryptoMetadata(com.sdv.platform.domain.document.DocumentCryptoMetadata cryptoMetadata) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<com.sdv.platform.domain.document.DocumentStorageRef> findStorageRef(UUID tenantId, UUID documentId) { return java.util.Optional.empty(); }
        @Override public java.util.Optional<com.sdv.platform.domain.document.DocumentCryptoMetadata> findCryptoMetadata(UUID tenantId, UUID documentId) { return java.util.Optional.empty(); }
        @Override public void markExpectedDocumentReceived(UUID tenantId, UUID submissionId, String sourceDocumentId, UUID documentId, Instant receivedAt) { }
        @Override public void updateSubmissionAfterReceipt(UUID tenantId, UUID sourceId, UUID submissionId, int expectedDocumentCount, Instant activityAt) { }
        @Override public boolean transitionDocumentToProcessing(UUID tenantId, UUID sourceId, UUID submissionId, UUID documentId, Instant processingAt) { return false; }
        @Override public void markDocumentAvailable(UUID tenantId, UUID sourceId, UUID submissionId, UUID documentId, Instant availableAt, Integer pageCount) { }
        @Override public void markDocumentProcessingFailed(UUID tenantId, UUID sourceId, UUID submissionId, UUID documentId, String errorCode) { }
        @Override public void markSubmissionIncompleteIfDue(UUID tenantId, UUID sourceId, UUID submissionId, Instant cutoff) { }
    }

    /** For the test-support constructors only: no record levels can be stored, so one sent fails (fail closed). */
    private static final class NoRecordLevelRepository implements RecordSensitivityRepository {
        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("Record sensitivity levels are not available here.");
        }
        @Override public boolean insertIfAbsent(NewRecordLevel level) { throw unavailable(); }
        @Override public java.util.Optional<StoredRecordLevel> lockByKey(UUID tenantId, UUID sourceId, UUID recordId) {
            throw unavailable();
        }
        @Override public void raise(UUID tenantId, UUID recordLevelId, SensitivityLevel level, String setBy, Instant updatedAt) {
            throw unavailable();
        }
        @Override public long countForSource(UUID tenantId, UUID sourceId) { throw unavailable(); }
    }

    private record CanonicalRequest(
            String sourceSubmissionId,
            int expectedDocumentCount,
            List<String> expectedSourceDocumentIds,
            Map<String, String> metadata,
            SensitivityLevel recordSensitivityLevel,
            String requestSha256) { }
}
