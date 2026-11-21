package com.sdv.platform.application.approvals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.business.RecordKey;
import com.sdv.platform.application.business.RecordReferences;
import com.sdv.platform.application.port.AccessRequestRepository;
import com.sdv.platform.application.port.AccessRequestRepository.AccessRequest;
import com.sdv.platform.application.port.AccessRequestRepository.DocumentFacts;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.AvailableDocumentSnapshot;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.RecordSensitivityRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0028 (S5 + S6): access requests and their decisions.
 * <ul>
 *   <li>A Restricted Steward asks to print or download one Highly Confidential document (the web layer checks
 *       REQUEST_SENSITIVE_ACCESS). A Steward's request to lower a level comes through {@link
 *       com.sdv.platform.application.sensitivity.SensitivityLevelService}.</li>
 *   <li>A Business Admin (APPROVE_SENSITIVE_ACCESS; the web layer checks it) lists what is waiting and approves or
 *       declines, one at a time or many at once. Nobody decides their own request.</li>
 *   <li>An approved print or download is a grant for one document and one use, usable for 24 hours by default
 *       ({@link ApprovalSettings}); {@link SensitiveAccessGrantStore} uses it. An approved lowering is applied at
 *       once. A request nobody decides expires after 7 days by default.</li>
 * </ul>
 * The Business Admin never sees a title, file name, other case fields or content: only what {@link PendingRequestView}
 * holds. Every request, decision, refused decision and expiry is audited; a change and its audit event are written in
 * one transaction. The request's free-text reason is not copied into the request and decision events (it stays in
 * access_requests, which IT Admins don't read). Task 0029 (0.4): neither does a level change's event; an approved
 * lowering writes a row of the level-change history (sensitivity_level_changes) with the reason, the approver as who
 * changed it and the request it came from, and its event names that row (levelChangeId).
 */
@Service
public class AccessRequestService {
    public static final String CREATED_ACTION = "ACCESS_REQUEST_CREATED";
    public static final String APPROVED_ACTION = "ACCESS_REQUEST_APPROVED";
    public static final String DECLINED_ACTION = "ACCESS_REQUEST_DECLINED";
    public static final String DECISION_REFUSED_ACTION = "ACCESS_REQUEST_DECISION_REFUSED";
    public static final String DOCUMENT_LOWERED_ACTION = "DOCUMENT_SENSITIVITY_LOWERED";
    public static final String RECORD_LOWERED_ACTION = "RECORD_SENSITIVITY_LOWERED";
    public static final int REASON_MIN = 3;
    public static final int REASON_MAX = 500;
    public static final int BULK_MAX = 100;
    static final int PENDING_LIMIT = 500;
    static final int MINE_LIMIT = 100;
    /** The retention cleanup deleted these, or is deleting them: nothing about them changes any more. */
    private static final Set<String> GONE = Set.of("EXPIRED_DELETED", "RETENTION_DELETING");

    /** What a Business Admin decides. */
    public enum Decision { APPROVE, DECLINE }

    private final AccessRequestRepository requests;
    private final DocumentIntakeRepository documents;
    private final DocumentAccessGuard access;
    private final RecordReferences recordReferences;
    private final BusinessRecordRepository records;
    private final RecordSensitivityRepository recordLevels;
    private final SensitiveAccessGrantStore grants;
    private final ApprovalSettings settings;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public AccessRequestService(AccessRequestRepository requests, DocumentIntakeRepository documents,
                                DocumentAccessGuard access, RecordReferences recordReferences,
                                BusinessRecordRepository records, RecordSensitivityRepository recordLevels,
                                SensitiveAccessGrantStore grants, ApprovalSettings settings, AuditEventPort audit,
                                PlatformTransactionManager transactionManager) {
        this(requests, documents, access, recordReferences, records, recordLevels, grants, settings, audit,
                transactionManager, Clock.systemUTC());
    }

    public AccessRequestService(AccessRequestRepository requests, DocumentIntakeRepository documents,
                                DocumentAccessGuard access, RecordReferences recordReferences,
                                BusinessRecordRepository records, RecordSensitivityRepository recordLevels,
                                SensitiveAccessGrantStore grants, ApprovalSettings settings, AuditEventPort audit,
                                PlatformTransactionManager transactionManager, Clock clock) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.access = Objects.requireNonNull(access, "access");
        this.recordReferences = Objects.requireNonNull(recordReferences, "recordReferences");
        this.records = Objects.requireNonNull(records, "records");
        this.recordLevels = Objects.requireNonNull(recordLevels, "recordLevels");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------------------------------------ requests

