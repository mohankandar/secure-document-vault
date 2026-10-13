package com.sdv.platform.application.business;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.AvailableDocumentSnapshot;
import com.sdv.platform.application.port.BusinessRecordRepository.DocumentCount;
import com.sdv.platform.application.port.BusinessRecordRepository.ExpectedDocumentSnapshot;
import com.sdv.platform.application.port.BusinessRecordRepository.IdentifierCriterion;
import com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot;
import com.sdv.platform.application.port.BusinessRecordRepository.SearchCriterion;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionStatus;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BusinessExperienceService {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 50;
    private static final int MAX_CRITERIA = 10;
    private static final int MAX_QUERY_VALUE_LENGTH = 500;

    private final TenantRepository tenants;
    private final IngestionSourceRepository sources;
    private final MetadataFieldDefinitionRepository definitions;
    private final BusinessRecordRepository records;
    private final AuditEventPort audit;
    private final BusinessRecordRefCodec refs = new BusinessRecordRefCodec();
    private final Clock clock;

    @Autowired
    public BusinessExperienceService(
            TenantRepository tenants,
            IngestionSourceRepository sources,
            MetadataFieldDefinitionRepository definitions,
            BusinessRecordRepository records,
            AuditEventPort audit) {
        this(tenants, sources, definitions, records, audit, Clock.systemUTC());
    }

    BusinessExperienceService(
            TenantRepository tenants,
            IngestionSourceRepository sources,
            MetadataFieldDefinitionRepository definitions,
            BusinessRecordRepository records,
            AuditEventPort audit,
            Clock clock) {
        this.tenants = tenants;
        this.sources = sources;
        this.definitions = definitions;
        this.records = records;
        this.audit = audit;
        this.clock = clock;
    }

    public BusinessExperienceView.SearchSchema searchSchema(UUID tenantId) {
        requireActiveTenant(tenantId);
        List<BusinessExperienceView.SourceSchema> sourceSchemas = sources.findAll(tenantId).stream()
                .sorted(Comparator.comparing(IngestionSource::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(IngestionSource::sourceKey))
                .map(source -> new BusinessExperienceView.SourceSchema(
                        source.sourceId(),
                        source.sourceKey(),
                        source.displayName(),
                        definitions.findCurrent(tenantId, source.sourceId()).stream()
                                .filter(MetadataFieldDefinition::searchable)
                                .sorted(Comparator.comparing(MetadataFieldDefinition::displayLabel, String.CASE_INSENSITIVE_ORDER)
                                        .thenComparing(MetadataFieldDefinition::fieldKey))
                                .map(field -> new BusinessExperienceView.SearchField(
                                        field.fieldKey(),
                                        field.displayLabel(),
                                        field.fieldType(),
                                        field.businessIdentifier(),
                                        field.normalization(),
                                        field.searchMode()))
                                .toList()))
                .toList();
        return new BusinessExperienceView.SearchSchema(sourceSchemas);
    }

    public BusinessExperienceView.SearchResponse search(
            UUID tenantId,
            UUID sourceId,
            Map<String, String> requestedCriteria,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            Integer requestedLimit,
            String actorId,
            String correlationId) {
        return search(
                tenantId, sourceId, requestedCriteria,
                createdFrom, createdTo, updatedFrom, updatedTo,
                1, requestedLimit, actorId, correlationId);
    }

    public BusinessExperienceView.SearchResponse search(
            UUID tenantId,
            UUID sourceId,
            Map<String, String> requestedCriteria,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            Integer requestedPage,
            Integer requestedPageSize,
            String actorId,
            String correlationId) {
        requireActiveTenant(tenantId);
        IngestionSource source = requireSource(tenantId, sourceId);

        int page = requestedPage == null ? 1 : requestedPage;
        int pageSize = requestedPageSize == null ? DEFAULT_LIMIT : requestedPageSize;
        if (page < 1) {
            throw new IllegalArgumentException("Search page must be at least 1.");
        }
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw new IllegalArgumentException("Search page size must be between 1 and " + MAX_LIMIT + ".");
        }

        Map<String, String> safeCriteria = requestedCriteria == null ? Map.of() : requestedCriteria;
        if (safeCriteria.isEmpty()
                && createdFrom == null && createdTo == null && updatedFrom == null && updatedTo == null) {
            throw new IllegalArgumentException("At least one configured metadata field or common date filter is required.");
        }
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new IllegalArgumentException("dateFrom cannot be after dateTo.");
        }
        if (updatedFrom != null && updatedTo != null && updatedFrom.isAfter(updatedTo)) {
            throw new IllegalArgumentException("lastUpdatedFrom cannot be after lastUpdatedTo.");
        }
        if (safeCriteria.size() > MAX_CRITERIA) {
            throw new IllegalArgumentException("At most " + MAX_CRITERIA + " search fields may be combined.");
        }

        Map<String, MetadataFieldDefinition> current = definitions.findCurrent(tenantId, sourceId).stream()
                .collect(Collectors.toMap(MetadataFieldDefinition::fieldKey, Function.identity()));

        List<SearchCriterion> criteria = safeCriteria.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> buildCriterion(tenantId, sourceId, current, entry.getKey(), entry.getValue()))
                .toList();

        int firstIndex = Math.multiplyExact(page - 1, pageSize);
        int requiredCandidateCount = Math.addExact(firstIndex, pageSize + 1);
        int scanOffset = 0;
        int scanBatchSize = Math.max(200, pageSize * 4);
        boolean exhausted = false;

        LinkedHashMap<String, BusinessRecordRefCodec.RecordIdentity> candidates = new LinkedHashMap<>();
        while (candidates.size() < requiredCandidateCount && !exhausted) {
            List<Submission> matching = records.findMatchingSubmissions(
                    tenantId, sourceId, criteria,
                    createdFrom, createdTo, updatedFrom, updatedTo,
                    scanOffset, scanBatchSize);

            if (matching.isEmpty()) {
                exhausted = true;
                break;
            }

            List<UUID> matchingIds = matching.stream().map(Submission::submissionId).toList();
            Map<UUID, List<MetadataSnapshot>> matchedMetadata = groupMetadata(
                    records.findMetadataSnapshots(tenantId, sourceId, matchingIds));

            for (Submission submission : matching) {
                BusinessRecordRefCodec.RecordIdentity identity = identityFor(
                        sourceId,
                        submission.submissionId(),
                        matchedMetadata.getOrDefault(submission.submissionId(), List.of()));
                candidates.putIfAbsent(identity.canonicalKey(), identity);
            }

            scanOffset += matching.size();
            exhausted = matching.size() < scanBatchSize;
        }

        List<BusinessRecordRefCodec.RecordIdentity> ordered = new ArrayList<>(candidates.values());
        if (firstIndex >= ordered.size()) {
            appendSearchAudit(tenantId, actorId, sourceId, criteria.size(), 0, correlationId);
            return new BusinessExperienceView.SearchResponse(
                    sourceId, List.of(), page, pageSize, false, false);
        }

        int pageEndExclusive = Math.min(firstIndex + pageSize, ordered.size());
        List<BusinessExperienceView.RecordSummary> results = new ArrayList<>();
        for (BusinessRecordRefCodec.RecordIdentity identity : ordered.subList(firstIndex, pageEndExclusive)) {
            RecordAggregate aggregate = aggregate(tenantId, source, identity);
            if (!aggregate.submissions().isEmpty()) {
                results.add(summary(aggregate));
            }
        }

        boolean hasMore = ordered.size() > firstIndex + pageSize || !exhausted;
        appendSearchAudit(tenantId, actorId, sourceId, criteria.size(), results.size(), correlationId);
        return new BusinessExperienceView.SearchResponse(
                sourceId, results, page, pageSize, hasMore, hasMore);
    }

    public BusinessExperienceView.RecordView record(
            UUID tenantId,
            String recordRef,
            String actorId,
            String correlationId) {
        requireActiveTenant(tenantId);
        BusinessRecordRefCodec.RecordIdentity identity = refs.decode(recordRef);
        IngestionSource source = requireSource(tenantId, identity.sourceId());

        RecordAggregate aggregate = aggregate(tenantId, source, identity);
        if (aggregate.submissions().isEmpty()) {
            throw new NoSuchElementException("Business record not found.");
        }

        BusinessExperienceView.RecordView view = recordView(aggregate);
        audit.append(new AuditEvent(
                UUID.randomUUID(), tenantId, "USER", actorId,
                "BUSINESS_RECORD_OPENED", "BUSINESS_RECORD", sha256(recordRef),
                clock.instant(), correlationId, AuditOutcome.SUCCESS,
                "{\"sourceId\":\"" + source.sourceId() + "\",\"submissionCount\":"
                        + view.submissionCount() + ",\"availableDocumentCount\":" + view.documents().size() + "}"));
        return view;
    }

    private SearchCriterion buildCriterion(
            UUID tenantId,
            UUID sourceId,
            Map<String, MetadataFieldDefinition> current,
            String fieldKey,
            String rawValue) {
        MetadataFieldDefinition field = current.get(fieldKey);
        if (field == null || !field.searchable()) {
            throw new IllegalArgumentException(
                    "Search field '" + fieldKey + "' is not currently exposed for this source.");
        }
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("Search value for '" + fieldKey + "' is required.");
        }
        if (rawValue.length() > MAX_QUERY_VALUE_LENGTH) {
            throw new IllegalArgumentException("Search value for '" + fieldKey + "' is too long.");
        }

        LinkedHashSet<String> variants = new LinkedHashSet<>();
        definitions.findHistory(tenantId, sourceId, fieldKey).stream()
                .map(MetadataFieldDefinition::normalization)
                .distinct()
                .forEach(normalization -> {
                    String normalized = SearchValueNormalizer.normalize(normalization, rawValue);
                    if (!normalized.isBlank()) variants.add(normalized);
                });
        String currentNormalized = SearchValueNormalizer.normalize(field.normalization(), rawValue);
        if (!currentNormalized.isBlank()) variants.add(currentNormalized);

        if (variants.isEmpty()) {
            throw new IllegalArgumentException(
                    "Search value for '" + fieldKey + "' is empty after configured normalization.");
        }
        return new SearchCriterion(fieldKey, field.searchMode(), List.copyOf(variants));
    }

    private RecordAggregate aggregate(
            UUID tenantId,
            IngestionSource source,
            BusinessRecordRefCodec.RecordIdentity identity) {
        List<UUID> memberIds;
        if (identity.fallbackSubmissionId() != null) {
            memberIds = List.of(identity.fallbackSubmissionId());
        } else {
            List<IdentifierCriterion> identifiers = identity.identifiers().stream()
                    .map(part -> new IdentifierCriterion(part.fieldKey(), part.normalizedValue()))
                    .toList();
            memberIds = records.findSubmissionIdsByRecordIdentity(
                    tenantId, source.sourceId(), identifiers);
        }
        if (memberIds.isEmpty()) {
            return new RecordAggregate(source, identity, List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        List<Submission> submissions = records.findSubmissionsByIds(
                tenantId, source.sourceId(), memberIds);
        if (submissions.isEmpty()) {
            return new RecordAggregate(source, identity, List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        List<UUID> ids = submissions.stream().map(Submission::submissionId).toList();
        List<MetadataSnapshot> metadata = records.findMetadataSnapshots(tenantId, source.sourceId(), ids);
        List<AvailableDocumentSnapshot> documents = records.findAvailableDocuments(
                tenantId, source.sourceId(), ids);
        List<ExpectedDocumentSnapshot> expected = records.findExpectedDocuments(tenantId, ids);
        Map<UUID, Integer> documentCounts = records.countDocuments(tenantId, source.sourceId(), ids).stream()
                .collect(Collectors.toMap(DocumentCount::submissionId, DocumentCount::documentCount));

        return new RecordAggregate(source, identity, submissions, metadata, documents, expected, documentCounts);
    }

    private BusinessExperienceView.RecordSummary summary(RecordAggregate aggregate) {
        List<BusinessExperienceView.FieldValue> latestMetadata = latestMetadata(aggregate);
        List<BusinessExperienceView.FieldValue> identifiers = displayIdentifiers(aggregate, latestMetadata);
        List<BusinessExperienceView.FieldValue> preview = latestMetadata.stream()
                .filter(value -> !value.businessIdentifier())
                .limit(4)
                .toList();
        boolean incomplete = aggregate.submissions().stream()
                .anyMatch(submission -> submission.status() != SubmissionStatus.COMPLETE);

        return new BusinessExperienceView.RecordSummary(
                refs.encode(aggregate.identity()),
                identifiers,
                preview,
                aggregate.submissions().size(),
                aggregate.documents().size(),
                aggregate.submissions().getFirst().createdAt(),
                incomplete);
    }

    private BusinessExperienceView.RecordView recordView(RecordAggregate aggregate) {
        List<BusinessExperienceView.FieldValue> metadata = latestMetadata(aggregate);
        List<BusinessExperienceView.FieldValue> identifiers = displayIdentifiers(aggregate, metadata);
        List<BusinessExperienceView.DocumentSummary> documents = aggregate.documents().stream()
                .map(document -> new BusinessExperienceView.DocumentSummary(
                        document.documentId(), document.submissionId(), document.sourceDocumentId(),
                        document.originalFilename(), document.ingestedAt(), document.availableAt(), document.expiresAt()))
                .toList();

        Map<UUID, List<ExpectedDocumentSnapshot>> expectedBySubmission = aggregate.expected().stream()
                .collect(Collectors.groupingBy(ExpectedDocumentSnapshot::submissionId));

        List<BusinessExperienceView.IncompleteSubmission> incomplete = aggregate.submissions().stream()
                .filter(submission -> submission.status() != SubmissionStatus.COMPLETE)
                .map(submission -> {
                    int received = aggregate.documentCounts().getOrDefault(submission.submissionId(), 0);
                    int missing = Math.max(0, submission.expectedDocumentCount() - received);
                    List<String> missingIds = expectedBySubmission
                            .getOrDefault(submission.submissionId(), List.of()).stream()
                            .filter(item -> "EXPECTED".equals(item.state()))
                            .map(ExpectedDocumentSnapshot::sourceDocumentId)
                            .sorted()
                            .toList();
                    return new BusinessExperienceView.IncompleteSubmission(
                            submission.submissionId(), submission.status().name(),
                            submission.expectedDocumentCount(), received, missing, missingIds);
                })
                .toList();

        BusinessExperienceView.IncompleteAttention attention =
                incomplete.isEmpty() ? null : new BusinessExperienceView.IncompleteAttention(incomplete);

        return new BusinessExperienceView.RecordView(
                refs.encode(aggregate.identity()),
                aggregate.source().sourceId(),
                aggregate.source().sourceKey(),
                aggregate.source().displayName(),
                identifiers,
                metadata,
                aggregate.submissions().size(),
                aggregate.submissions().getFirst().createdAt(),
                documents,
                attention);
    }

    private static List<BusinessExperienceView.FieldValue> latestMetadata(RecordAggregate aggregate) {
        Map<UUID, List<MetadataSnapshot>> bySubmission = groupMetadata(aggregate.metadata());
        LinkedHashMap<String, BusinessExperienceView.FieldValue> latest = new LinkedHashMap<>();

        for (Submission submission : aggregate.submissions()) {
            bySubmission.getOrDefault(submission.submissionId(), List.of()).stream()
                    .sorted(Comparator.comparing(MetadataSnapshot::fieldKey))
                    .forEach(value -> latest.putIfAbsent(
                            value.fieldKey(),
                            new BusinessExperienceView.FieldValue(
                                    value.fieldKey(), value.displayLabel(),
                                    value.storedValue(), value.businessIdentifier())));
        }
        return latest.values().stream()
                .sorted(Comparator.comparing(BusinessExperienceView.FieldValue::displayLabel, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(BusinessExperienceView.FieldValue::fieldKey))
                .toList();
    }

    private static List<BusinessExperienceView.FieldValue> displayIdentifiers(
            RecordAggregate aggregate,
            List<BusinessExperienceView.FieldValue> latestMetadata) {
        if (aggregate.identity().fallbackSubmissionId() != null) return List.of();
        Map<String, BusinessExperienceView.FieldValue> latestByKey = latestMetadata.stream()
                .collect(Collectors.toMap(BusinessExperienceView.FieldValue::fieldKey, Function.identity(), (a, b) -> a));

        return aggregate.identity().identifiers().stream()
                .map(part -> {
                    BusinessExperienceView.FieldValue display = latestByKey.get(part.fieldKey());
                    return display != null
                            ? new BusinessExperienceView.FieldValue(
                                    display.fieldKey(), display.displayLabel(), display.value(), true)
                            : new BusinessExperienceView.FieldValue(
                                    part.fieldKey(), part.fieldKey(), part.normalizedValue(), true);
                })
                .toList();
    }

    private static BusinessRecordRefCodec.RecordIdentity identityFor(
            UUID sourceId,
            UUID submissionId,
            List<MetadataSnapshot> metadata) {
        List<BusinessRecordRefCodec.IdentifierPart> identifiers = metadata.stream()
                .filter(MetadataSnapshot::businessIdentifier)
                .map(value -> new BusinessRecordRefCodec.IdentifierPart(
                        value.fieldKey(), value.normalizedValue()))
                .filter(part -> part.normalizedValue() != null && !part.normalizedValue().isBlank())
                .sorted(Comparator.comparing(BusinessRecordRefCodec.IdentifierPart::fieldKey)
                        .thenComparing(BusinessRecordRefCodec.IdentifierPart::normalizedValue))
                .toList();

        return identifiers.isEmpty()
                ? new BusinessRecordRefCodec.RecordIdentity(sourceId, List.of(), submissionId)
                : new BusinessRecordRefCodec.RecordIdentity(sourceId, identifiers, null);
    }

    private static Map<UUID, List<MetadataSnapshot>> groupMetadata(List<MetadataSnapshot> metadata) {
        return metadata.stream().collect(Collectors.groupingBy(MetadataSnapshot::submissionId));
    }

    private void requireActiveTenant(UUID tenantId) {
        var tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found."));
        if (tenant.status() != TenantStatus.ACTIVE) {
            throw new IllegalStateException("Tenant is not active.");
        }
    }

    private IngestionSource requireSource(UUID tenantId, UUID sourceId) {
        return sources.findById(tenantId, sourceId)
                .orElseThrow(() -> new NoSuchElementException("Ingestion source not found."));
    }

    private void appendSearchAudit(
            UUID tenantId,
            String actorId,
            UUID sourceId,
            int criteriaCount,
            int resultCount,
            String correlationId) {
        audit.append(new AuditEvent(
                UUID.randomUUID(), tenantId, "USER", actorId,
                "BUSINESS_SEARCH", "INGESTION_SOURCE", sourceId.toString(),
                clock.instant(), correlationId, AuditOutcome.SUCCESS,
                "{\"criteriaCount\":" + criteriaCount + ",\"resultCount\":" + resultCount + "}"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private record RecordAggregate(
            IngestionSource source,
            BusinessRecordRefCodec.RecordIdentity identity,
            List<Submission> submissions,
            List<MetadataSnapshot> metadata,
            List<AvailableDocumentSnapshot> documents,
            List<ExpectedDocumentSnapshot> expected,
            Map<UUID, Integer> documentCounts) {
        RecordAggregate {
            submissions = submissions.stream()
                    .sorted(Comparator.comparing(Submission::createdAt).reversed()
                            .thenComparing(Submission::submissionId))
                    .toList();
            metadata = List.copyOf(metadata);
            documents = documents.stream()
                    .sorted(Comparator.comparing(AvailableDocumentSnapshot::availableAt,
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(AvailableDocumentSnapshot::documentId))
                    .toList();
            expected = List.copyOf(expected);
            documentCounts = Map.copyOf(documentCounts);
        }
    }
}
