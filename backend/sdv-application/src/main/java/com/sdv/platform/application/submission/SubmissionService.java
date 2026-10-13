package com.sdv.platform.application.submission;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.SubmissionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.BusinessMetadataValue;
import com.sdv.platform.domain.document.Document;
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
    private final AuditEventPort audit;
    private final Clock clock;

    @Autowired
    public SubmissionService(TenantRepository tenants,
                             IngestionSourceRepository sources,
                             MetadataFieldDefinitionRepository definitions,
                             SubmissionRepository submissions,
                             DocumentIntakeRepository documents,
                             AuditEventPort audit) {
        this(tenants, sources, definitions, submissions, documents, audit, Clock.systemUTC());
    }

    SubmissionService(TenantRepository tenants,
                      IngestionSourceRepository sources,
                      MetadataFieldDefinitionRepository definitions,
                      SubmissionRepository submissions,
                      DocumentIntakeRepository documents,
                      AuditEventPort audit,
                      Clock clock) {
        this.tenants = tenants;
        this.sources = sources;
        this.definitions = definitions;
        this.submissions = submissions;
        this.documents = documents;
        this.audit = audit;
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
        this(tenants, sources, definitions, submissions, new EmptyDocumentIntakeRepository(), audit, clock);
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
            ensureSameCreateRequest(existing.get(), canonical.requestSha256());
            return details(existing.get(), true);
        }

        int maxDocuments = effectiveMaxDocuments(tenant, source);
        if (canonical.expectedDocumentCount() > maxDocuments) {
            throw new IllegalArgumentException("expectedDocumentCount exceeds the configured submission limit of " + maxDocuments + ".");
        }
        if (canonical.expectedSourceDocumentIds().size() > canonical.expectedDocumentCount()) {
            throw new IllegalArgumentException("Expected manifest entries cannot exceed expectedDocumentCount.");
        }

        List<MetadataFieldDefinition> currentDefinitions = definitions.findCurrent(tenantId, sourceId);
        List<BusinessMetadataValue> metadataValues = validateAndBuildMetadata(
                tenantId, sourceId, currentDefinitions, canonical.metadata(), null);

        Instant now = clock.instant();
        Submission candidate = new Submission(
                UUID.randomUUID(), tenantId, sourceId, canonical.sourceSubmissionId(),
                canonical.expectedDocumentCount(), canonical.requestSha256(), SubmissionStatus.OPEN,
                now, now, null);

        SubmissionRepository.InsertResult insertResult = submissions.insertIfAbsent(candidate);
        if (!insertResult.inserted()) {
            ensureSameCreateRequest(insertResult.submission(), canonical.requestSha256());
            return details(insertResult.submission(), true);
        }

        UUID submissionId = candidate.submissionId();
        List<SubmissionExpectedDocument> expectedDocuments = canonical.expectedSourceDocumentIds().stream()
                .map(sourceDocumentId -> new SubmissionExpectedDocument(
                        UUID.randomUUID(), tenantId, submissionId, sourceDocumentId,
                        ExpectedDocumentState.EXPECTED, null, now, null))
                .toList();

        // Rebind metadata values to the generated submission identity only after this create won the
        // database-level idempotency race.
        List<BusinessMetadataValue> boundMetadata = metadataValues.stream()
                .map(value -> new BusinessMetadataValue(
                        value.metadataValueId(), value.tenantId(), submissionId, value.sourceId(),
                        value.definitionId(), value.fieldKey(), value.definitionVersion(),
                        value.storedValue(), value.normalizedValue()))
                .toList();

        submissions.insertExpectedDocuments(expectedDocuments);
        submissions.insertBusinessMetadata(boundMetadata);
        audit.append(new AuditEvent(
                UUID.randomUUID(), tenantId, "SOURCE", sourceSubject,
                "SUBMISSION_CREATED", "SUBMISSION", submissionId.toString(), now, correlationId,
                AuditOutcome.SUCCESS,
                "{\"sourceId\":\"" + sourceId + "\",\"expectedDocumentCount\":"
                        + canonical.expectedDocumentCount() + ",\"manifestCount\":" + expectedDocuments.size()
                        + ",\"metadataFieldCount\":" + boundMetadata.size() + "}"));

        return details(candidate, false);
    }

    public SubmissionDetails get(UUID tenantId, UUID sourceId, UUID submissionId) {
        requireActiveTenant(tenantId);
        requireActiveSource(tenantId, sourceId);
        Submission submission = submissions.findById(tenantId, sourceId, submissionId)
                .orElseThrow(() -> new NoSuchElementException("Submission not found."));
        return details(submission, false);
    }

    public SubmissionDetails getBySourceSubmissionId(UUID tenantId, UUID sourceId, String sourceSubmissionId) {
        requireActiveTenant(tenantId);
        requireActiveSource(tenantId, sourceId);
        String normalized = requireText(sourceSubmissionId, "sourceSubmissionId", 200);
        Submission submission = submissions.findBySourceIdentity(tenantId, sourceId, normalized)
                .orElseThrow(() -> new NoSuchElementException("Submission not found."));
        return details(submission, false);
    }

    private SubmissionDetails details(Submission submission, boolean replay) {
        Submission effective = refreshIncompleteStatus(submission);
        List<SubmissionExpectedDocument> expectedDocuments =
                submissions.findExpectedDocuments(effective.tenantId(), effective.submissionId());
        List<Document> documentList =
                documents.findDocuments(effective.tenantId(), effective.sourceId(), effective.submissionId());

        SubmissionProgress progress = null;
        if (effective.status() != SubmissionStatus.COMPLETE) {
            int received = documentList.size();
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
                submissions.findBusinessMetadata(effective.tenantId(), effective.sourceId(), effective.submissionId()),
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
                .orElseThrow(() -> new NoSuchElementException("Submission not found."));
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

    private static int effectiveMaxDocuments(Tenant tenant, IngestionSource source) {
        int tenantMax = tenant.guardrails().maxDocumentsPerSubmission();
        Integer sourceMax = source.limits() == null ? null : source.limits().maxDocumentsPerSubmission();
        return sourceMax == null ? tenantMax : Math.min(tenantMax, sourceMax);
    }

    private static CanonicalRequest canonicalize(CreateSubmissionCommand command) {
        if (command == null) throw new IllegalArgumentException("Submission request is required.");
        String sourceSubmissionId = requireText(command.sourceSubmissionId(), "sourceSubmissionId", 200);
        if (command.expectedDocumentCount() < 1) {
            throw new IllegalArgumentException("expectedDocumentCount must be at least 1.");
        }

        List<String> manifest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (command.expectedSourceDocumentIds() != null) {
            for (String rawId : command.expectedSourceDocumentIds()) {
                String id = requireText(rawId, "sourceDocumentId", 200);
                if (!seen.add(id)) throw new IllegalArgumentException("Expected manifest contains duplicate sourceDocumentId '" + id + "'.");
                manifest.add(id);
            }
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        if (command.metadata() != null) {
            command.metadata().forEach((key, value) -> {
                String fieldKey = requireText(key, "metadata field key", 100);
                if (value == null) throw new IllegalArgumentException("Metadata field '" + fieldKey + "' cannot be null.");
                metadata.put(fieldKey, value);
            });
        }

        String requestSha = requestFingerprint(sourceSubmissionId, command.expectedDocumentCount(), manifest, metadata);
        return new CanonicalRequest(sourceSubmissionId, command.expectedDocumentCount(), List.copyOf(manifest), Map.copyOf(metadata), requestSha);
    }

    private static List<BusinessMetadataValue> validateAndBuildMetadata(
            UUID tenantId,
            UUID sourceId,
            List<MetadataFieldDefinition> definitions,
            Map<String, String> supplied,
            UUID submissionId) {
        Map<String, MetadataFieldDefinition> byKey = new HashMap<>();
        definitions.forEach(definition -> byKey.put(definition.fieldKey(), definition));

        for (String suppliedKey : supplied.keySet()) {
            if (!byKey.containsKey(suppliedKey)) {
                throw new IllegalArgumentException("Metadata field '" + suppliedKey + "' is not configured for this ingestion source.");
            }
        }
        for (MetadataFieldDefinition definition : definitions) {
            if (definition.required() && (!supplied.containsKey(definition.fieldKey()) || supplied.get(definition.fieldKey()).isBlank())) {
                throw new IllegalArgumentException("Required metadata field '" + definition.fieldKey() + "' is missing.");
            }
        }

        List<BusinessMetadataValue> result = new ArrayList<>();
        supplied.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    MetadataFieldDefinition definition = byKey.get(entry.getKey());
                    MetadataValueValidator.ValidatedValue validated = MetadataValueValidator.validate(definition, entry.getValue());
                    String searchValue = definition.searchable() || definition.businessIdentifier()
                            ? validated.normalizedValue() : null;
                    result.add(new BusinessMetadataValue(
                            UUID.randomUUID(), tenantId, submissionId, sourceId,
                            definition.definitionId(), definition.fieldKey(), definition.version(),
                            validated.storedValue(), searchValue));
                });
        return List.copyOf(result);
    }

    private static void ensureSameCreateRequest(Submission existing, String requestSha256) {
        if (existing.createRequestSha256() == null) {
            throw new IllegalStateException("The source submission identity already exists without a comparable create fingerprint.");
        }
        if (!existing.createRequestSha256().equals(requestSha256)) {
            throw new IllegalStateException("sourceSubmissionId already exists with a different submission create request.");
        }
    }

    private static String requestFingerprint(String sourceSubmissionId,
                                             int expectedCount,
                                             List<String> manifest,
                                             Map<String, String> metadata) {
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
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
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
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required.");
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters.");
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
        @Override public boolean hasExactDuplicateContent(UUID tenantId, UUID sourceId, String contentSha256, UUID excludingDocumentId) { return false; }
        @Override public void insertDocument(Document document) { throw new UnsupportedOperationException(); }
        @Override public void insertStorageRef(com.sdv.platform.domain.document.DocumentStorageRef storageRef) { throw new UnsupportedOperationException(); }
        @Override public void insertCryptoMetadata(com.sdv.platform.domain.document.DocumentCryptoMetadata cryptoMetadata) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<com.sdv.platform.domain.document.DocumentStorageRef> findStorageRef(UUID tenantId, UUID documentId) { return java.util.Optional.empty(); }
        @Override public java.util.Optional<com.sdv.platform.domain.document.DocumentCryptoMetadata> findCryptoMetadata(UUID tenantId, UUID documentId) { return java.util.Optional.empty(); }
        @Override public void markExpectedDocumentReceived(UUID tenantId, UUID submissionId, String sourceDocumentId, UUID documentId, Instant receivedAt) { }
        @Override public void updateSubmissionAfterReceipt(UUID tenantId, UUID sourceId, UUID submissionId, int expectedDocumentCount, Instant activityAt) { }
        @Override public boolean transitionDocumentToProcessing(UUID tenantId, UUID sourceId, UUID submissionId, UUID documentId, Instant processingAt) { return false; }
        @Override public void markDocumentAvailable(UUID tenantId, UUID sourceId, UUID submissionId, UUID documentId, Instant availableAt) { }
        @Override public void markDocumentProcessingFailed(UUID tenantId, UUID sourceId, UUID submissionId, UUID documentId, String errorCode) { }
        @Override public void markSubmissionIncompleteIfDue(UUID tenantId, UUID sourceId, UUID submissionId, Instant cutoff) { }
    }

    private record CanonicalRequest(
            String sourceSubmissionId,
            int expectedDocumentCount,
            List<String> expectedSourceDocumentIds,
            Map<String, String> metadata,
            String requestSha256) { }
}
