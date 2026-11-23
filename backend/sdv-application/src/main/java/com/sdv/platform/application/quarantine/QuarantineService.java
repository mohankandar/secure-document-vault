package com.sdv.platform.application.quarantine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.approvals.ApprovalSettings;
import com.sdv.platform.application.audit.AuditCsv;
import com.sdv.platform.application.business.SearchValueNormalizer;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.QuarantineRepository;
import com.sdv.platform.application.port.QuarantineRepository.DocumentFacts;
import com.sdv.platform.application.port.QuarantineRepository.Exposure;
import com.sdv.platform.application.port.QuarantineRepository.Filter;
import com.sdv.platform.application.port.QuarantineRepository.Incident;
import com.sdv.platform.application.port.QuarantineRepository.IncidentRef;
import com.sdv.platform.application.port.QuarantineRepository.MatchStats;
import com.sdv.platform.application.port.QuarantineRepository.Placed;
import com.sdv.platform.application.port.QuarantineRepository.Preparation;
import com.sdv.platform.application.port.QuarantineRepository.Quarantined;
import com.sdv.platform.application.port.QuarantineRepository.ReleaseRequest;
import com.sdv.platform.application.port.QuarantineRepository.Released;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Task 0048: quarantine. A Steward (QUARANTINE_DOCUMENTS) quarantines one document of a level they can open, on its
 * own or into an open incident; a Restricted Steward (QUARANTINE_BY_FILTER) also quarantines every document matching a
 * filter, after seeing the server's count. While quarantined, {@link DocumentAccessGuard} refuses the document to
 * everyone except a reviewer who can open its level (SDV-QUA-001), and the retention cleanup leaves it alone.
 * <p>
 * A reviewer releases one document. A whole incident is released only when a Steward asks and a Business Admin
 * (APPROVE_SENSITIVE_ACCESS) approves, one request at a time, never their own; requests expire like access requests.
 * <p>
 * The web layer checks the capability; this service checks the levels. Each change is audited in the same transaction
 * as the change. Reasons (the incident's, the release's, the request's) stay in the quarantine tables, never in the
 * audit. No view carries a file name, title or content.
 * <p>
 * Task 0051 (owner decision): no limit on a filter quarantine. Confirming stores the filter and a cut-off (the latest
 * received time among the documents counted): from that moment every matching document received up to the cut-off is
 * quarantined, on every path, before its own row exists. {@link #prepare} writes the rows in batches, in the
 * background, keeping its place in the incident, so it continues after a restart and a batch run twice writes nothing
 * twice. Review and single release work on the rows already written; a whole-incident release waits until the incident
 * is prepared, and it ends the filter's blocking. A single release frees that one document even though the filter
 * still matches it. Views of large incidents list at most {@link #REVIEW_LIMIT} documents; their counts are complete.
 */
@Service
public class QuarantineService {
    public static final String INCIDENT_CREATED_ACTION = "QUARANTINE_INCIDENT_CREATED";
    public static final String QUARANTINED_ACTION = "DOCUMENT_QUARANTINED";
    public static final String RELEASED_ACTION = "DOCUMENT_QUARANTINE_RELEASED";
    public static final String INCIDENT_CLOSED_ACTION = "QUARANTINE_INCIDENT_CLOSED";
    public static final String RELEASE_REQUESTED_ACTION = "QUARANTINE_RELEASE_REQUESTED";
    public static final String RELEASE_APPROVED_ACTION = "QUARANTINE_RELEASE_APPROVED";
    public static final String RELEASE_DECLINED_ACTION = "QUARANTINE_RELEASE_DECLINED";
    public static final String RELEASE_EXPIRED_ACTION = "QUARANTINE_RELEASE_EXPIRED";
    public static final String EXPOSURE_EXPORTED_ACTION = "QUARANTINE_EXPOSURE_EXPORTED";
    public static final String INCIDENT_TARGET = "QUARANTINE_INCIDENT";

    public static final int NAME_MAX = 200;
    public static final int REASON_MIN = 3;
    public static final int REASON_MAX = 500;
    public static final int FIELD_VALUE_MAX = 300;
    static final int LIST_LIMIT = 200;
    /** Task 0051: the review list and the exposure view show at most this many documents (their counts are complete). */
    public static final int REVIEW_LIMIT = 500;
    /** Task 0051: the background job writes this many rows per batch, each batch in its own transaction. */
    static final int BATCH_SIZE = 1000;
    /** Task 0051: rows read per page when a large incident is counted. */
    static final int PAGE_SIZE = 2000;
    static final String PREPARING = "This incident is still being prepared. Its whole release can be asked for once it is ready.";
    static final String CSV_HEADER = "Incident,Document ID,Identifier,Identifier value,Level,Received (UTC),Pages,"
            + "What happened before the quarantine,Person,When (UTC)";
    private static final Set<String> NOT_QUARANTINABLE = Set.of("EXPIRED_DELETED", "RETENTION_DELETING");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final QuarantineRepository quarantine;
    private final DocumentAccessGuard access;
    private final MetadataFieldDefinitionRepository definitions;
    private final AuditEventPort audit;
    private final ApprovalSettings settings;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public QuarantineService(QuarantineRepository quarantine, DocumentAccessGuard access,
                             MetadataFieldDefinitionRepository definitions, AuditEventPort audit,
                             ApprovalSettings settings, PlatformTransactionManager transactionManager) {
        this(quarantine, access, definitions, audit, settings, transactionManager, Clock.systemUTC());
    }

    QuarantineService(QuarantineRepository quarantine, DocumentAccessGuard access,
                      MetadataFieldDefinitionRepository definitions, AuditEventPort audit, ApprovalSettings settings,
                      PlatformTransactionManager transactionManager, Clock clock) {
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
        this.access = Objects.requireNonNull(access, "access");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = clock;
    }

    /** Who is acting: the server's clearance decides the levels; the web layer has already checked the capability. */
    public record Actor(UUID tenantId, String actorId, String displayName, Clearance clearance, String correlationId) {
        public Actor {
            if (tenantId == null) throw new CodedRefusal.Forbidden(SdvErrorCode.QUA_002, "No entity is selected.");
            if (actorId == null || actorId.isBlank()) throw new CodedRefusal.Forbidden(SdvErrorCode.QUA_002, "Sign in first.");
            clearance = clearance == null ? Clearance.STANDARD_ONLY : clearance;
        }
    }

    // ------------------------------------------------------------------ quarantine one document

    /**
     * Quarantines one document: into the open incident {@code incidentReference}, or a new incident of one named
     * {@code name} with {@code reason}.
     */
    public IncidentView quarantineDocument(Actor actor, String documentReference, String incidentReference, String name,
                                           String reason) {
        UUID documentId = parse(documentReference, SdvErrorCode.QUA_005, NO_DOCUMENT);
        UUID incidentId = blank(incidentReference) ? null : parse(incidentReference, SdvErrorCode.QUA_007, NO_INCIDENT);
        String incidentName = incidentId == null ? name(name) : null;
        String why = incidentId == null ? reason(reason) : null;
        Instant now = clock.instant();
        UUID id = transactions.execute(status -> {
            DocumentFacts document = quarantine.lockDocument(actor.tenantId(), documentId)
                    .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_005, NO_DOCUMENT));
            requireReviewable(actor, document);
            requireQuarantinable(actor, document);
            UUID target;
            if (incidentId == null) {
                target = createIncident(actor, incidentName, why, "DOCUMENT", now, 1);
            } else {
                Incident incident = quarantine.lockIncident(actor.tenantId(), incidentId)
                        .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_007, NO_INCIDENT));
                if (!incident.open()) throw new CodedRefusal.Conflict(SdvErrorCode.QUA_008, INCIDENT_RELEASED);
                target = incident.incidentId();
            }
            place(actor, target, document, now);
            return target;
        });
        return incident(actor, id.toString());
    }

    // ------------------------------------------------------------------ quarantine by filter

    /** The filter as the web layer sends it: received dates (ISO days, UTC), a sending system, field values. */
    public record FilterRequest(String receivedFrom, String receivedTo, String sourceId, Map<String, String> fields) { }

    /** Task 0051: the count alone; there is no limit. */
    public record MatchCount(long count) { }

    /** The server's count of documents the filter would quarantine now. */
    public MatchCount countMatching(Actor actor, FilterRequest request) {
        Filter filter = filter(actor.tenantId(), request);
        return new MatchCount(quarantine.countMatching(actor.tenantId(), filter));
    }

    /**
     * Quarantines every document matching the filter into a new incident, only when the count is still
     * {@code expectedCount} (what the person saw and selected); otherwise nothing is quarantined (SDV-QUA-009).
     * <p>
     * Task 0051: no limit. The incident stores the filter and the cut-off, so every matching document is quarantined
     * when this returns; the first batch of rows is written at once, the rest by {@link #prepare} in the background.
     * A filter can take documents of every level, so only someone who can review every level quarantines by filter.
     */
    public IncidentView quarantineByFilter(Actor actor, FilterRequest request, Long expectedCount, String name,
                                           String reason) {
        Filter filter = filter(actor.tenantId(), request);
        String incidentName = name(name);
        String why = reason(reason);
        if (expectedCount == null || expectedCount < 1) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.QUA_004, "Count the matching documents first, then select them all.");
        }
        if (!actor.clearance().canReviewQuarantined(SensitivityLevel.HIGHLY_CONFIDENTIAL)) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.QUA_002, LEVEL_REFUSED);
        }
        Instant now = clock.instant();
        UUID id = transactions.execute(status -> {
            MatchStats stats = quarantine.matchStats(actor.tenantId(), filter);
            if (stats.count() != expectedCount || stats.latestReceivedAt() == null) {
                throw new CodedRefusal.Conflict(SdvErrorCode.QUA_009, COUNT_CHANGED);
            }
            UUID incidentId = createIncident(actor, incidentName, why, "FILTER", now, stats.count());
            quarantine.startFilterBlocking(actor.tenantId(), incidentId, filter, stats.latestReceivedAt(), stats.count());
            return incidentId;
        });
        // The first batch now (a small incident is ready at once); a failure here is retried by the background job.
        try {
            prepareBatch(new IncidentRef(actor.tenantId(), id), actor.correlationId());
        } catch (RuntimeException ex) {
            // Nothing is lost: the documents are quarantined by the filter, and the job writes the rows.
        }
        return incident(actor, id.toString());
    }

    // ------------------------------------------------------------------ task 0051: the background job

    /**
     * Writes rows of incidents still being prepared, at most {@code maxBatches} batches in all, oldest incident first.
     * Each batch is its own transaction; a stop between batches loses nothing (the next run continues after the last
     * batch that committed). Returns the number of batches that wrote or looked at something.
     */
    public int prepare(int maxBatches) {
        int done = 0;
        for (IncidentRef ref : quarantine.findPreparing(Math.max(1, maxBatches))) {
            while (done < maxBatches) {
                Boolean more = prepareBatch(ref, "quarantine-prepare-" + ref.incidentId());
                if (more == null) break;
                done++;
                if (!more) break;
            }
            if (done >= maxBatches) break;
        }
        return done;
    }

    /** One batch: null when there was nothing to do (or another worker has it), else whether more is left. */
    private Boolean prepareBatch(IncidentRef ref, String correlationId) {
        return transactions.execute(status -> {
            Preparation p = quarantine.lockPreparation(ref.tenantId(), ref.incidentId()).orElse(null);
            if (p == null || !p.preparing() || !"OPEN".equals(p.status())) return null;
            Instant now = clock.instant();
            Instant at = now.isBefore(p.createdAt()) ? p.createdAt() : now;
            String byName = p.createdByName() == null ? p.createdBy() : p.createdByName();
            QuarantineRepository.Batch batch = quarantine.prepareBatch(p, BATCH_SIZE, p.createdBy(), byName, at);
            for (Placed placed : batch.placed()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("incidentId", p.incidentId().toString());
                details.put("quarantineId", placed.quarantineId().toString());
                details.put("byFilter", true);
                audit.append(new AuditEvent(UUID.randomUUID(), p.tenantId(), "USER", p.createdBy(), QUARANTINED_ACTION,
                        "DOCUMENT", placed.documentId().toString(), at, correlationId, AuditOutcome.SUCCESS, json(details),
                        p.createdByName()));
            }
            boolean finished = batch.scanned() < BATCH_SIZE;
            quarantine.advancePreparation(p.tenantId(), p.incidentId(), p.preparedCount() + batch.scanned(),
                    batch.lastDocumentId() == null ? p.cursorReceivedAt() : batch.lastReceivedAt(),
                    batch.lastDocumentId() == null ? p.cursorDocumentId() : batch.lastDocumentId(),
                    finished ? at : null);
            if (finished) {
                // Prepared: when nothing is left in force (all released, or quarantined elsewhere), the incident ends.
                Integer inForce = quarantine.countInForce(p.tenantId(), List.of(p.incidentId())).get(p.incidentId());
                if (inForce == null || inForce == 0) {
                    quarantine.closeIncident(p.tenantId(), p.incidentId(), at);
                    audit.append(new AuditEvent(UUID.randomUUID(), p.tenantId(), "SYSTEM", "vero", INCIDENT_CLOSED_ACTION,
                            INCIDENT_TARGET, p.incidentId().toString(), at, correlationId, AuditOutcome.SUCCESS, "{}"));
                }
            }
            return !finished;
        });
    }

    // ------------------------------------------------------------------ review

    /** Incidents, open first then newest, with the documents in force the person may review (others only counted). */
    public List<IncidentSummary> incidents(Actor actor) {
        List<Incident> incidents = quarantine.findIncidents(actor.tenantId(), LIST_LIMIT);
        Map<UUID, Integer> inForce = quarantine.countInForce(actor.tenantId(),
                incidents.stream().map(Incident::incidentId).toList());
        return incidents.stream().map(i -> {
            Progress progress = progress(actor.tenantId(), i.incidentId());
            return new IncidentSummary(i.incidentId(), i.name(), i.scope(), i.status(),
                    i.createdByName(), i.createdAt(), i.closedAt(), inForce.getOrDefault(i.incidentId(), 0),
                    quarantine.findPendingRequest(actor.tenantId(), i.incidentId()).isPresent(), progress.preparing(),
                    progress.prepared(), progress.expected());
        }).toList();
    }

    /** Task 0051: a filter incident's preparation; expected is null for an incident without a stored filter. */
    public record Progress(boolean preparing, long prepared, Long expected) {
        static final Progress NONE = new Progress(false, 0, null);
    }

    private Progress progress(UUID tenantId, UUID incidentId) {
        return quarantine.findPreparation(tenantId, incidentId)
                .map(p -> new Progress(p.preparing() && "OPEN".equals(p.status()),
                        Math.min(p.preparedCount(), p.expectedCount()), p.expectedCount()))
                .orElse(Progress.NONE);
    }

    /**
     * One incident for review: its documents of levels the person can open (at most {@link #REVIEW_LIMIT}, in force
     * first, oldest first); the rest only counted. The counts cover the whole incident.
     */
    public IncidentView incident(Actor actor, String incidentReference) {
        UUID incidentId = parse(incidentReference, SdvErrorCode.QUA_007, NO_INCIDENT);
        Incident incident = quarantine.findIncident(actor.tenantId(), incidentId)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_007, NO_INCIDENT));
        List<Quarantined> first = quarantine.findInIncident(actor.tenantId(), incidentId, REVIEW_LIMIT);
        Map<UUID, SensitivityLevel> levels = levels(actor.tenantId(), first.stream().map(Quarantined::document).toList());
        List<QuarantinedDocumentView> visible = new ArrayList<>();
        for (Quarantined q : first) {
            SensitivityLevel level = levels.get(q.document().documentId());
            if (actor.clearance().canReviewQuarantined(level)) visible.add(QuarantinedDocumentView.of(q, level));
        }
        long total = quarantine.countInIncident(actor.tenantId(), incidentId);
        int inForce = quarantine.countInForce(actor.tenantId(), List.of(incidentId)).getOrDefault(incidentId, 0);
        long[] hidden = {0};
        if (total <= first.size()) {
            hidden[0] = first.size() - visible.size();
        } else {
            forEachPage(actor.tenantId(), incidentId, false, page -> {
                Map<UUID, SensitivityLevel> l = levels(actor.tenantId(), page.stream().map(Quarantined::document).toList());
                for (Quarantined q : page) {
                    if (!actor.clearance().canReviewQuarantined(l.get(q.document().documentId()))) hidden[0]++;
                }
            });
        }
        ReleaseRequest pending = quarantine.findPendingRequest(actor.tenantId(), incidentId).orElse(null);
        Progress progress = progress(actor.tenantId(), incidentId);
        return new IncidentView(incident.incidentId(), incident.name(), incident.reason(), incident.scope(),
                incident.status(), incident.createdByName(), incident.createdAt(), incident.closedAt(), inForce,
                (int) Math.min(total, Integer.MAX_VALUE), (int) Math.min(hidden[0], Integer.MAX_VALUE), visible,
                pending == null ? null : pending.requestId(),
                pending == null ? null : pending.requestedByName(), pending == null ? null : pending.expiresAt(),
                progress.preparing(), progress.prepared(), progress.expected(), REVIEW_LIMIT);
    }

    /** Every row of the incident (or those in force), a page at a time, so a large incident is never all in memory. */
    private void forEachPage(UUID tenantId, UUID incidentId, boolean inForceOnly, Consumer<List<Quarantined>> each) {
        UUID after = null;
        while (true) {
            List<Quarantined> page = quarantine.findInIncidentPage(tenantId, incidentId, inForceOnly, after, PAGE_SIZE);
            if (page.isEmpty()) return;
            each.accept(page);
            if (page.size() < PAGE_SIZE) return;
            after = page.getLast().quarantineId();
        }
    }

    // ------------------------------------------------------------------ release one document

    public QuarantinedDocumentView releaseDocument(Actor actor, String documentReference, String reason) {
        UUID documentId = parse(documentReference, SdvErrorCode.QUA_005, NO_DOCUMENT);
        String why = reason(reason);
        Instant now = clock.instant();
        return transactions.execute(status -> {
            // Task 0051: a document a filter incident quarantines before its row is written. The incident is locked
            // before the document (the order the background job takes), and its row is written and released here.
            Optional<UUID> byFilter = quarantine.findActive(actor.tenantId(), documentId).isPresent()
                    ? Optional.empty() : quarantine.blockingIncident(actor.tenantId(), documentId);
            byFilter.ifPresent(incidentId -> quarantine.lockIncident(actor.tenantId(), incidentId));
            DocumentFacts document = quarantine.lockDocument(actor.tenantId(), documentId)
                    .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_005, NO_DOCUMENT));
            SensitivityLevel level = requireReviewable(actor, document);
            if (byFilter.isPresent() && quarantine.findActive(actor.tenantId(), documentId).isEmpty()
                    && quarantine.blockingIncident(actor.tenantId(), documentId).filter(byFilter.get()::equals).isPresent()) {
                Preparation p = quarantine.findPreparation(actor.tenantId(), byFilter.get())
                        .orElseThrow(() -> new InternalCheckFailed("A filter incident has no filter."));
                UUID quarantineId = UUID.randomUUID();
                quarantine.insertQuarantine(quarantineId, actor.tenantId(), p.incidentId(), documentId, p.createdBy(),
                        p.createdByName() == null ? p.createdBy() : p.createdByName(), p.createdAt());
                Map<String, Object> placed = new LinkedHashMap<>();
                placed.put("incidentId", p.incidentId().toString());
                placed.put("quarantineId", quarantineId.toString());
                placed.put("byFilter", true);
                append(actor, QUARANTINED_ACTION, "DOCUMENT", documentId.toString(), now.isBefore(p.createdAt()) ? p.createdAt() : now, placed);
            }
            Quarantined q = quarantine.findActive(actor.tenantId(), documentId)
                    .orElseThrow(() -> new CodedRefusal.Conflict(SdvErrorCode.QUA_011, NOT_QUARANTINED));
            if (byFilter.isEmpty()) quarantine.lockIncident(actor.tenantId(), q.incidentId());
            Instant at = now.isBefore(q.quarantinedAt()) ? q.quarantinedAt() : now;
            if (!quarantine.release(actor.tenantId(), q.quarantineId(), actor.actorId(), displayName(actor), why, null, at)) {
                throw new CodedRefusal.Conflict(SdvErrorCode.QUA_011, NOT_QUARANTINED);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("incidentId", q.incidentId().toString());
            details.put("quarantineId", q.quarantineId().toString());
            details.put("quarantinedAt", q.quarantinedAt().toString());
            append(actor, RELEASED_ACTION, "DOCUMENT", documentId.toString(), at, details);
            closeIfEmpty(actor, q.incidentId(), at);
            return QuarantinedDocumentView.of(new Quarantined(q.quarantineId(), q.incidentId(), document,
                    q.quarantinedBy(), q.quarantinedByName(), q.quarantinedAt(), actor.actorId(), displayName(actor), at), level);
        });
    }

    // ------------------------------------------------------------------ release a whole incident

    /** A Steward asks for the whole incident to be released; a Business Admin decides. One pending request at a time. */
    public IncidentView requestIncidentRelease(Actor actor, String incidentReference, String reason) {
        UUID incidentId = parse(incidentReference, SdvErrorCode.QUA_007, NO_INCIDENT);
        String why = reason(reason);
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            expire(actor.tenantId(), now, actor.correlationId());
            Incident incident = quarantine.lockIncident(actor.tenantId(), incidentId)
                    .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_007, NO_INCIDENT));
            if (!incident.open()) throw new CodedRefusal.Conflict(SdvErrorCode.QUA_008, INCIDENT_RELEASED);
            if (quarantine.findPendingRequest(actor.tenantId(), incidentId).isPresent()) {
                throw new CodedRefusal.Conflict(SdvErrorCode.QUA_010, "A release of this incident is already waiting for a decision.");
            }
            // Task 0051: the whole incident must be prepared first, so the request covers every one of its documents.
            if (progress(actor.tenantId(), incidentId).preparing()) {
                throw new CodedRefusal.Conflict(SdvErrorCode.QUA_010, PREPARING);
            }
            long[] inForce = {0};
            forEachPage(actor.tenantId(), incidentId, true, page -> {
                Map<UUID, SensitivityLevel> levels = levels(actor.tenantId(), page.stream().map(Quarantined::document).toList());
                for (Quarantined q : page) {
                    if (!actor.clearance().canReviewQuarantined(levels.get(q.document().documentId()))) {
                        throw new CodedRefusal.Forbidden(SdvErrorCode.QUA_002,
                                "This incident holds documents of a level you can't open, so you can't ask for its release.");
                    }
                    inForce[0]++;
                }
            });
            ReleaseRequest request = new ReleaseRequest(UUID.randomUUID(), actor.tenantId(), incidentId, why,
                    actor.actorId(), displayName(actor), now, "PENDING", null, null, null,
                    now.plus(settings.pendingWindow(actor.tenantId())));
            quarantine.insertRequest(request);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("requestId", request.requestId().toString());
            details.put("documentCount", inForce[0]);
            details.put("expiresAt", request.expiresAt().toString());
            append(actor, RELEASE_REQUESTED_ACTION, INCIDENT_TARGET, incidentId.toString(), now, details);
        });
        return incident(actor, incidentId.toString());
    }

    /**
     * Release requests waiting for a Business Admin, oldest first: the incident name, the document count, levels and
     * dates, who asked. Never a reason, title, file name or content.
     */
    public List<ReleaseRequestView> pendingReleaseRequests(Actor actor) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> expire(actor.tenantId(), now, actor.correlationId()));
        return quarantine.findPendingRequests(actor.tenantId(), LIST_LIMIT).stream()
                .map(r -> requestView(actor.tenantId(), r)).toList();
    }

    /** One request decided by a Business Admin who didn't make it; never in bulk. Approving releases every document. */
    public ReleaseRequestView decide(Actor actor, String requestReference, boolean approve) {
        UUID requestId = parse(requestReference, SdvErrorCode.QUA_010, NO_REQUEST);
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> expire(actor.tenantId(), now, actor.correlationId()));
        return transactions.execute(status -> {
            ReleaseRequest request = quarantine.lockRequest(actor.tenantId(), requestId)
                    .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_010, NO_REQUEST));
            if (!"PENDING".equals(request.status())) {
                throw new CodedRefusal.Conflict(SdvErrorCode.QUA_010, "This request was already decided, or it expired.");
            }
            if (request.requestedBy().equals(actor.actorId())) {
                throw new CodedRefusal.Conflict(SdvErrorCode.QUA_010, "Nobody can decide their own request.");
            }
            Incident incident = quarantine.lockIncident(actor.tenantId(), request.incidentId())
                    .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_007, NO_INCIDENT));
            String decision = approve ? "APPROVED" : "DECLINED";
            if (!quarantine.decideRequest(actor.tenantId(), requestId, decision, actor.actorId(), displayName(actor), now)) {
                throw new CodedRefusal.Conflict(SdvErrorCode.QUA_010, "This request was already decided, or it expired.");
            }
            if (approve && incident.open() && progress(actor.tenantId(), incident.incidentId()).preparing()) {
                throw new CodedRefusal.Conflict(SdvErrorCode.QUA_010, PREPARING);
            }
            int released = 0;
            if (approve && incident.open()) {
                // Task 0051: every row in force at once (the request's reason is the release reason, kept here, never
                // in the audit); each document's release is still audited.
                for (Released r : quarantine.releaseAllInForce(actor.tenantId(), incident.incidentId(), actor.actorId(),
                        displayName(actor), request.reason(), requestId, now)) {
                    released++;
                    Instant at = now.isBefore(r.quarantinedAt()) ? r.quarantinedAt() : now;
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("incidentId", incident.incidentId().toString());
                    details.put("quarantineId", r.quarantineId().toString());
                    details.put("requestId", requestId.toString());
                    append(actor, RELEASED_ACTION, "DOCUMENT", r.documentId().toString(), at, details);
                }
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("requestId", requestId.toString());
            details.put("requestedBy", request.requestedBy());
            if (approve) details.put("releasedCount", released);
            append(actor, approve ? RELEASE_APPROVED_ACTION : RELEASE_DECLINED_ACTION, INCIDENT_TARGET,
                    incident.incidentId().toString(), now, details);
            // Approved: the incident ends, and with it its filter's blocking (task 0051).
            if (approve) closeIfEmpty(actor, incident.incidentId(), now);
            ReleaseRequest decided = new ReleaseRequest(request.requestId(), request.tenantId(), request.incidentId(),
                    request.reason(), request.requestedBy(), request.requestedByName(), request.requestedAt(), decision,
                    actor.actorId(), displayName(actor), now, request.expiresAt());
            return requestView(actor.tenantId(), decided);
        });
    }

    // ------------------------------------------------------------------ exposure report

    /**
     * Per incident: documents by level, and per document who opened, printed or downloaded it before its quarantine.
     * Task 0051: lists at most {@link #REVIEW_LIMIT} documents (the export has them all); the counts are complete.
     */
    public ExposureReport exposure(Actor actor, String incidentReference) {
        return exposure(actor, incidentReference, REVIEW_LIMIT);
    }

    private ExposureReport exposure(Actor actor, String incidentReference, Integer listLimit) {
        UUID incidentId = parse(incidentReference, SdvErrorCode.QUA_007, NO_INCIDENT);
        Incident incident = quarantine.findIncident(actor.tenantId(), incidentId)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_007, NO_INCIDENT));
        Map<UUID, List<ExposureEntry>> byDocument = new LinkedHashMap<>();
        for (Exposure e : quarantine.findExposureInIncident(actor.tenantId(), incidentId)) {
            byDocument.computeIfAbsent(e.documentId(), k -> new ArrayList<>())
                    .add(new ExposureEntry(e.action(), actionWords(e.action()), e.actorName() == null ? e.actorId() : e.actorName(), e.occurredAt()));
        }
        Map<SensitivityLevel, Integer> counts = new EnumMap<>(SensitivityLevel.class);
        for (SensitivityLevel l : SensitivityLevel.values()) counts.put(l, 0);
        List<ExposureDocument> documents = new ArrayList<>();
        int[] hidden = {0};
        int[] visible = {0};
        forEachPage(actor.tenantId(), incidentId, false, page -> {
            Map<UUID, SensitivityLevel> levels = levels(actor.tenantId(), page.stream().map(Quarantined::document).toList());
            for (Quarantined q : page) {
                DocumentFacts d = q.document();
                SensitivityLevel level = levels.get(d.documentId());
                if (!actor.clearance().canReviewQuarantined(level)) {
                    hidden[0]++;
                    continue;
                }
                visible[0]++;
                counts.merge(level, 1, Integer::sum);
                if (listLimit != null && documents.size() >= listLimit) continue;
                documents.add(new ExposureDocument(d.documentId(), d.primaryIdentifierLabel(), d.primaryIdentifier(), level,
                        d.receivedAt(), d.pageCount(), q.quarantinedAt(), q.inForce(),
                        byDocument.getOrDefault(d.documentId(), List.of())));
            }
        });
        // Oldest quarantine first, then by received time (the pages come by quarantine ID).
        documents.sort(java.util.Comparator.comparing(ExposureDocument::quarantinedAt)
                .thenComparing(ExposureDocument::receivedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(ExposureDocument::documentId));
        return new ExposureReport(incident.incidentId(), incident.name(), incident.status(), incident.createdAt(),
                counts.get(SensitivityLevel.STANDARD), counts.get(SensitivityLevel.CONFIDENTIAL),
                counts.get(SensitivityLevel.HIGHLY_CONFIDENTIAL), hidden[0], documents, visible[0]);
    }

    public record Export(byte[] csv, int rowCount, String filename, UUID reference, String fingerprint) { }

    /** The exposure report as CSV, with a reference and the SHA-256 of the file; the export is audited. */
    public Export exportExposure(Actor actor, String incidentReference) {
        ExposureReport report = exposure(actor, incidentReference, null);
        StringBuilder out = new StringBuilder("﻿").append(CSV_HEADER).append("\r\n");
        int rows = 0;
        for (ExposureDocument d : report.documents()) {
            List<ExposureEntry> entries = d.exposure().isEmpty()
                    ? List.of(new ExposureEntry(null, "Not opened, printed or downloaded", "", null)) : d.exposure();
            for (ExposureEntry e : entries) {
                out.append(AuditCsv.cell(report.name())).append(',')
                        .append(AuditCsv.cell(d.documentId().toString())).append(',')
                        .append(AuditCsv.cell(nullToEmpty(d.primaryIdentifierLabel()))).append(',')
                        .append(AuditCsv.cell(nullToEmpty(d.primaryIdentifier()))).append(',')
                        .append(AuditCsv.cell(levelWords(d.level()))).append(',')
                        .append(AuditCsv.cell(d.receivedAt() == null ? "" : d.receivedAt().toString())).append(',')
                        .append(d.pageCount() == null ? "" : d.pageCount().toString()).append(',')
                        .append(AuditCsv.cell(e.what())).append(',')
                        .append(AuditCsv.cell(nullToEmpty(e.person()))).append(',')
                        .append(AuditCsv.cell(e.at() == null ? "" : e.at().toString())).append("\r\n");
                rows++;
            }
        }
        byte[] csv = out.toString().getBytes(StandardCharsets.UTF_8);
        String fingerprint = sha256(csv);
        UUID reference = UUID.randomUUID();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", reference.toString());
        details.put("fingerprint", fingerprint);
        details.put("rowCount", rows);
        details.put("documentCount", report.documents().size());
        append(actor, EXPOSURE_EXPORTED_ACTION, INCIDENT_TARGET, report.incidentId().toString(), clock.instant(), details);
        String filename = "vero-quarantine-exposure-" + LocalDate.now(clock.withZone(ZoneOffset.UTC)) + "-"
                + reference.toString().substring(0, 8) + ".csv";
        return new Export(csv, rows, filename, reference, fingerprint);
    }

    // ------------------------------------------------------------------ views

    public record IncidentSummary(UUID incidentId, String name, String scope, String status, String createdByName,
                                  Instant createdAt, Instant closedAt, int documentsInForce, boolean releaseRequested,
                                  boolean preparing, long preparedCount, Long expectedCount) { }

    /**
     * hiddenCount: documents of levels the person can't open (counted, never shown). Task 0051: preparing while the
     * background job writes the rows (preparedCount of expectedCount looked at); documents lists at most listLimit.
     */
    public record IncidentView(UUID incidentId, String name, String reason, String scope, String status,
                               String createdByName, Instant createdAt, Instant closedAt, int documentsInForce,
                               int documentCount, int hiddenCount, List<QuarantinedDocumentView> documents,
                               UUID pendingRequestId, String pendingRequestedByName, Instant pendingExpiresAt,
                               boolean preparing, long preparedCount, Long expectedCount, int listLimit) { }

    public record QuarantinedDocumentView(UUID quarantineId, UUID documentId, String primaryIdentifierLabel,
                                          String primaryIdentifier, SensitivityLevel level, Instant receivedAt,
                                          Integer pageCount, String quarantinedByName, Instant quarantinedAt,
                                          boolean inForce, String releasedByName, Instant releasedAt) {
        static QuarantinedDocumentView of(Quarantined q, SensitivityLevel level) {
            DocumentFacts d = q.document();
            return new QuarantinedDocumentView(q.quarantineId(), d.documentId(), d.primaryIdentifierLabel(),
                    d.primaryIdentifier(), level, d.receivedAt(), d.pageCount(), q.quarantinedByName(),
                    q.quarantinedAt(), q.inForce(), q.releasedByName(), q.releasedAt());
        }
    }

    /** What a Business Admin sees: never a reason, title, file name or content. */
    public record ReleaseRequestView(UUID requestId, UUID incidentId, String incidentName, int documentCount,
                                     int standard, int confidential, int highlyConfidential,
                                     Instant incidentCreatedAt, Instant firstReceivedAt, Instant lastReceivedAt,
                                     String requestedByName, Instant requestedAt, Instant expiresAt, String status,
                                     String decidedByName, Instant decidedAt) { }

    public record ExposureReport(UUID incidentId, String name, String status, Instant createdAt, int standard,
                                 int confidential, int highlyConfidential, int hiddenCount,
                                 List<ExposureDocument> documents, int documentCount) { }

    public record ExposureDocument(UUID documentId, String primaryIdentifierLabel, String primaryIdentifier,
                                   SensitivityLevel level, Instant receivedAt, Integer pageCount, Instant quarantinedAt,
                                   boolean inForce, List<ExposureEntry> exposure) { }

    /** action: the audit action (DOCUMENT_VIEW_OPENED, DOCUMENT_PRINTED, DOCUMENT_DOWNLOADED); what: in words. */
    public record ExposureEntry(String action, String what, String person, Instant at) { }

    // ------------------------------------------------------------------ inside

    private SensitivityLevel requireReviewable(Actor actor, DocumentFacts document) {
        SensitivityLevel level = levels(actor.tenantId(), List.of(document)).get(document.documentId());
        if (!actor.clearance().canReviewQuarantined(level)) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.QUA_002, LEVEL_REFUSED);
        }
        return level;
    }

    private void requireQuarantinable(Actor actor, DocumentFacts document) {
        if (NOT_QUARANTINABLE.contains(document.status())) {
            throw new CodedRefusal.Conflict(SdvErrorCode.QUA_006,
                    "This document was deleted by the retention cleanup, or is being deleted, so it can't be quarantined.");
        }
        if (quarantine.isQuarantined(actor.tenantId(), document.documentId())) {
            throw new CodedRefusal.Conflict(SdvErrorCode.QUA_006, "This document is already quarantined.");
        }
    }

    private UUID createIncident(Actor actor, String name, String reason, String scope, Instant now, long documents) {
        Incident incident = new Incident(UUID.randomUUID(), actor.tenantId(), name, reason, scope, actor.actorId(),
                displayName(actor), now, "OPEN", null);
        quarantine.insertIncident(incident);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("scope", scope);
        details.put("documentCount", documents);
        append(actor, INCIDENT_CREATED_ACTION, INCIDENT_TARGET, incident.incidentId().toString(), now, details);
        return incident.incidentId();
    }

    private void place(Actor actor, UUID incidentId, DocumentFacts document, Instant now) {
        UUID quarantineId = UUID.randomUUID();
        quarantine.insertQuarantine(quarantineId, actor.tenantId(), incidentId, document.documentId(), actor.actorId(),
                displayName(actor), now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("incidentId", incidentId.toString());
        details.put("quarantineId", quarantineId.toString());
        append(actor, QUARANTINED_ACTION, "DOCUMENT", document.documentId().toString(), now, details);
    }

    private void closeIfEmpty(Actor actor, UUID incidentId, Instant at) {
        // Task 0051: an incident still being prepared stays open (its filter quarantines the documents not yet written).
        if (progress(actor.tenantId(), incidentId).preparing()) return;
        if (quarantine.countInForce(actor.tenantId(), List.of(incidentId)).getOrDefault(incidentId, 0) > 0) return;
        quarantine.closeIncident(actor.tenantId(), incidentId, at);
        append(actor, INCIDENT_CLOSED_ACTION, INCIDENT_TARGET, incidentId.toString(), at, new LinkedHashMap<>());
        // A request still waiting for an incident that is now empty has nothing left to release: it ends as expired.
        for (ReleaseRequest r : quarantine.expirePending(actor.tenantId(), incidentId)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("requestId", r.requestId().toString());
            details.put("requestedBy", r.requestedBy());
            details.put("incidentClosed", true);
            audit.append(new AuditEvent(UUID.randomUUID(), actor.tenantId(), "SYSTEM", "vero", RELEASE_EXPIRED_ACTION,
                    INCIDENT_TARGET, incidentId.toString(), at, actor.correlationId(), AuditOutcome.SUCCESS, json(details)));
        }
    }

    /** Pending requests whose time ran out become EXPIRED, each audited (by the system). */
    private void expire(UUID tenantId, Instant now, String correlationId) {
        for (ReleaseRequest r : quarantine.expireRequests(tenantId, now)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("requestId", r.requestId().toString());
            details.put("requestedBy", r.requestedBy());
            audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "SYSTEM", "vero", RELEASE_EXPIRED_ACTION,
                    INCIDENT_TARGET, r.incidentId().toString(), now, correlationId, AuditOutcome.SUCCESS, json(details)));
        }
    }

    private ReleaseRequestView requestView(UUID tenantId, ReleaseRequest r) {
        Incident incident = quarantine.findIncident(tenantId, r.incidentId())
                .orElseThrow(() -> new InternalCheckFailed("A quarantine release request names no incident."));
        // Waiting: the documents still quarantined (what approving releases); decided: every document of the incident.
        // Task 0051: counted a page at a time.
        int[] n = {0, 0, 0, 0};
        Instant[] range = {null, null};
        forEachPage(tenantId, r.incidentId(), "PENDING".equals(r.status()), page -> {
            Map<UUID, SensitivityLevel> levels = levels(tenantId, page.stream().map(Quarantined::document).toList());
            for (Quarantined q : page) {
                SensitivityLevel level = levels.get(q.document().documentId());
                n[0]++;
                if (level == SensitivityLevel.STANDARD) n[1]++;
                else if (level == SensitivityLevel.CONFIDENTIAL) n[2]++;
                else n[3]++;
                Instant received = q.document().receivedAt();
                if (received != null) {
                    range[0] = range[0] == null || received.isBefore(range[0]) ? received : range[0];
                    range[1] = range[1] == null || received.isAfter(range[1]) ? received : range[1];
                }
            }
        });
        return new ReleaseRequestView(r.requestId(), incident.incidentId(), incident.name(), n[0], n[1], n[2], n[3],
                incident.createdAt(), range[0], range[1], r.requestedByName(), r.requestedAt(), r.expiresAt(), r.status(),
                r.decidedByName(), r.decidedAt());
    }

    private Filter filter(UUID tenantId, FilterRequest request) {
        FilterRequest f = request == null ? new FilterRequest(null, null, null, null) : request;
        LocalDate from = day(f.receivedFrom());
        LocalDate to = day(f.receivedTo());
        if (from == null || to == null) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.QUA_004, "Give the received dates: from and to.");
        }
        if (to.isBefore(from)) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.QUA_004, "The 'to' date is before the 'from' date.");
        }
        UUID sourceId = blank(f.sourceId()) ? null : parse(f.sourceId(), SdvErrorCode.QUA_004, "This sending system isn't known.");
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> asked = f.fields() == null ? Map.of() : f.fields();
        if (!asked.isEmpty() && sourceId == null) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.QUA_004, "Choose the sending system before filtering by its fields.");
        }
        for (Map.Entry<String, String> e : asked.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue().strip();
            if (value.isEmpty()) continue;
            if (value.length() > FIELD_VALUE_MAX) {
                throw new CodedRefusal.BadRequest(SdvErrorCode.QUA_004, "A field value is too long.");
            }
            MetadataFieldDefinition field = definitions.findCurrent(tenantId, sourceId, e.getKey())
                    .filter(MetadataFieldDefinition::searchable)
                    .orElseThrow(() -> new CodedRefusal.BadRequest(SdvErrorCode.QUA_004,
                            "This field can't be searched for this sending system."));
            String normalized = SearchValueNormalizer.normalize(field.normalization(), value);
            if (normalized.isBlank()) {
                throw new CodedRefusal.BadRequest(SdvErrorCode.QUA_004, "A field value is empty after it is normalized.");
            }
            fields.put(field.fieldKey(), normalized);
        }
        return new Filter(from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), sourceId, fields);
    }

    private static LocalDate day(String value) {
        if (blank(value)) return null;
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException ex) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.QUA_004, "Give dates as YYYY-MM-DD.");
        }
    }

    private Map<UUID, SensitivityLevel> levels(UUID tenantId, List<DocumentFacts> documents) {
        if (documents.isEmpty()) return Map.of();
        return access.effectiveLevels(tenantId, documents.stream()
                .map(d -> new DocumentAccessGuard.DocumentLevelRef(d.documentId(), d.sourceId(), d.submissionId(), d.ownLevel()))
                .distinct().toList());
    }

    private void append(Actor actor, String action, String targetType, String targetId, Instant at,
                        Map<String, Object> details) {
        audit.append(new AuditEvent(UUID.randomUUID(), actor.tenantId(), "USER", actor.actorId(), action, targetType,
                targetId, at, actor.correlationId(), AuditOutcome.SUCCESS, json(details), actor.displayName()));
    }

    private static String json(Map<String, Object> details) {
        try {
            return JSON.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw InternalCheckFailed.auditNotWritten("The quarantine change could not be audited, so nothing was changed.", ex);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new InternalCheckFailed("SHA-256 is not available.");
        }
    }

    static String actionWords(String action) {
        return switch (action) {
            case "DOCUMENT_VIEW_OPENED" -> "Opened";
            case "DOCUMENT_PRINTED" -> "Printed";
            case "DOCUMENT_DOWNLOADED" -> "Downloaded";
            default -> action;
        };
    }

    static String levelWords(SensitivityLevel level) {
        if (level == null) return "";
        return switch (level) {
            case STANDARD -> "Standard";
            case CONFIDENTIAL -> "Confidential";
            case HIGHLY_CONFIDENTIAL -> "Highly Confidential";
        };
    }

    private static String displayName(Actor actor) {
        String name = actor.displayName() == null ? "" : actor.displayName().strip();
        return name.isEmpty() ? actor.actorId() : name;
    }

    private static String name(String name) {
        String value = name == null ? "" : name.strip();
        if (value.isEmpty() || value.length() > NAME_MAX) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.QUA_004, "Name the incident (1 to " + NAME_MAX + " characters).");
        }
        return value;
    }

    private static String reason(String reason) {
        String value = reason == null ? "" : reason.strip();
        if (value.length() < REASON_MIN || value.length() > REASON_MAX) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.QUA_004,
                    "Give a reason of " + REASON_MIN + " to " + REASON_MAX + " characters.");
        }
        return value;
    }

    private static UUID parse(String reference, SdvErrorCode code, String message) {
        try {
            return UUID.fromString(reference == null ? "" : reference.strip());
        } catch (IllegalArgumentException ex) {
            if (code == SdvErrorCode.QUA_004) throw new CodedRefusal.BadRequest(code, message);
            throw new CodedRefusal.NotFound(code, message);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static final String NO_DOCUMENT = "No document has this reference.";
    static final String NO_INCIDENT = "No quarantine incident has this reference.";
    static final String NO_REQUEST = "No release request has this reference.";
    static final String LEVEL_REFUSED = "Only a Steward who can open this document's level can quarantine, review or release it.";
    static final String INCIDENT_RELEASED = "This incident was released, so nothing can be added to it.";
    static final String COUNT_CHANGED = "The matching documents changed since they were counted. Count them again.";
    static final String NOT_QUARANTINED = "This document isn't quarantined.";
}
