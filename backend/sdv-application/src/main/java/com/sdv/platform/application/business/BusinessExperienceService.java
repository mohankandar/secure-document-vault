package com.sdv.platform.application.business;

import com.sdv.platform.application.access.ActiveTenant;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.QuarantineLookup;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.AnywhereCriterion;
import com.sdv.platform.application.port.BusinessRecordRepository.AnywhereField;
import com.sdv.platform.application.port.BusinessRecordRepository.AvailableDocumentSnapshot;
import com.sdv.platform.application.port.BusinessRecordRepository.DocumentCount;
import com.sdv.platform.application.port.BusinessRecordRepository.ExpectedDocumentSnapshot;
import com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot;
import com.sdv.platform.application.port.BusinessRecordRepository.RecentDocumentView;
import com.sdv.platform.application.port.BusinessRecordRepository.SearchCriterion;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.RecordValuesPort;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.source.IngestionSourceStatus;
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
    private static final int MAX_PAGE = 10_000;
    private static final int MAX_CRITERIA = 10;
    private static final int MAX_QUERY_VALUE_LENGTH = 512;
    /** "Anywhere" text, after trimming (task 0022). */
    static final int ANYWHERE_MIN_LENGTH = 2;
    static final int ANYWHERE_MAX_LENGTH = 100;
    /** Rank of an anywhere match, lower is better; must match the order the search SQL uses. */
    static final int RANK_EXACT_IDENTIFIER = 0;
    static final int RANK_EXACT = 1;
    static final int RANK_STARTS_WITH = 2;
    static final int RANK_CONTAINS = 3;
    static final int RECENT_DEFAULT_LIMIT = 8;
    static final int RECENT_MAX_LIMIT = 20;
    /** At most this many of the person's newest view events are read for Recently viewed. */
    static final int RECENT_EVENT_SCAN = 500;
    static final java.time.Duration RECENT_WINDOW = java.time.Duration.ofDays(90);

    private final TenantRepository tenants;
    private final IngestionSourceRepository sources;
    private final MetadataFieldDefinitionRepository definitions;
    private final BusinessRecordRepository records;
    private final AuditEventPort audit;
    /* Task 0048: which documents are quarantined now (search rows and the record panel show them as unavailable). */
    private final QuarantineLookup quarantine;
    /* Task 0078: the sensitive values: exact search, and opened for who may see them. */
    private final RecordValuesPort values;
    private final BusinessRecordRefCodec refs = new BusinessRecordRefCodec();
    private final Clock clock;

    public BusinessExperienceService(
            TenantRepository tenants,
            IngestionSourceRepository sources,
            MetadataFieldDefinitionRepository definitions,
            BusinessRecordRepository records,
            AuditEventPort audit) {
        this(tenants, sources, definitions, records, audit, QuarantineLookup.NONE, Clock.systemUTC());
    }

    @Autowired
    public BusinessExperienceService(
            TenantRepository tenants,
            IngestionSourceRepository sources,
            MetadataFieldDefinitionRepository definitions,
            BusinessRecordRepository records,
            AuditEventPort audit,
            QuarantineLookup quarantine,
            RecordValuesPort values) {
        this(tenants, sources, definitions, records, audit, quarantine, values, Clock.systemUTC());
    }

    BusinessExperienceService(
            TenantRepository tenants,
            IngestionSourceRepository sources,
            MetadataFieldDefinitionRepository definitions,
            BusinessRecordRepository records,
            AuditEventPort audit,
            Clock clock) {
        this(tenants, sources, definitions, records, audit, QuarantineLookup.NONE, clock);
    }

    BusinessExperienceService(
            TenantRepository tenants,
            IngestionSourceRepository sources,
            MetadataFieldDefinitionRepository definitions,
            BusinessRecordRepository records,
            AuditEventPort audit,
            QuarantineLookup quarantine,
            Clock clock) {
        this(tenants, sources, definitions, records, audit, quarantine, RecordValuesPort.NONE, clock);
    }

    BusinessExperienceService(
            TenantRepository tenants,
            IngestionSourceRepository sources,
            MetadataFieldDefinitionRepository definitions,
            BusinessRecordRepository records,
            AuditEventPort audit,
            QuarantineLookup quarantine,
            RecordValuesPort values,
            Clock clock) {
        this.tenants = tenants;
        this.sources = sources;
        this.definitions = definitions;
        this.records = records;
        this.audit = audit;
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
        this.values = Objects.requireNonNull(values, "values");
        this.clock = clock;
    }


    private List<MetadataFieldDefinition> orderedSearchFields(UUID tenantId, UUID sourceId) {
        List<String> saved = definitions.findDisplayOrder(tenantId, sourceId);
        Map<String, Integer> positions = new HashMap<>();
        for (int i=0; i<saved.size(); i++) positions.put(saved.get(i), i);
        return definitions.findCurrent(tenantId, sourceId).stream()
                .sorted(Comparator.comparingInt((MetadataFieldDefinition f) ->
                         positions.getOrDefault(f.fieldKey(), Integer.MAX_VALUE))
                        .thenComparing(MetadataFieldDefinition::displayLabel, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(MetadataFieldDefinition::fieldKey))
                .toList();
    }

    public BusinessExperienceView.SearchSchema searchSchema(UUID tenantId) {
        requireActiveTenant(tenantId);
        // Task 0023: one query for every system's last delivery, read only for this tenant.
        Map<UUID, Instant> lastDelivered = new HashMap<>();
        for (BusinessRecordRepository.SourceActivity a : records.findSourceActivity(tenantId)) {
            if (a.lastDeliveredAt() != null) lastDelivered.put(a.sourceId(), a.lastDeliveredAt());
        }
        Set<String> sensitive = values.sensitiveKeys(tenantId);
        boolean partial = values.partialSearch();
        List<BusinessExperienceView.SourceSchema> sourceSchemas = sources.findAll(tenantId).stream()
                .sorted(Comparator.comparing(IngestionSource::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(IngestionSource::sourceKey))
                .map(source -> new BusinessExperienceView.SourceSchema(
                        source.sourceId(),
                        source.sourceKey(),
                        source.displayName(),
                        orderedSearchFields(tenantId, source.sourceId()).stream()
                                .filter(MetadataFieldDefinition::searchable)
                                .map(field -> new BusinessExperienceView.SearchField(
                                        field.fieldKey(),
                                        field.displayLabel(),
                                        field.fieldType(),
                                        field.businessIdentifier(),
                                        field.normalization(),
                                        sensitive.contains(field.fieldKey()) ? MetadataSearchMode.EXACT : mode(field, partial),
                                        sensitive.contains(field.fieldKey()),
                                        !partial && field.searchMode() == MetadataSearchMode.PARTIAL && !sensitive.contains(field.fieldKey())))
                                .toList(),
                        source.status() == IngestionSourceStatus.ACTIVE ? "ACTIVE" : "PAUSED",
                        lastDelivered.containsKey(source.sourceId()),
                        lastDelivered.get(source.sourceId())))
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
        return search(tenantId, sourceId, requestedCriteria, createdFrom, createdTo, updatedFrom, updatedTo,
                false, requestedPage, requestedPageSize, actorId, correlationId);
    }

    /**
     * incompleteOnly: only records with an incomplete submission (missing documents). It counts as a criterion,
     * like a date range, so it may be used alone.
     */
    public BusinessExperienceView.SearchResponse search(
            UUID tenantId,
            UUID sourceId,
            Map<String, String> requestedCriteria,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            boolean incompleteOnly,
            Integer requestedPage,
            Integer requestedPageSize,
            String actorId,
            String correlationId) {
        return search(tenantId, sourceId, requestedCriteria, null, createdFrom, createdTo, updatedFrom, updatedTo,
                incompleteOnly, requestedPage, requestedPageSize, actorId, correlationId);
    }

    /**
     * anywhere (task 0022): text tried in every searchable field of the source, each with its own match and
     * normalization (digits-only fields only when the text has digits; dates never). It counts as a criterion and
     * combines with the others by AND. Results then come best match first and name the fields that matched.
     */
    public BusinessExperienceView.SearchResponse search(
            UUID tenantId,
            UUID sourceId,
            Map<String, String> requestedCriteria,
            String anywhere,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            boolean incompleteOnly,
            Integer requestedPage,
            Integer requestedPageSize,
            String actorId,
            String correlationId) {
        return search(tenantId, sourceId, requestedCriteria, anywhere, createdFrom, createdTo, updatedFrom, updatedTo,
                incompleteOnly, requestedPage, requestedPageSize, actorId, correlationId, Clearance.STANDARD_ONLY);
    }

    /**
     * Task 0026: records come back as before (counts unchanged); each carries restrictedLevel, worked out from this
     * clearance and each document's effective level.
     */
    public BusinessExperienceView.SearchResponse search(
            UUID tenantId,
            UUID sourceId,
            Map<String, String> requestedCriteria,
            String anywhere,
            Instant createdFrom,
            Instant createdTo,
            Instant updatedFrom,
            Instant updatedTo,
            boolean incompleteOnly,
            Integer requestedPage,
            Integer requestedPageSize,
            String actorId,
            String correlationId,
            Clearance clearance) {
        Objects.requireNonNull(clearance, "clearance");
        requireActiveTenant(tenantId);
        IngestionSource source = requireSource(tenantId, sourceId);

        String anywhereText = anywhere == null ? null : anywhere.trim();
        if (anywhereText != null
                && (anywhereText.length() < ANYWHERE_MIN_LENGTH || anywhereText.length() > ANYWHERE_MAX_LENGTH)) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.SCH_001, "Search text must be " + ANYWHERE_MIN_LENGTH + " to "
                    + ANYWHERE_MAX_LENGTH + " characters.");
        }

        int page = requestedPage == null ? 1 : requestedPage;
        int pageSize = requestedPageSize == null ? DEFAULT_LIMIT : requestedPageSize;
        if (page < 1 || page > MAX_PAGE) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.SCH_001, "Search page must be between 1 and " + MAX_PAGE + ".");
        }
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.SCH_001, "Search page size must be between 1 and " + MAX_LIMIT + ".");
        }

        Map<String, String> safeCriteria = requestedCriteria == null ? Map.of() : requestedCriteria;
        if (safeCriteria.isEmpty() && anywhereText == null && !incompleteOnly
                && createdFrom == null && createdTo == null && updatedFrom == null && updatedTo == null) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.SCH_001, "At least one configured metadata field, anywhere text, common date filter or incompleteOnly is required.");
        }
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.SCH_001, "dateFrom cannot be after dateTo.");
        }
        if (updatedFrom != null && updatedTo != null && updatedFrom.isAfter(updatedTo)) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.SCH_001, "lastUpdatedFrom cannot be after lastUpdatedTo.");
        }
        if (safeCriteria.size() > MAX_CRITERIA) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.SCH_001, "At most " + MAX_CRITERIA + " search fields may be combined.");
        }

        Map<String, MetadataFieldDefinition> current = definitions.findCurrent(tenantId, sourceId).stream()
                .collect(Collectors.toMap(MetadataFieldDefinition::fieldKey, Function.identity()));

        Set<String> sensitive = values.sensitiveKeys(tenantId);
        boolean partial = values.partialSearch();
        List<SearchCriterion> criteria = criteria(tenantId, sourceId, current, safeCriteria, sensitive, partial);

        boolean isAnywhere = anywhereText != null;
        AnywhereCriterion anywhereCriterion = isAnywhere ? anywhereCriterion(tenantId, sourceId, anywhereText, sensitive, partial) : null;
        if (isAnywhere && anywhereCriterion == null) {
            // No field of this source can match the text (e.g. letters and only digits-only fields): nothing found.
            appendSearchAudit(tenantId, actorId, sourceId, criteria.size(), 0, true, correlationId);
            return new BusinessExperienceView.SearchResponse(sourceId, List.of(), page, pageSize, false, false);
        }

        int firstIndex = Math.multiplyExact(page - 1, pageSize);
        int requiredCandidateCount = Math.addExact(firstIndex, pageSize + 1);
        int scanOffset = 0;
        int scanBatchSize = Math.max(200, pageSize * 4);
        boolean exhausted = false;

        LinkedHashMap<UUID, BusinessRecordRefCodec.RecordIdentity> candidates = new LinkedHashMap<>();
        while (candidates.size() < requiredCandidateCount && !exhausted) {
            List<Submission> matching = anywhereCriterion == null
                    ? records.findMatchingSubmissions(
                            tenantId, sourceId, criteria,
                            createdFrom, createdTo, updatedFrom, updatedTo,
                            incompleteOnly, scanOffset, scanBatchSize)
                    : records.findMatchingSubmissions(
                            tenantId, sourceId, criteria, anywhereCriterion,
                            createdFrom, createdTo, updatedFrom, updatedTo,
                            incompleteOnly, scanOffset, scanBatchSize);

            if (matching.isEmpty()) {
                exhausted = true;
                break;
            }

            List<UUID> matchingIds = matching.stream().map(Submission::submissionId).toList();
            Map<UUID, UUID> recordOf = records.findRecordIds(tenantId, sourceId, matchingIds);
            for (Submission submission : matching) {
                UUID recordId = recordOf.get(submission.submissionId());
                if (recordId != null) candidates.putIfAbsent(recordId, new BusinessRecordRefCodec.RecordIdentity(sourceId, recordId));
            }

            scanOffset += matching.size();
            exhausted = matching.size() < scanBatchSize;
        }

        List<BusinessRecordRefCodec.RecordIdentity> ordered = new ArrayList<>(candidates.values());
        if (firstIndex >= ordered.size()) {
            appendSearchAudit(tenantId, actorId, sourceId, criteria.size(), 0, isAnywhere, correlationId);
            return new BusinessExperienceView.SearchResponse(
                    sourceId, List.of(), page, pageSize, false, false);
        }

        int pageEndExclusive = Math.min(firstIndex + pageSize, ordered.size());
        List<String> titleFields = definitions.findRecordTitleFields(tenantId, sourceId);
        List<RecordAggregate> pageAggregates = new ArrayList<>();
        for (BusinessRecordRefCodec.RecordIdentity identity : ordered.subList(firstIndex, pageEndExclusive)) {
            RecordAggregate aggregate = aggregate(tenantId, source, identity);
            if (!aggregate.submissions().isEmpty()) pageAggregates.add(aggregate);
        }
        // Task 0026: the page's record levels in one query.
        Map<UUID, SensitivityLevel> recordLevels = pageAggregates.isEmpty() ? Map.of()
                : records.findRecordSensitivityLevels(tenantId, sourceId,
                        pageAggregates.stream().map(a -> a.identity().recordId()).toList());
        // Task 0078: the names of the page's records, for the records whose documents this person may open: one key-service call.
        Map<UUID, List<RecordValuesPort.Opened>> names = opened(tenantId, sourceId, pageAggregates.stream()
                .filter(a -> mayOpen(a, levelOf(recordLevels, a), clearance)).toList(), sensitive);
        List<BusinessExperienceView.RecordSummary> results = new ArrayList<>();
        for (RecordAggregate aggregate : pageAggregates) {
            results.add(summary(aggregate.with(names.get(aggregate.identity().recordId())), titleFields, matchedFields(aggregate, anywhereCriterion),
                    levelOf(recordLevels, aggregate), clearance, sensitive));
        }

        boolean hasMore = ordered.size() > firstIndex + pageSize || !exhausted;
        appendSearchAudit(tenantId, actorId, sourceId, criteria.size(), results.size(), isAnywhere, correlationId);
        return new BusinessExperienceView.SearchResponse(
                sourceId, results, page, pageSize, hasMore, hasMore);
    }

    /**
     * Records the person opened in the viewer themselves, newest first, one entry per record. Built only from the
     * person's own DOCUMENT_VIEW_OPENED events in this tenant (last {@link #RECENT_WINDOW}); a record is returned
     * only while the same checks as search and record detail still find it with at least one AVAILABLE document,
     * so nothing past retention or deleted appears.
     */
    public BusinessExperienceView.RecentRecords recentRecords(
            UUID tenantId,
            String actorId,
            Integer requestedLimit,
            String correlationId) {
        return recentRecords(tenantId, actorId, requestedLimit, correlationId, Clearance.STANDARD_ONLY);
    }

    /** Task 0026: as search, each record carries restrictedLevel for this clearance. */
    public BusinessExperienceView.RecentRecords recentRecords(
            UUID tenantId,
            String actorId,
            Integer requestedLimit,
            String correlationId,
            Clearance clearance) {
        Objects.requireNonNull(clearance, "clearance");
        requireActiveTenant(tenantId);
        if (actorId == null || actorId.isBlank()) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.SCH_003, "An authenticated person is required.");
        }
        int limit = requestedLimit == null ? RECENT_DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > RECENT_MAX_LIMIT) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.SCH_003, "limit must be between 1 and " + RECENT_MAX_LIMIT + ".");
        }

        List<RecentDocumentView> views = records.findRecentlyViewedDocuments(
                tenantId, actorId, clock.instant().minus(RECENT_WINDOW), RECENT_EVENT_SCAN);

        Map<UUID, UUID> recordOf = new HashMap<>();
        views.stream()
                .collect(Collectors.groupingBy(RecentDocumentView::sourceId, LinkedHashMap::new,
                        Collectors.mapping(RecentDocumentView::submissionId, Collectors.toCollection(LinkedHashSet::new))))
                .forEach((sourceId, submissionIds) -> recordOf.putAll(records.findRecordIds(tenantId, sourceId, submissionIds)));

        // One entry per record, keeping the newest open (views are already newest first).
        LinkedHashMap<UUID, Instant> openedByRecord = new LinkedHashMap<>();
        LinkedHashMap<UUID, BusinessRecordRefCodec.RecordIdentity> identities = new LinkedHashMap<>();
        for (RecentDocumentView view : views) {
            UUID recordId = recordOf.get(view.submissionId());
            if (recordId != null && identities.putIfAbsent(recordId, new BusinessRecordRefCodec.RecordIdentity(view.sourceId(), recordId)) == null) {
                openedByRecord.put(recordId, view.lastOpenedAt());
            }
        }

        Map<UUID, Optional<IngestionSource>> sourceCache = new HashMap<>();
        Map<UUID, List<String>> titleFieldsBySource = new HashMap<>();
        LinkedHashMap<RecordAggregate, SensitivityLevel> found = new LinkedHashMap<>();
        for (BusinessRecordRefCodec.RecordIdentity identity : identities.values()) {
            if (found.size() >= limit) break;
            Optional<IngestionSource> source = sourceCache.computeIfAbsent(
                    identity.sourceId(), id -> sources.findById(tenantId, id));
            if (source.isEmpty()) continue;
            RecordAggregate aggregate = aggregate(tenantId, source.get(), identity);
            if (aggregate.submissions().isEmpty() || aggregate.documents().isEmpty()) continue;
            found.put(aggregate, records.findRecordSensitivityLevel(tenantId, identity.sourceId(), identity.recordId()).orElse(null));
        }
        // Task 0078: the names, one key-service call for each sending system in the list.
        Set<String> sensitive = values.sensitiveKeys(tenantId);
        Map<UUID, List<RecordValuesPort.Opened>> names = new HashMap<>();
        found.entrySet().stream().filter(e -> mayOpen(e.getKey(), e.getValue(), clearance)).map(Map.Entry::getKey)
                .collect(Collectors.groupingBy(a -> a.identity().sourceId()))
                .forEach((sourceId, aggregates) -> names.putAll(opened(tenantId, sourceId, aggregates, sensitive)));
        List<BusinessExperienceView.RecentRecord> recent = new ArrayList<>();
        found.forEach((aggregate, recordLevel) -> recent.add(new BusinessExperienceView.RecentRecord(
                aggregate.identity().sourceId(),
                summary(aggregate.with(names.get(aggregate.identity().recordId())), titleFieldsBySource.computeIfAbsent(aggregate.identity().sourceId(),
                        id -> definitions.findRecordTitleFields(tenantId, id)), List.of(), recordLevel, clearance, sensitive),
                openedByRecord.get(aggregate.identity().recordId()))));

        audit.append(new AuditEvent(
                UUID.randomUUID(), tenantId, "USER", actorId,
                "BUSINESS_RECENT_RECORDS_LISTED", "USER", actorId,
                clock.instant(), correlationId, AuditOutcome.SUCCESS,
                "{\"resultCount\":" + recent.size() + "}"));
        return new BusinessExperienceView.RecentRecords(List.copyOf(recent));
    }

    public BusinessExperienceView.RecordView record(
            UUID tenantId,
            String recordRef,
            String actorId,
            String correlationId) {
        return record(tenantId, recordRef, actorId, correlationId, Clearance.STANDARD_ONLY);
    }

    /**
     * Task 0026: the record and its search fields as before. Documents this clearance can't open at their effective
     * level are listed apart (lockedDocuments) with only their reference and level.
     */
    public BusinessExperienceView.RecordView record(
            UUID tenantId,
            String recordRef,
            String actorId,
            String correlationId,
            Clearance clearance) {
        Objects.requireNonNull(clearance, "clearance");
        requireActiveTenant(tenantId);
        BusinessRecordRefCodec.RecordIdentity identity = refs.decode(recordRef);
        // Task 0043: a reference to another tenant's sending system is "not found" (SDV-SCH-002), like any other.
        IngestionSource source = sources.findById(tenantId, identity.sourceId())
                .orElseThrow(() -> RecordRefusals.notFound("sending system not of this tenant"));

        RecordAggregate aggregate = aggregate(tenantId, source, identity);
        if (aggregate.submissions().isEmpty()) {
            throw RecordRefusals.notFound("no submission left for the reference");
        }

        // Task 0024, part 2: the record's own level.
        SensitivityLevel recordLevel = records.findRecordSensitivityLevel(
                tenantId, source.sourceId(), identity.recordId()).orElse(null);
        // Task 0078: its sensitive values only for who may open its documents: one key-service call.
        if (mayOpen(aggregate, recordLevel, clearance)) {
            aggregate = aggregate.with(openOne(tenantId, source.sourceId(), identity.recordId()));
        }
        BusinessExperienceView.RecordView view = recordView(aggregate, recordLevel,
                definitions.findRecordTitleFields(tenantId, source.sourceId()), clearance, values.sensitiveKeys(tenantId));
        // submissionId is an internal reference (a UUID, never a readable value); the audit list
        // looks up the record number from it within the tenant (task 0020 item 1).
        audit.append(new AuditEvent(
                UUID.randomUUID(), tenantId, "USER", actorId,
                "BUSINESS_RECORD_OPENED", "BUSINESS_RECORD", sha256(recordRef),
                clock.instant(), correlationId, AuditOutcome.SUCCESS,
                "{\"sourceId\":\"" + source.sourceId()
                        + "\",\"submissionId\":\"" + aggregate.submissions().getFirst().submissionId()
                        + "\",\"submissionCount\":"
                        + view.submissionCount() + ",\"availableDocumentCount\":"
                        + (view.documents().size() + view.lockedDocuments().size() + view.unavailableDocuments().size())
                        + (view.lockedDocuments().isEmpty() ? "" : ",\"lockedDocumentCount\":" + view.lockedDocuments().size())
                        + "}"));
        return view;
    }

    private static MetadataSearchMode mode(MetadataFieldDefinition field, boolean partial) {
        return field.searchMode() == MetadataSearchMode.PARTIAL && !partial ? MetadataSearchMode.PREFIX : field.searchMode();
    }

    /** Task 0078: every field checked first, then the sensitive ones' fingerprints in one go (two key-service calls at most). */
    private List<SearchCriterion> criteria(UUID tenantId, UUID sourceId, Map<String, MetadataFieldDefinition> current, Map<String, String> asked,
                                           Set<String> sensitive, boolean partial) {
        List<SearchCriterion> out = new ArrayList<>();
        Map<String, String> exact = new TreeMap<>();
        for (Map.Entry<String, String> entry : new TreeMap<>(asked).entrySet()) {
            SearchCriterion criterion = buildCriterion(tenantId, sourceId, current, entry.getKey(), entry.getValue(), partial);
            if (sensitive.contains(entry.getKey())) exact.put(entry.getKey(), entry.getValue());
            else out.add(criterion);
        }
        values.fingerprints(tenantId, exact).forEach((key, prints) -> out.add(SearchCriterion.sensitive(key, prints)));
        out.sort(Comparator.comparing(SearchCriterion::fieldKey));
        return out;
    }

    /**
     * Task 0078: sensitive values are shown to who may open the record's documents (those not quarantined) at their effective level; a record
     * without a document, at its own level.
     */
    private static boolean mayOpen(RecordAggregate aggregate, SensitivityLevel recordLevel, Clearance clearance) {
        return clearance.canOpen(recordLevel == null ? SensitivityLevel.STANDARD : recordLevel)
                && aggregate.notQuarantined().stream().allMatch(d -> clearance.canOpen(SensitivityLevel.effective(d.sensitivityLevel(), recordLevel)));
    }

    private Map<UUID, List<RecordValuesPort.Opened>> opened(UUID tenantId, UUID sourceId, List<RecordAggregate> aggregates, Set<String> sensitive) {
        if (aggregates.isEmpty() || sensitive.isEmpty()) return Map.of();
        return values.openPage(tenantId, sourceId, aggregates.stream().map(a -> a.identity().recordId()).toList());
    }

    /** Without the key service the record opens without its sensitive values (its documents can't be opened then either). */
    private List<RecordValuesPort.Opened> openOne(UUID tenantId, UUID sourceId, UUID recordId) {
        try {
            return values.open(tenantId, sourceId, recordId);
        } catch (com.sdv.platform.domain.error.KeyServiceDown ex) {
            return List.of();
        }
    }

    private SearchCriterion buildCriterion(
            UUID tenantId,
            UUID sourceId,
            Map<String, MetadataFieldDefinition> current,
            String fieldKey,
            String rawValue,
            boolean partial) {
        MetadataFieldDefinition field = current.get(fieldKey);
        if (field == null || !field.searchable()) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.SCH_001,
                    "Search field '" + fieldKey + "' is not currently exposed for this source.");
        }
        if (rawValue == null || rawValue.isBlank()) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.SCH_001, "Search value for '" + fieldKey + "' is required.");
        }
        if (rawValue.length() > MAX_QUERY_VALUE_LENGTH) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.SCH_001, "Search value for '" + fieldKey + "' is too long.");
        }

        List<String> variants = normalizedVariants(tenantId, sourceId, field, rawValue);
        if (variants.isEmpty()) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.SCH_001,
                    "Search value for '" + fieldKey + "' is empty after configured normalization.");
        }
        return new SearchCriterion(fieldKey, mode(field, partial), variants, exactIgnoresCase(field));
    }

    /**
     * Task 0022 follow-up: an Exact text field ignores capitals ("mortgage" finds "Mortgage"), in quick search and in
     * single-field search. Identifiers and digits-only fields keep their exact comparison, and so do fields that
     * aren't text. Starts with and Contains already ignore case.
     */
    static boolean exactIgnoresCase(MetadataFieldDefinition field) {
        return field.searchMode() == MetadataSearchMode.EXACT
                && !field.businessIdentifier()
                && field.normalization() != MetadataNormalization.DIGITS_ONLY
                && (field.fieldType() == MetadataFieldType.TEXT || field.fieldType() == MetadataFieldType.EMAIL);
    }

    /** The value as each normalization the field has had would have stored it (so older records still match). */
    private List<String> normalizedVariants(UUID tenantId, UUID sourceId, MetadataFieldDefinition field, String rawValue) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        definitions.findHistory(tenantId, sourceId, field.fieldKey()).stream()
                .map(MetadataFieldDefinition::normalization)
                .distinct()
                .forEach(normalization -> {
                    String normalized = SearchValueNormalizer.normalize(normalization, rawValue);
                    if (!normalized.isBlank()) variants.add(normalized);
                });
        String currentNormalized = SearchValueNormalizer.normalize(field.normalization(), rawValue);
        if (!currentNormalized.isBlank()) variants.add(currentNormalized);
        if (field.searchMode() == MetadataSearchMode.EXACT) variants.addAll(definitions.storedForms(tenantId, field.fieldKey(), rawValue));
        return List.copyOf(variants);
    }

    /**
     * The fields "anywhere" text is tried in, in the source's display order: every searchable field except dates and sensitive fields,
     * and digits-only fields only when the text has a digit. Null when no field can match the text.
     */
    private AnywhereCriterion anywhereCriterion(UUID tenantId, UUID sourceId, String text, Set<String> sensitive, boolean partial) {
        boolean hasDigit = text.chars().anyMatch(Character::isDigit);
        List<AnywhereField> fields = new ArrayList<>();
        for (MetadataFieldDefinition field : orderedSearchFields(tenantId, sourceId)) {
            if (!field.searchable() || !field.active() || field.fieldType() == MetadataFieldType.DATE) continue;
            if (sensitive.contains(field.fieldKey())) continue; // only the whole value finds those (task 0078)
            if (field.normalization() == MetadataNormalization.DIGITS_ONLY && !hasDigit) continue;
            List<String> variants = normalizedVariants(tenantId, sourceId, field, text);
            if (variants.isEmpty()) continue;
            fields.add(new AnywhereField(field.fieldKey(), mode(field, partial), variants, field.businessIdentifier(),
                    exactIgnoresCase(field)));
        }
        return fields.isEmpty() ? null : new AnywhereCriterion(fields);
    }

    /**
     * The record's fields that match the anywhere text, best match first (then in display order). Values come from
     * the record's own data, newest submission first. Only the fields the search tried can appear.
     */
    static List<BusinessExperienceView.MatchedField> matchedFields(RecordAggregate aggregate, AnywhereCriterion anywhere) {
        if (anywhere == null) return List.of();
        Map<UUID, List<MetadataSnapshot>> bySubmission = groupMetadata(aggregate.metadata());
        LinkedHashMap<String, Integer> rankByField = new LinkedHashMap<>();
        LinkedHashMap<String, BusinessExperienceView.MatchedField> byField = new LinkedHashMap<>();
        for (Submission submission : aggregate.submissions()) {
            for (MetadataSnapshot value : bySubmission.getOrDefault(submission.submissionId(), List.of())) {
                for (AnywhereField field : anywhere.fields()) {
                    if (!field.fieldKey().equals(value.fieldKey()) || byField.containsKey(field.fieldKey())) continue;
                    Integer rank = matchRank(field, value.normalizedValue());
                    if (rank == null) continue;
                    rankByField.put(field.fieldKey(), rank);
                    byField.put(field.fieldKey(), new BusinessExperienceView.MatchedField(
                            value.fieldKey(), value.displayLabel(), value.storedValue()));
                }
            }
        }
        List<String> order = anywhere.fields().stream().map(AnywhereField::fieldKey).toList();
        return byField.values().stream()
                .sorted(Comparator.comparingInt((BusinessExperienceView.MatchedField m) -> rankByField.get(m.fieldKey()))
                        .thenComparingInt(m -> order.indexOf(m.fieldKey())))
                .toList();
    }

    /** The same match and rank as the search SQL, for one stored (normalized) value; null when it doesn't match. */
    static Integer matchRank(AnywhereField field, String normalizedValue) {
        if (normalizedValue == null) return null;
        int exact = field.businessIdentifier() ? RANK_EXACT_IDENTIFIER : RANK_EXACT;
        Integer best = null;
        for (String variant : field.normalizedVariants()) {
            Integer rank;
            if (field.searchMode() == MetadataSearchMode.EXACT) {
                boolean equal = field.ignoreCase()
                        ? normalizedValue.toLowerCase(Locale.ROOT).equals(variant.toLowerCase(Locale.ROOT))
                        : normalizedValue.equals(variant);
                rank = equal ? exact : null;
            } else {
                String value = normalizedValue.toLowerCase(Locale.ROOT), wanted = variant.toLowerCase(Locale.ROOT);
                boolean matches = field.searchMode() == MetadataSearchMode.PREFIX ? value.startsWith(wanted) : value.contains(wanted);
                rank = !matches ? null : value.equals(wanted) ? exact : value.startsWith(wanted) ? RANK_STARTS_WITH : RANK_CONTAINS;
            }
            if (rank != null && (best == null || rank < best)) best = rank;
        }
        return best;
    }

    private RecordAggregate aggregate(
            UUID tenantId,
            IngestionSource source,
            BusinessRecordRefCodec.RecordIdentity identity) {
        List<UUID> memberIds = records.findSubmissionIdsOfRecord(tenantId, source.sourceId(), identity.recordId());
        if (memberIds.isEmpty()) {
            return new RecordAggregate(source, identity, List.of(), List.of(), List.of(), List.of(), Map.of(), Set.of(), List.of());
        }

        List<Submission> submissions = records.findSubmissionsByIds(
                tenantId, source.sourceId(), memberIds);
        if (submissions.isEmpty()) {
            return new RecordAggregate(source, identity, List.of(), List.of(), List.of(), List.of(), Map.of(), Set.of(), List.of());
        }

        List<UUID> ids = submissions.stream().map(Submission::submissionId).toList();
        List<MetadataSnapshot> metadata = records.findMetadataSnapshots(tenantId, source.sourceId(), ids);
        List<AvailableDocumentSnapshot> documents = records.findAvailableDocuments(
                tenantId, source.sourceId(), ids);
        List<ExpectedDocumentSnapshot> expected = records.findExpectedDocuments(tenantId, ids);
        Map<UUID, Integer> documentCounts = records.countDocuments(tenantId, source.sourceId(), ids).stream()
                .collect(Collectors.toMap(DocumentCount::submissionId, DocumentCount::documentCount));

        Set<UUID> quarantined = documents.isEmpty() ? Set.of()
                : quarantine.quarantined(tenantId, documents.stream().map(AvailableDocumentSnapshot::documentId).toList());
        return new RecordAggregate(source, identity, submissions, metadata, documents, expected, documentCounts, quarantined, List.of());
    }

    private static SensitivityLevel levelOf(Map<UUID, SensitivityLevel> recordLevels, RecordAggregate aggregate) {
        return recordLevels == null ? null : recordLevels.get(aggregate.identity().recordId());
    }

    /** Task 0026: the highest effective level among the record's documents this clearance can't open, or null. */
    static String restrictedLevel(List<AvailableDocumentSnapshot> documents, SensitivityLevel recordLevel,
                                  Clearance clearance) {
        SensitivityLevel locked = clearance.highestLocked(documents.stream()
                .map(document -> SensitivityLevel.effective(document.sensitivityLevel(), recordLevel))
                .toList());
        return locked == null ? null : locked.name();
    }

    private BusinessExperienceView.RecordSummary summary(
            RecordAggregate aggregate,
            List<String> titleFields,
            List<BusinessExperienceView.MatchedField> matchedFields,
            SensitivityLevel recordLevel,
            Clearance clearance,
            Set<String> sensitive) {
        List<BusinessExperienceView.FieldValue> latestMetadata = latestMetadata(aggregate);
        List<BusinessExperienceView.FieldValue> identifiers = displayIdentifiers(aggregate, latestMetadata);
        List<BusinessExperienceView.FieldValue> preview = latestMetadata.stream()
                .filter(value -> !value.businessIdentifier() && !aggregate.isOpened(value.fieldKey()))
                .limit(4)
                .toList();
        List<Submission> open = aggregate.submissions().stream()
                .filter(submission -> submission.status() != SubmissionStatus.COMPLETE)
                .toList();
        // Same counts as record detail's incomplete attention, added up over the incomplete submissions.
        int expected = open.stream().mapToInt(Submission::expectedDocumentCount).sum();
        int received = open.stream()
                .mapToInt(submission -> aggregate.documentCounts().getOrDefault(submission.submissionId(), 0))
                .sum();

        return new BusinessExperienceView.RecordSummary(
                refs.encode(aggregate.identity()),
                title(aggregate, latestMetadata, titleFields, sensitive),
                identifiers,
                preview,
                aggregate.submissions().size(),
                aggregate.documents().size(),
                aggregate.submissions().getFirst().createdAt(),
                !open.isEmpty(),
                expected,
                received,
                matchedFields,
                // Task 0048: a quarantined document shows no level to anyone here; it is counted as unavailable.
                restrictedLevel(aggregate.notQuarantined(), recordLevel, clearance),
                aggregate.quarantined().size());
    }

    private BusinessExperienceView.RecordView recordView(RecordAggregate aggregate, SensitivityLevel recordLevel,
                                                          List<String> titleFields, Clearance clearance,
                                                          Set<String> sensitive) {
        List<BusinessExperienceView.FieldValue> metadata = latestMetadata(aggregate);
        List<BusinessExperienceView.FieldValue> identifiers = displayIdentifiers(aggregate, metadata);
        // Task 0048: a quarantined document is listed as unavailable (its reference only, no level, no reason),
        // except to a reviewer who can open its level: they see it with the others, marked quarantined.
        List<BusinessExperienceView.UnavailableDocument> unavailable = aggregate.documents().stream()
                .filter(document -> aggregate.quarantined().contains(document.documentId()))
                .filter(document -> !clearance.canReviewQuarantined(SensitivityLevel.effective(document.sensitivityLevel(), recordLevel)))
                .map(document -> new BusinessExperienceView.UnavailableDocument(document.documentId()))
                .toList();
        Set<UUID> unavailableIds = unavailable.stream().map(BusinessExperienceView.UnavailableDocument::documentId)
                .collect(Collectors.toSet());
        List<BusinessExperienceView.LockedDocument> locked = aggregate.documents().stream()
                .filter(document -> !unavailableIds.contains(document.documentId()))
                .filter(document -> !clearance.canOpen(SensitivityLevel.effective(document.sensitivityLevel(), recordLevel)))
                .map(document -> new BusinessExperienceView.LockedDocument(document.documentId(),
                        SensitivityLevel.effective(document.sensitivityLevel(), recordLevel).name()))
                .toList();
        List<BusinessExperienceView.DocumentSummary> documents = aggregate.documents().stream()
                .filter(document -> !unavailableIds.contains(document.documentId()))
                .filter(document -> clearance.canOpen(SensitivityLevel.effective(document.sensitivityLevel(), recordLevel)))
                .map(document -> new BusinessExperienceView.DocumentSummary(
                        document.documentId(), document.submissionId(), document.sourceDocumentId(),
                        document.originalFilename(), document.ingestedAt(), document.availableAt(), document.expiresAt(),
                        document.pageCount(), document.redacted(), document.sensitivityLevel().name(),
                        SensitivityLevel.effective(document.sensitivityLevel(), recordLevel).name(),
                        aggregate.quarantined().contains(document.documentId())))
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
                title(aggregate, metadata, titleFields, sensitive),
                aggregate.source().sourceId(),
                aggregate.source().sourceKey(),
                aggregate.source().displayName(),
                identifiers,
                metadata,
                aggregate.submissions().size(),
                aggregate.submissions().getFirst().createdAt(),
                documents,
                attention,
                recordLevel == null ? null : recordLevel.name(),
                locked,
                restrictedLevel(aggregate.documents().stream()
                        .filter(document -> !unavailableIds.contains(document.documentId())).toList(), recordLevel, clearance),
                unavailable);
    }

    /** Task 0078: a title whose fields this person may not see is left out, and the screen falls back to the identifier. */
    private static String title(RecordAggregate aggregate, List<BusinessExperienceView.FieldValue> latestMetadata, List<String> titleFields, Set<String> sensitive) {
        if (titleFields != null && titleFields.stream().anyMatch(key -> sensitive.contains(key) && !aggregate.isOpened(key))) return null;
        return recordTitle(latestMetadata, titleFields);
    }

    /**
     * The record's name. With a Record title setting: the chosen fields' values joined by a space, as long as every
     * chosen field has a value on this record. Otherwise (no setting, or a chosen field missing): the first preview
     * field, as before. Never an identifier; null when the record has no other field with a value.
     */
    public static String recordTitle(List<BusinessExperienceView.FieldValue> latestMetadata, List<String> titleFields) {
        if (titleFields != null && !titleFields.isEmpty()) {
            Map<String, BusinessExperienceView.FieldValue> byKey = latestMetadata.stream()
                    .collect(Collectors.toMap(BusinessExperienceView.FieldValue::fieldKey, Function.identity(), (a, b) -> a));
            List<String> parts = new ArrayList<>();
            for (String key : titleFields) {
                BusinessExperienceView.FieldValue value = byKey.get(key);
                if (value == null || value.businessIdentifier() || value.value() == null || value.value().isBlank()) {
                    parts = null;
                    break;
                }
                parts.add(value.value().trim());
            }
            if (parts != null) return String.join(" ", parts);
        }
        return latestMetadata.stream()
                .filter(value -> !value.businessIdentifier())
                .map(BusinessExperienceView.FieldValue::value)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
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
        aggregate.opened().forEach(v -> latest.put(v.fieldKey(), new BusinessExperienceView.FieldValue(v.fieldKey(), v.label(), v.value(), v.identifier(), true)));
        return latest.values().stream()
                .sorted(Comparator.comparing(BusinessExperienceView.FieldValue::displayLabel, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(BusinessExperienceView.FieldValue::fieldKey))
                .toList();
    }

    private static List<BusinessExperienceView.FieldValue> displayIdentifiers(
            RecordAggregate aggregate,
            List<BusinessExperienceView.FieldValue> latestMetadata) {
        return latestMetadata.stream().filter(BusinessExperienceView.FieldValue::businessIdentifier)
                .sorted(Comparator.comparing(BusinessExperienceView.FieldValue::fieldKey)).toList();
    }

    private static Map<UUID, List<MetadataSnapshot>> groupMetadata(List<MetadataSnapshot> metadata) {
        return metadata.stream().collect(Collectors.groupingBy(MetadataSnapshot::submissionId));
    }

    private void requireActiveTenant(UUID tenantId) {
        var tenant = ActiveTenant.require(tenants, tenantId);
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
            boolean anywhere,
            String correlationId) {
        // An "anywhere" search says so; the text itself is never stored (it may be a name).
        audit.append(new AuditEvent(
                UUID.randomUUID(), tenantId, "USER", actorId,
                "BUSINESS_SEARCH", "INGESTION_SOURCE", sourceId.toString(),
                clock.instant(), correlationId, AuditOutcome.SUCCESS,
                "{\"criteriaCount\":" + criteriaCount + ",\"resultCount\":" + resultCount
                        + (anywhere ? ",\"anywhere\":true" : "") + "}"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new InternalCheckFailed("SHA-256 is unavailable.", ex);
        }
    }

    private record RecordAggregate(
            IngestionSource source,
            BusinessRecordRefCodec.RecordIdentity identity,
            List<Submission> submissions,
            List<MetadataSnapshot> metadata,
            List<AvailableDocumentSnapshot> documents,
            List<ExpectedDocumentSnapshot> expected,
            Map<UUID, Integer> documentCounts,
            Set<UUID> quarantined,
            List<RecordValuesPort.Opened> opened) {
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
            quarantined = Set.copyOf(quarantined);
        }

        RecordAggregate with(List<RecordValuesPort.Opened> sensitive) {
            return sensitive == null || sensitive.isEmpty() ? this
                    : new RecordAggregate(source, identity, submissions, metadata, documents, expected, documentCounts, quarantined, List.copyOf(sensitive));
        }

        boolean isOpened(String fieldKey) {
            return opened.stream().anyMatch(v -> v.fieldKey().equals(fieldKey));
        }

        /** Task 0048: the documents not quarantined now. */
        List<AvailableDocumentSnapshot> notQuarantined() {
            return documents.stream().filter(document -> !quarantined.contains(document.documentId())).toList();
        }
    }
}