    /**
     * A Restricted Steward asks to print or download one Highly Confidential document they can open. The document
     * must be available and Highly Confidential (at its effective level).
     */
    public RequestView requestPrintOrDownload(UUID tenantId, String actorId, String actorName, Clearance clearance,
                                              String documentReference, String kindText, String reason,
                                              String correlationId) {
        requireTenant(tenantId);
        requireActor(actorId);
        AccessRequestKind kind = kind(kindText);
        if (!kind.grant()) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.APR_012, "Ask for PRINT or DOWNLOAD.");
        }
        UUID documentId = referenceOf(documentReference, SdvErrorCode.APR_013, NO_DOCUMENT);
        Document document = documents.findDocumentByIdForTenant(tenantId, documentId)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.APR_013, NO_DOCUMENT));
        SensitivityLevel level = access.requireOpenable(document, clearance, DocumentAccessGuard.Attempt.REQUEST_ACCESS,
                actorId, correlationId);
        if (document.status() != DocumentStatus.AVAILABLE) {
            throw new CodedRefusal.Conflict(SdvErrorCode.APR_014,
                    "This document isn't available, so it can't be printed or downloaded.");
        }
        if (level != SensitivityLevel.HIGHLY_CONFIDENTIAL) {
            throw new CodedRefusal.Conflict(SdvErrorCode.APR_003,
                    "This document isn't Highly Confidential, so printing or downloading it needs no approval.");
        }
        String why = reasonOf(reason, SdvErrorCode.APR_002);
        Instant now = clock.instant();
        AccessRequest request = new AccessRequest(UUID.randomUUID(), tenantId, kind, documentId, null, null, null, why,
                actorId, blankToNull(actorName), now, AccessRequestStatus.PENDING, null, null, null,
                now.plus(settings.pendingWindow(tenantId)), null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("level", level.name());
        return create(request, details, correlationId);
    }

    /** A Steward's request to lower a document's own level; {@code SensitivityLevelService} has checked the rules. */
    public RequestView requestLowerDocument(UUID tenantId, String actorId, String actorName, UUID documentId,
                                            SensitivityLevel currentLevel, SensitivityLevel requestedLevel,
                                            String reason, String correlationId) {
        Instant now = clock.instant();
        AccessRequest request = new AccessRequest(UUID.randomUUID(), tenantId, AccessRequestKind.LOWER_LEVEL, documentId,
                null, null, requestedLevel, reason, actorId, blankToNull(actorName), now, AccessRequestStatus.PENDING,
                null, null, null, now.plus(settings.pendingWindow(tenantId)), null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("level", currentLevel.name());
        details.put("requestedLevel", requestedLevel.name());
        return create(request, details, correlationId);
    }

    /** A Steward's request to lower a whole record's level; {@code SensitivityLevelService} has checked the rules. */
    public RequestView requestLowerRecord(UUID tenantId, String actorId, String actorName,
                                          RecordReferences.ResolvedRecord record, SensitivityLevel currentLevel,
                                          SensitivityLevel requestedLevel, String reason, String correlationId) {
        Instant now = clock.instant();
        AccessRequest request = new AccessRequest(UUID.randomUUID(), tenantId, AccessRequestKind.LOWER_RECORD_LEVEL, null,
                record.sourceId(), record.recordKey(), requestedLevel, reason, actorId, blankToNull(actorName), now,
                AccessRequestStatus.PENDING, null, null, null, now.plus(settings.pendingWindow(tenantId)), null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("level", currentLevel.name());
        details.put("requestedLevel", requestedLevel.name());
        details.put("sourceId", record.sourceId().toString());
        details.put("submissionId", record.submissionIds().getFirst().toString());
        return create(request, details, correlationId);
    }

    private RequestView create(AccessRequest request, Map<String, Object> extra, String correlationId) {
        return transactions.execute(status -> {
            if (!requests.insertPending(request)) {
                throw new CodedRefusal.Conflict(SdvErrorCode.APR_004, "You already have this request waiting.");
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("accessRequestId", request.requestId().toString());
            details.put("kind", request.kind().name());
            details.putAll(extra);
            append(request.tenantId(), "USER", request.requestedBy(), request.requestedByName(), CREATED_ACTION,
                    targetType(request), targetId(request), request.requestedAt(), correlationId, AuditOutcome.SUCCESS,
                    details);
            return RequestView.of(request);
        });
    }

    /**
     * The person's own requests, newest first, with their status (waiting, approved, declined, used or expired).
     * Task 0030 (0.3): each names its record by the sending system and the primary identifier, so the viewer can list
     * a whole-record request with the documents of that record. Both are null when the record can't be found (gone).
     */
    public List<RequestView> mine(UUID tenantId, String actorId, String correlationId) {
        requireTenant(tenantId);
        requireActor(actorId);
        grants.expireDue(tenantId, correlationId);
        List<AccessRequest> rows = requests.findByRequester(tenantId, actorId, MINE_LIMIT);
        List<UUID> documentIds = rows.stream().map(AccessRequest::documentId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, DocumentFacts> facts = new HashMap<>();
        if (!documentIds.isEmpty()) requests.findDocumentFacts(tenantId, documentIds).forEach(f -> facts.put(f.documentId(), f));
        Map<String, Optional<AccessRequestRepository.PrimaryIdentifier>> recordIds = new HashMap<>();
        List<RequestView> views = new ArrayList<>();
        for (AccessRequest r : rows) {
            if (r.kind().scope() == AccessRequestKind.Scope.DOCUMENT) {
                DocumentFacts f = facts.get(r.documentId());
                views.add(f == null ? RequestView.of(r)
                        : RequestView.of(r, f.sourceId(), f.primaryIdentifier(), f.primaryIdentifierLabel()));
            } else {
                Optional<AccessRequestRepository.PrimaryIdentifier> id = recordIds.computeIfAbsent(
                        r.sourceId() + "|" + r.recordKey(), k -> recordIdentifier(tenantId, r));
                views.add(id.map(p -> RequestView.of(r, r.sourceId(), p.value(), p.label())).orElseGet(() -> RequestView.of(r)));
            }
        }
        return List.copyOf(views);
    }

    private Optional<AccessRequestRepository.PrimaryIdentifier> recordIdentifier(UUID tenantId, AccessRequest r) {
        try {
            return recordReferences.fromKey(tenantId, r.sourceId(), r.recordKey())
                    .flatMap(found -> found.submissionIds().stream()
                            .map(id -> requests.findPrimaryIdentifier(tenantId, id))
                            .flatMap(Optional::stream).findFirst());
        } catch (RuntimeException ex) {
            // The record is named only to help the requester; without it the request is still listed.
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------------------------------- Business Admin

    /**
     * What is waiting, oldest first, grouped by the record's primary identifier (groups ordered by their oldest
     * request). kinds: comma-separated kind names, or empty for all; requester: the shown name (or subject) exactly.
     */
    public PendingList pending(UUID tenantId, String kinds, String requester, String correlationId) {
        requireTenant(tenantId);
        Set<AccessRequestKind> wanted = kinds(kinds);
        String who = requester == null || requester.isBlank() ? null : requester.strip();
        grants.expireDue(tenantId, correlationId);
        List<AccessRequest> rows = requests.findPending(tenantId, wanted, who, PENDING_LIMIT);

        List<UUID> documentIds = rows.stream().map(AccessRequest::documentId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, DocumentFacts> facts = new HashMap<>();
        if (!documentIds.isEmpty()) {
            requests.findDocumentFacts(tenantId, documentIds).forEach(f -> facts.put(f.documentId(), f));
        }
        Map<UUID, SensitivityLevel> levels = facts.isEmpty() ? Map.of() : access.effectiveLevels(tenantId,
                facts.values().stream().map(f -> new DocumentAccessGuard.DocumentLevelRef(
                        f.documentId(), f.sourceId(), f.submissionId(), f.ownLevel())).toList());
        Map<String, List<AvailableDocumentSnapshot>> recordDocuments = new HashMap<>();

        List<PendingRequestView> views = new ArrayList<>();
        for (AccessRequest r : rows) {
            views.add(r.kind().scope() == AccessRequestKind.Scope.DOCUMENT
                    ? documentView(tenantId, r, facts.get(r.documentId()), levels.get(r.documentId()), recordDocuments)
                    : recordView(tenantId, r, recordDocuments));
        }
        Map<String, List<PendingRequestView>> grouped = new LinkedHashMap<>();
        Map<String, String> labels = new HashMap<>();
        for (PendingRequestView v : views) {
            String key = v.primaryIdentifier() == null ? "" : v.primaryIdentifier();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
            labels.putIfAbsent(key, v.primaryIdentifierLabel());
        }
        List<PendingGroup> groups = grouped.entrySet().stream()
                .map(e -> new PendingGroup(e.getKey().isEmpty() ? null : e.getKey(), labels.get(e.getKey()),
                        List.copyOf(e.getValue())))
                .toList();
        return new PendingList(groups, views.size());
    }

    private PendingRequestView documentView(UUID tenantId, AccessRequest r, DocumentFacts f, SensitivityLevel level,
                                            Map<String, List<AvailableDocumentSnapshot>> cache) {
        if (f == null) {
            return PendingRequestView.of(r, null, null, null, null, null, null, null, Levels.UNKNOWN);
        }
        Position position = null;
        SensitivityLevel recordLevel = null;
        boolean recordLevelKnown = false;
        try {
            RecordReferences.ResolvedRecord record = recordReferences.ofSubmission(tenantId, f.sourceId(), f.submissionId());
            recordLevel = records.findRecordSensitivityLevel(tenantId, f.sourceId(), record.recordKey()).orElse(null);
            recordLevelKnown = true;
            List<AvailableDocumentSnapshot> onRecord = cache.computeIfAbsent(f.sourceId() + "|" + record.recordKey(),
                    k -> records.findAvailableDocuments(tenantId, f.sourceId(), record.submissionIds()));
            position = position(r.documentId(), onRecord);
        } catch (RuntimeException ex) {
            // The position is a help for telling documents apart; without it the request is still listed.
        }
        return PendingRequestView.of(r, f.primaryIdentifier(), f.primaryIdentifierLabel(), f.receivedAt(), f.pageCount(),
                null, position, level, recordLevelKnown ? Levels.ofDocument(r, f.ownLevel(), recordLevel, level) : Levels.UNKNOWN);
    }

    private PendingRequestView recordView(UUID tenantId, AccessRequest r, Map<String, List<AvailableDocumentSnapshot>> cache) {
        Optional<RecordReferences.ResolvedRecord> record = recordReferences.fromKey(tenantId, r.sourceId(), r.recordKey());
        if (record.isEmpty()) return PendingRequestView.of(r, null, null, null, null, 0, null, null, Levels.UNKNOWN);
        RecordReferences.ResolvedRecord found = record.get();
        AccessRequestRepository.PrimaryIdentifier identifier = found.submissionIds().stream()
                .map(id -> requests.findPrimaryIdentifier(tenantId, id))
                .flatMap(Optional::stream).findFirst().orElse(null);
        int count = cache.computeIfAbsent(r.sourceId() + "|" + r.recordKey(),
                k -> records.findAvailableDocuments(tenantId, r.sourceId(), found.submissionIds())).size();
        SensitivityLevel level = records.findRecordSensitivityLevel(tenantId, r.sourceId(), r.recordKey()).orElse(null);
        return PendingRequestView.of(r, identifier == null ? null : identifier.value(),
                identifier == null ? null : identifier.label(), null, null, count, null, level,
                new Levels(null, level, null, null));
    }

    /**
     * "Document 2 of 6": only when another document on the record has the same received date (UTC) and page count,
     * so the two can't be told apart otherwise. Documents are counted oldest first.
     */
    static Position position(UUID documentId, List<AvailableDocumentSnapshot> onRecord) {
        List<AvailableDocumentSnapshot> ordered = onRecord.stream()
                .sorted(Comparator.comparing(AvailableDocumentSnapshot::ingestedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AvailableDocumentSnapshot::documentId))
                .toList();
        AvailableDocumentSnapshot self = ordered.stream().filter(d -> d.documentId().equals(documentId)).findFirst().orElse(null);
        if (self == null) return null;
        boolean twin = ordered.stream().anyMatch(d -> !d.documentId().equals(documentId)
                && Objects.equals(day(d.ingestedAt()), day(self.ingestedAt()))
                && Objects.equals(d.pageCount(), self.pageCount()));
        return twin ? new Position(ordered.indexOf(self) + 1, ordered.size()) : null;
    }

    private static LocalDate day(Instant at) {
        return at == null ? null : LocalDate.ofInstant(at, ZoneOffset.UTC);
    }

    /** One decision, from the approvals list. A whole-record request is decided here, one at a time. */
    public DecisionView decide(UUID tenantId, String actorId, String actorName, String requestReference,
                               Decision decision, String correlationId) {
        requireTenant(tenantId);
        requireActor(actorId);
        Objects.requireNonNull(decision, "decision");
        grants.expireDue(tenantId, correlationId);
        return decideOne(tenantId, actorId, actorName, requestReference, decision, false, correlationId);
    }

    /**
     * Many decisions at once. Each request is checked, decided and audited on its own; one refusal never stops the
     * rest. Whole-record requests are refused here (SDV-APR-009): they are decided one at a time.
     */
    public BulkResult decideMany(UUID tenantId, String actorId, String actorName, String decisionText,
                                 List<String> requestReferences, String correlationId) {
        requireTenant(tenantId);
        requireActor(actorId);
        Decision decision = decision(decisionText);
        if (requestReferences == null || requestReferences.isEmpty() || requestReferences.size() > BULK_MAX) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.APR_010,
                    "Choose from 1 to " + BULK_MAX + " requests to decide at once.");
        }
        grants.expireDue(tenantId, correlationId);
        List<BulkItem> results = new ArrayList<>();
        for (String reference : new LinkedHashSet<>(requestReferences)) {
            try {
                DecisionView done = decideOne(tenantId, actorId, actorName, reference, decision, true, correlationId);
                results.add(new BulkItem(done.requestId().toString(), done.status().name(), null, null));
            } catch (RuntimeException ex) {
                if (ex instanceof CodedRefusal coded) {
                    results.add(new BulkItem(reference, "REFUSED", coded.errorCode().code(), ex.getMessage()));
                } else {
                    results.add(new BulkItem(reference, "FAILED", null, "This request couldn't be decided. Try it on its own."));
                }
            }
        }
        long done = results.stream().filter(r -> !"REFUSED".equals(r.outcome()) && !"FAILED".equals(r.outcome())).count();
        return new BulkResult(List.copyOf(results), (int) done, results.size() - (int) done);
    }

    private DecisionView decideOne(UUID tenantId, String actorId, String actorName, String reference, Decision decision,
                                   boolean bulk, String correlationId) {
        UUID requestId = referenceOf(reference, SdvErrorCode.APR_007, NO_REQUEST);
        try {
            return transactions.execute(status -> {
                Instant now = clock.instant();
                AccessRequest r = requests.lock(tenantId, requestId)
                        .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.APR_007, NO_REQUEST));
                if (actorId.equals(r.requestedBy())) {
                    throw new CodedRefusal.Forbidden(SdvErrorCode.APR_006, "You can't approve or decline your own request.");
                }
                if (r.status() != AccessRequestStatus.PENDING || !r.expiresAt().isAfter(now)) {
                    throw new CodedRefusal.Conflict(SdvErrorCode.APR_008, "This request is no longer waiting: it "
                            + statusWords(r.status() == AccessRequestStatus.PENDING ? AccessRequestStatus.EXPIRED : r.status()) + ".");
                }
                if (bulk && r.kind().scope() == AccessRequestKind.Scope.RECORD) {
                    throw new CodedRefusal.Conflict(SdvErrorCode.APR_009,
                            "A request for a whole record is decided on its own, not with others.");
                }
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("accessRequestId", r.requestId().toString());
                details.put("kind", r.kind().name());
                details.put("requestedBy", r.requestedBy());
                if (decision == Decision.DECLINE) {
                    requests.decide(tenantId, requestId, AccessRequestStatus.DECLINED, actorId, blankToNull(actorName), now,
                            r.expiresAt());
                    append(tenantId, "USER", actorId, actorName, DECLINED_ACTION, targetType(r), targetId(r), now,
                            correlationId, AuditOutcome.SUCCESS, details);
                    return new DecisionView(requestId, AccessRequestStatus.DECLINED, null);
                }
                Instant grantEnds = null;
                switch (r.kind()) {
                    case PRINT, DOWNLOAD -> {
                        grantEnds = now.plus(settings.grantWindow(tenantId));
                        requests.decide(tenantId, requestId, AccessRequestStatus.APPROVED, actorId, blankToNull(actorName),
                                now, grantEnds);
                        details.put("grantExpiresAt", grantEnds.toString());
                    }
                    case LOWER_LEVEL -> {
                        lowerDocument(r, actorId, actorName, now, correlationId);
                        requests.decide(tenantId, requestId, AccessRequestStatus.APPROVED, actorId, blankToNull(actorName),
                                now, r.expiresAt());
                        details.put("requestedLevel", r.requestedLevel().name());
                    }
                    case LOWER_RECORD_LEVEL -> {
                        lowerRecord(r, actorId, actorName, now, correlationId);
                        requests.decide(tenantId, requestId, AccessRequestStatus.APPROVED, actorId, blankToNull(actorName),
                                now, r.expiresAt());
                        details.put("requestedLevel", r.requestedLevel().name());
                    }
                }
                append(tenantId, "USER", actorId, actorName, APPROVED_ACTION, targetType(r), targetId(r), now,
                        correlationId, AuditOutcome.SUCCESS, details);
                return new DecisionView(requestId, AccessRequestStatus.APPROVED, grantEnds);
            });
        } catch (RuntimeException ex) {
            if (ex instanceof CodedRefusal coded) refused(tenantId, actorId, actorName, requestId, decision, coded, correlationId);
            throw ex;
        }
    }

    private void lowerDocument(AccessRequest r, String actorId, String actorName, Instant now, String correlationId) {
        AccessRequestRepository.LockedDocument document = requests.lockDocument(r.tenantId(), r.documentId())
                .orElseThrow(AccessRequestService::noLongerApplies);
        if (GONE.contains(document.status()) || document.ownLevel() == null
                || document.ownLevel().compareTo(r.requestedLevel()) <= 0) {
            throw noLongerApplies();
        }
        requests.updateDocumentLevel(r.tenantId(), r.documentId(), r.requestedLevel());
        UUID changeId = UUID.randomUUID();
        requests.insertLevelChange(new AccessRequestRepository.LevelChange(changeId, r.tenantId(),
                AccessRequestRepository.LevelChange.Scope.DOCUMENT, r.documentId(), null, null,
                AccessRequestRepository.LevelChange.Direction.LOWERED, document.ownLevel(), r.requestedLevel(),
                r.reason(), actorId, blankToNull(actorName), now, r.requestId()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scope", "DOCUMENT");
        details.put("from", document.ownLevel().name());
        details.put("to", r.requestedLevel().name());
        details.put("levelChangeId", changeId.toString());
        details.put("accessRequestId", r.requestId().toString());
        details.put("requestedBy", r.requestedBy());
        append(r.tenantId(), "USER", actorId, actorName, DOCUMENT_LOWERED_ACTION, "DOCUMENT", r.documentId().toString(),
                now, correlationId, AuditOutcome.SUCCESS, details);
    }

    private void lowerRecord(AccessRequest r, String actorId, String actorName, Instant now, String correlationId) {
        RecordSensitivityRepository.StoredRecordLevel stored = recordLevels.lockByKey(r.tenantId(), r.sourceId(), r.recordKey())
                .orElseThrow(AccessRequestService::noLongerApplies);
        if (stored.level().compareTo(r.requestedLevel()) <= 0) throw noLongerApplies();
        recordLevels.setLevel(r.tenantId(), stored.recordLevelId(), r.requestedLevel(), actorId, r.reason(), now);
        UUID changeId = UUID.randomUUID();
        requests.insertLevelChange(new AccessRequestRepository.LevelChange(changeId, r.tenantId(),
                AccessRequestRepository.LevelChange.Scope.RECORD, null, r.sourceId(), r.recordKey(),
                AccessRequestRepository.LevelChange.Direction.LOWERED, stored.level(), r.requestedLevel(),
                r.reason(), actorId, blankToNull(actorName), now, r.requestId()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scope", "RECORD");
        details.put("from", stored.level().name());
        details.put("to", r.requestedLevel().name());
        details.put("levelChangeId", changeId.toString());
        details.put("accessRequestId", r.requestId().toString());
        details.put("requestedBy", r.requestedBy());
        details.put("sourceId", r.sourceId().toString());
        append(r.tenantId(), "USER", actorId, actorName, RECORD_LOWERED_ACTION, "RECORD_SENSITIVITY",
                stored.recordLevelId().toString(), now, correlationId, AuditOutcome.SUCCESS, details);
    }

    private static CodedRefusal.Conflict noLongerApplies() {
        return new CodedRefusal.Conflict(SdvErrorCode.APR_011,
                "This lowering no longer applies: the level has changed since it was asked for, or the document is gone. "
                        + "Decline it.");
    }

    /** A refused decision is audited on its own (the decision itself was rolled back). Never stops the refusal. */
    private void refused(UUID tenantId, String actorId, String actorName, UUID requestId, Decision decision,
                         CodedRefusal refusal, String correlationId) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("accessRequestId", requestId.toString());
            details.put("decision", decision.name());
            details.put("code", refusal.errorCode().code());
            append(tenantId, "USER", actorId, actorName, DECISION_REFUSED_ACTION, "ACCESS_REQUEST", requestId.toString(),
                    clock.instant(), correlationId, AuditOutcome.DENIED, details);
        } catch (RuntimeException ignored) {
            // The refusal stands even when the audit store is unavailable.
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static final String NO_DOCUMENT = "No document has this reference.";
    private static final String NO_REQUEST = "No request has this reference.";

    static String targetType(AccessRequest r) {
        return r.kind().scope() == AccessRequestKind.Scope.DOCUMENT ? "DOCUMENT" : "RECORD";
    }

    /** A document request names the document; a record request names the record by its key's SHA-256. */
    static String targetId(AccessRequest r) {
        return r.kind().scope() == AccessRequestKind.Scope.DOCUMENT ? r.documentId().toString() : RecordKey.sha256(r.recordKey());
    }

    private void append(UUID tenantId, String actorType, String actorId, String actorName, String action,
                        String targetType, String targetId, Instant at, String correlationId, AuditOutcome outcome,
                        Map<String, Object> details) {
        String safe;
        try {
            safe = json.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("The request could not be audited, so nothing was changed.", ex);
        }
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, actorType, actorId, action, targetType, targetId, at,
                correlationId, outcome, safe, blankToNull(actorName)));
    }

    private static String statusWords(AccessRequestStatus status) {
        return switch (status) {
            case APPROVED -> "was approved";
            case DECLINED -> "was declined";
            case USED -> "was approved and used";
            case EXPIRED -> "has expired";
            case PENDING -> "is waiting";
        };
    }

    static AccessRequestKind kind(String text) {
        if (text != null) {
            for (AccessRequestKind kind : AccessRequestKind.values()) {
                if (kind.name().equals(text)) return kind;
            }
        }
        throw new CodedRefusal.BadRequest(SdvErrorCode.APR_012,
                "The request kind must be one of PRINT, DOWNLOAD, LOWER_LEVEL, LOWER_RECORD_LEVEL (exactly, in capitals).");
    }

    private static Set<AccessRequestKind> kinds(String text) {
        if (text == null || text.isBlank()) return EnumSet.allOf(AccessRequestKind.class);
        Set<AccessRequestKind> kinds = EnumSet.noneOf(AccessRequestKind.class);
        for (String part : text.split(",")) kinds.add(kind(part.strip()));
        return kinds;
    }

    private static Decision decision(String text) {
        if (text != null) {
            for (Decision d : Decision.values()) {
                if (d.name().equals(text)) return d;
            }
        }
        throw new CodedRefusal.BadRequest(SdvErrorCode.APR_010, "The decision must be APPROVE or DECLINE.");
    }

    public static String reasonOf(String reason, SdvErrorCode code) {
        String value = reason == null ? "" : reason.strip();
        if (value.length() < REASON_MIN || value.length() > REASON_MAX) {
            throw new CodedRefusal.BadRequest(code, "Give a reason of " + REASON_MIN + " to " + REASON_MAX + " characters.");
        }
        return value;
    }

    public static UUID referenceOf(String reference, SdvErrorCode code, String message) {
        if (reference == null) throw new CodedRefusal.NotFound(code, message);
        String value = reference.strip();
        try {
            UUID id = UUID.fromString(value);
            // UUID.fromString accepts short forms such as "1-1-1-1-1"; only the canonical form is a reference.
            if (!id.toString().equalsIgnoreCase(value)) throw new CodedRefusal.NotFound(code, message);
            return id;
        } catch (IllegalArgumentException ex) {
            throw new CodedRefusal.NotFound(code, message);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void requireTenant(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required.");
    }

    private static void requireActor(String actorId) {
        if (actorId == null || actorId.isBlank()) throw new IllegalArgumentException("actorId is required.");
    }

    // -------------------------------------------------------------------------------------------------- views

    /**
     * A request as its requester sees it. Task 0030 (0.3): sourceId, primaryIdentifier and primaryIdentifierLabel name
     * the record (the document's record, or the whole record asked about); null when it can't be found, and in the
     * answer to a new request.
     */
    public record RequestView(
            UUID requestId,
            AccessRequestKind kind,
            AccessRequestKind.Scope scope,
            AccessRequestStatus status,
            UUID documentId,
            SensitivityLevel requestedLevel,
            String reason,
            Instant requestedAt,
            Instant decidedAt,
            Instant expiresAt,
            Instant usedAt,
            UUID sourceId,
            String primaryIdentifier,
            String primaryIdentifierLabel) {
        static RequestView of(AccessRequest r) {
            return of(r, null, null, null);
        }

        static RequestView of(AccessRequest r, UUID sourceId, String identifier, String label) {
            return new RequestView(r.requestId(), r.kind(), r.kind().scope(), r.status(), r.documentId(),
                    r.requestedLevel(), r.reason(), r.requestedAt(), r.decidedAt(), r.expiresAt(), r.usedAt(),
                    sourceId, identifier, label);
        }
    }

    /** "Document 2 of 6". */
    public record Position(int index, int of) { }

    /**
     * A waiting request as the Business Admin sees it, and nothing more: no title, file name, other case fields or
     * content. receivedAt and pageCount are the document's (document requests); documentCount is the record's
     * (whole-record requests). level is the document's effective level, or the record's level. documentId is for
     * support only: the screen doesn't show it.
     * <p>Task 0029 (0.5), so an approver isn't misled by a lowering: ownLevel is the document's own level and
     * recordLevel its record's level (null when the record has none). For a document lowering, levelAfter is the
     * effective level once approved, and accessUnchanged is true when that is still the level now: the record keeps
     * the document where it is, so who can open it won't change. For a whole-record request recordLevel is the
     * record's level and the other three are null. All four are null when they can't be worked out.
     */
    public record PendingRequestView(
            UUID requestId,
            AccessRequestKind kind,
            AccessRequestKind.Scope scope,
            String primaryIdentifier,
            String primaryIdentifierLabel,
            Instant receivedAt,
            Integer pageCount,
            Integer documentCount,
            Position position,
            SensitivityLevel level,
            SensitivityLevel requestedLevel,
            String requester,
            String reason,
            Instant requestedAt,
            UUID documentId,
            SensitivityLevel ownLevel,
            SensitivityLevel recordLevel,
            SensitivityLevel levelAfter,
            Boolean accessUnchanged) {
        static PendingRequestView of(AccessRequest r, String identifier, String label, Instant receivedAt, Integer pages,
                                     Integer documentCount, Position position, SensitivityLevel level, Levels levels) {
            return new PendingRequestView(r.requestId(), r.kind(), r.kind().scope(), identifier, label, receivedAt, pages,
                    documentCount, position, level, r.requestedLevel(), r.requesterShown(), r.reason(), r.requestedAt(),
                    r.documentId(), levels.own(), levels.record(), levels.after(), levels.accessUnchanged());
        }
    }

    /** Task 0029 (0.5): the levels behind a waiting request (see {@link PendingRequestView}). */
    record Levels(SensitivityLevel own, SensitivityLevel record, SensitivityLevel after, Boolean accessUnchanged) {
        static final Levels UNKNOWN = new Levels(null, null, null, null);

        static Levels ofDocument(AccessRequest r, SensitivityLevel own, SensitivityLevel record, SensitivityLevel effective) {
            if (r.kind() != AccessRequestKind.LOWER_LEVEL || r.requestedLevel() == null || effective == null) {
                return new Levels(own, record, null, null);
            }
            SensitivityLevel after = SensitivityLevel.effective(r.requestedLevel(), record);
            return new Levels(own, record, after, after == effective);
        }
    }

    public record PendingGroup(String primaryIdentifier, String primaryIdentifierLabel, List<PendingRequestView> requests) { }

    public record PendingList(List<PendingGroup> groups, int requestCount) { }

    public record DecisionView(UUID requestId, AccessRequestStatus status, Instant grantExpiresAt) { }

    /** outcome: APPROVED, DECLINED, REFUSED (with its code) or FAILED. */
    public record BulkItem(String requestId, String outcome, String code, String message) { }

    public record BulkResult(List<BulkItem> results, int decided, int notDecided) { }
}
