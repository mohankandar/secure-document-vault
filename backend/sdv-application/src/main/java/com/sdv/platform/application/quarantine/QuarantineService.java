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
import com.sdv.platform.application.port.QuarantineRepository.Counts;
import com.sdv.platform.application.port.QuarantineRepository.DocumentFacts;
import com.sdv.platform.application.port.QuarantineRepository.Exposure;
import com.sdv.platform.application.port.QuarantineRepository.Filter;
import com.sdv.platform.application.port.QuarantineRepository.Incident;
import com.sdv.platform.application.port.QuarantineRepository.IncidentRef;
import com.sdv.platform.application.port.QuarantineRepository.LevelGap;
import com.sdv.platform.application.port.QuarantineRepository.MatchStats;
import com.sdv.platform.application.port.QuarantineRepository.PageKey;
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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 * still matches it.
 * <p>
 * Task 0052: usable at a million documents. The incident keeps its counts (changeset 039, kept by the database as rows
 * are written and released), so no screen reads the whole incident; the review list and the exposure view come a page
 * of {@link #PAGE} at a time; an approved whole release runs in batches in the background ({@link #release}),
 * continues after a restart and releases nothing twice; the exposure CSV streams and its fingerprint is worked out
 * while it is written.
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
    /** Task 0052: written before the first byte of a streamed export leaves (the finished one carries the fingerprint). */
    public static final String EXPOSURE_EXPORT_STARTED_ACTION = "QUARANTINE_EXPOSURE_EXPORT_STARTED";
    public static final String INCIDENT_TARGET = "QUARANTINE_INCIDENT";

    public static final int NAME_MAX = 200;
    public static final int REASON_MIN = 3;
    public static final int REASON_MAX = 500;
    public static final int FIELD_VALUE_MAX = 300;
    static final int LIST_LIMIT = 200;
    /** Task 0052: the review list and the exposure view come a page of this many documents at a time. */
    public static final int PAGE = 100;
    /** Task 0051: the background job writes (task 0052: and releases) this many rows per batch, each in its own transaction. */
    static final int BATCH_SIZE = 1000;
    /** Task 0052: rows read per page while the export streams. */
    static final int EXPORT_PAGE = 1000;
    static final String PREPARING = "This incident is still being prepared. Its whole release can be asked for once it is ready.";
    static final String RELEASING = "This incident is being released. Nothing can be added to it or asked of it until that is done.";
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
            SensitivityLevel level = requireReviewable(actor, document);
            requireQuarantinable(actor, document);
            UUID target;
            if (incidentId == null) {
                target = createIncident(actor, incidentName, why, "DOCUMENT", now, 1);
            } else {
                Incident incident = quarantine.lockIncident(actor.tenantId(), incidentId)
                        .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_007, NO_INCIDENT));
                if (!incident.open()) throw new CodedRefusal.Conflict(SdvErrorCode.QUA_008, INCIDENT_RELEASED);
                // Task 0052: an incident being released in the background takes nothing more.
                if (counts(actor.tenantId(), incident.incidentId()).releasing()) {
                    throw new CodedRefusal.Conflict(SdvErrorCode.QUA_008, RELEASING);
                }
                target = incident.incidentId();
            }
            place(actor, target, document, level, now);
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

    // ------------------------------------------------------------------ task 0051: the background jobs

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
            // Task 0052: each row keeps its document's level now, for the incident's counts.
            QuarantineRepository.Batch batch = quarantine.prepareBatch(p, BATCH_SIZE, p.createdBy(), byName, at,
                    documents -> levels(p.tenantId(), documents));
            List<AuditEvent> events = new ArrayList<>(batch.placed().size());
            for (Placed placed : batch.placed()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("incidentId", p.incidentId().toString());
                details.put("quarantineId", placed.quarantineId().toString());
                details.put("byFilter", true);
                events.add(new AuditEvent(UUID.randomUUID(), p.tenantId(), "USER", p.createdBy(), QUARANTINED_ACTION,
                        "DOCUMENT", placed.documentId().toString(), at, correlationId, AuditOutcome.SUCCESS, json(details),
                        p.createdByName()));
            }
            audit.appendAll(events);
            boolean finished = batch.scanned() < BATCH_SIZE;
            quarantine.advancePreparation(p.tenantId(), p.incidentId(), p.preparedCount() + batch.scanned(),
                    batch.lastDocumentId() == null ? p.cursorReceivedAt() : batch.lastReceivedAt(),
                    batch.lastDocumentId() == null ? p.cursorDocumentId() : batch.lastDocumentId(),
                    finished ? at : null);
            if (finished) {
                // Prepared: when nothing is left in force (all released, or quarantined elsewhere), the incident ends.
                if (counts(p.tenantId(), p.incidentId()).inForce() == 0) {
                    quarantine.closeIncident(p.tenantId(), p.incidentId(), at);
                    audit.append(new AuditEvent(UUID.randomUUID(), p.tenantId(), "SYSTEM", "vero", INCIDENT_CLOSED_ACTION,
                            INCIDENT_TARGET, p.incidentId().toString(), at, correlationId, AuditOutcome.SUCCESS, "{}"));
                }
            }
            return !finished;
        });
    }

    /**
     * Task 0052: carries out approved whole releases, at most {@code maxBatches} batches of {@link #BATCH_SIZE} rows in
     * all, oldest incident first. Each batch is its own transaction (rows released and audited together), so a stop
     * loses nothing and nothing is released twice; the incident ends, and its filter's blocking with it, only when
     * every document is released. Returns the number of batches that released or looked at something.
     */
    public int release(int maxBatches) {
        int done = 0;
        for (IncidentRef ref : quarantine.findReleasing(Math.max(1, maxBatches))) {
            while (done < maxBatches) {
                Boolean more = releaseBatch(ref, "quarantine-release-" + ref.incidentId());
                if (more == null) break;
                done++;
                if (!more) break;
            }
            if (done >= maxBatches) break;
        }
        return done;
    }

    /** One release batch: null when there was nothing to do (or another worker has it), else whether more is left. */
    private Boolean releaseBatch(IncidentRef ref, String correlationId) {
        return transactions.execute(status -> {
            ReleaseRequest request = quarantine.lockReleasing(ref.tenantId(), ref.incidentId()).orElse(null);
            if (request == null || request.decidedBy() == null) return null;
            Instant now = clock.instant();
            // The release is the approver's: they are named on each row and in each audit event, as before.
            Actor approver = new Actor(ref.tenantId(), request.decidedBy(), request.decidedByName(), null, correlationId);
            List<Released> released = quarantine.releaseBatch(ref.tenantId(), ref.incidentId(), approver.actorId(),
                    displayName(approver), request.reason(), request.requestId(), now, BATCH_SIZE);
            List<AuditEvent> events = new ArrayList<>(released.size());
            for (Released r : released) {
                Instant at = now.isBefore(r.quarantinedAt()) ? r.quarantinedAt() : now;
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("incidentId", ref.incidentId().toString());
                details.put("quarantineId", r.quarantineId().toString());
                details.put("requestId", request.requestId().toString());
                events.add(event(approver, RELEASED_ACTION, "DOCUMENT", r.documentId().toString(), at, details));
            }
            audit.appendAll(events);
            if (released.size() < BATCH_SIZE) {
                closeIfEmpty(approver, ref.incidentId(), now);
                return counts(ref.tenantId(), ref.incidentId()).inForce() > 0;
            }
            return true;
        });
    }

    /**
     * Task 0052: fills in the level of rows written before changeset 039 (at most {@code maxBatches} batches), so the
     * incidents' counts by level are complete. Until then such a row counts as hidden to everyone below the highest
     * clearance. Returns the number of batches done.
     */
    public int fillLevels(int maxBatches) {
        int done = 0;
        while (done < maxBatches) {
            List<LevelGap> gaps = quarantine.findLevelGaps(BATCH_SIZE);
            if (gaps.isEmpty()) break;
            Map<UUID, List<LevelGap>> byTenant = new LinkedHashMap<>();
            for (LevelGap g : gaps) byTenant.computeIfAbsent(g.tenantId(), k -> new ArrayList<>()).add(g);
            for (Map.Entry<UUID, List<LevelGap>> e : byTenant.entrySet()) {
                Map<UUID, SensitivityLevel> levels = levels(e.getKey(), e.getValue().stream().map(LevelGap::document).toList());
                Map<UUID, SensitivityLevel> byRow = new LinkedHashMap<>();
                for (LevelGap g : e.getValue()) byRow.put(g.quarantineId(), levels.get(g.document().documentId()));
                transactions.executeWithoutResult(status -> quarantine.fillLevels(e.getKey(), byRow));
            }
            done++;
            if (gaps.size() < BATCH_SIZE) break;
        }
        return done;
    }

    // ------------------------------------------------------------------ review

    /** Incidents, open first then newest, with their counts as kept on them (task 0052: never the whole incident). */
    public List<IncidentSummary> incidents(Actor actor) {
        List<Incident> incidents = quarantine.findIncidents(actor.tenantId(), LIST_LIMIT);
        Map<UUID, Counts> counts = quarantine.counts(actor.tenantId(), incidents.stream().map(Incident::incidentId).toList());
        return incidents.stream().map(i -> {
            Progress progress = progress(actor.tenantId(), i.incidentId());
            Counts c = counts.getOrDefault(i.incidentId(), Counts.NONE);
            return new IncidentSummary(i.incidentId(), i.name(), i.scope(), i.status(),
                    i.createdByName(), i.createdAt(), i.closedAt(), narrow(c.inForce()),
                    quarantine.findPendingRequest(actor.tenantId(), i.incidentId()).isPresent(), progress.preparing(),
                    progress.prepared(), progress.expected(), c.releasing() && i.open(), c.documents(), c.released());
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

    private Counts counts(UUID tenantId, UUID incidentId) {
        return quarantine.counts(tenantId, List.of(incidentId)).getOrDefault(incidentId, Counts.NONE);
    }

    /** The first page of an incident for review. */
    public IncidentView incident(Actor actor, String incidentReference) {
        return incident(actor, incidentReference, null);
    }

    /**
     * Task 0052: one incident for review, a page of {@link #PAGE} of its documents at a time (in force first, then
     * released; each oldest first), only those of levels the person can open; {@code after} is the page's start, as
     * the previous page's {@code next} gave it. The counts come from the incident, never from reading it whole.
     */
    public IncidentView incident(Actor actor, String incidentReference, String after) {
        UUID incidentId = parse(incidentReference, SdvErrorCode.QUA_007, NO_INCIDENT);
        PageKey from = pageKey(after);
        Incident incident = quarantine.findIncident(actor.tenantId(), incidentId)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_007, NO_INCIDENT));
        Counts c = counts(actor.tenantId(), incidentId);
        Page page = page(actor, incidentId, from, PAGE);
        List<QuarantinedDocumentView> visible = new ArrayList<>();
        for (Visible v : page.rows()) visible.add(QuarantinedDocumentView.of(v.row(), v.level()));
        ReleaseRequest pending = quarantine.findPendingRequest(actor.tenantId(), incidentId).orElse(null);
        Progress progress = progress(actor.tenantId(), incidentId);
        return new IncidentView(incident.incidentId(), incident.name(), incident.reason(), incident.scope(),
                incident.status(), incident.createdByName(), incident.createdAt(), incident.closedAt(), narrow(c.inForce()),
                narrow(c.documents()), narrow(hidden(actor, c)), visible,
                pending == null ? null : pending.requestId(),
                pending == null ? null : pending.requestedByName(), pending == null ? null : pending.expiresAt(),
                progress.preparing(), progress.prepared(), progress.expected(), PAGE,
                c.releasing() && incident.open(), c.released(), page.next());
    }

    /** Documents of levels the person can't open: counted from the incident, never listed. */
    private static long hidden(Actor actor, Counts c) {
        Set<SensitivityLevel> levels = reviewable(actor);
        if (levels == null) return 0;
        long shown = 0;
        for (SensitivityLevel l : levels) shown += c.atLevel(l);
        return Math.max(0, c.documents() - shown);
    }

    /** The levels the person reviews; null when they review every level (nothing to leave out). */
    private static Set<SensitivityLevel> reviewable(Actor actor) {
        Set<SensitivityLevel> levels = EnumSet.noneOf(SensitivityLevel.class);
        for (SensitivityLevel l : SensitivityLevel.values()) if (actor.clearance().canReviewQuarantined(l)) levels.add(l);
        return levels.size() == SensitivityLevel.values().length ? null : levels;
    }

    private record Visible(Quarantined row, SensitivityLevel level) { }

    private record Page(List<Visible> rows, String next) { }

    /**
     * Up to {@code size} rows from {@code from}, in force first then released, of the levels the person reviews (as
     * kept on the rows, then checked again against each document's level now: a level raised since is left out).
     */
    private Page page(Actor actor, UUID incidentId, PageKey from, int size) {
        Set<SensitivityLevel> levels = reviewable(actor);
        List<Quarantined> rows = new ArrayList<>();
        PageKey key = from;
        while (true) {
            int want = size + 1 - rows.size();
            List<Quarantined> got = quarantine.findPage(actor.tenantId(), incidentId, key, levels, want);
            rows.addAll(got);
            if (got.size() < want && key.inForce()) {
                key = new PageKey(false, null, null, null);
                continue;
            }
            break;
        }
        String next = null;
        if (rows.size() > size) {
            rows = rows.subList(0, size);
            Quarantined last = rows.getLast();
            next = cursor(new PageKey(last.inForce(), last.quarantinedAt(), last.document().receivedAt(),
                    last.document().documentId()));
        }
        Map<UUID, SensitivityLevel> now = levels(actor.tenantId(), rows.stream().map(Quarantined::document).toList());
        List<Visible> visible = new ArrayList<>();
        for (Quarantined q : rows) {
            SensitivityLevel level = now.get(q.document().documentId());
            if (actor.clearance().canReviewQuarantined(level)) visible.add(new Visible(q, level));
        }
        return new Page(visible, next);
    }

    /** The page position, as text the screen hands back (opaque to it). */
    static String cursor(PageKey key) {
        String raw = (key.inForce() ? "F" : "R") + "|" + key.quarantinedAt() + "|" + key.receivedAt() + "|" + key.documentId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static PageKey pageKey(String cursor) {
        if (blank(cursor)) return PageKey.FIRST;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 4 || !("F".equals(parts[0]) || "R".equals(parts[0]))) throw new IllegalArgumentException();
            return new PageKey("F".equals(parts[0]), Instant.parse(parts[1]), Instant.parse(parts[2]), UUID.fromString(parts[3]));
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.QUA_004, "This page of the incident isn't known. Start again from the first page.");
        }
    }

    private static int narrow(long n) {
        return (int) Math.min(n, Integer.MAX_VALUE);
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
                        p.createdByName() == null ? p.createdBy() : p.createdByName(), p.createdAt(), level,
                        document.receivedAt());
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
            Counts c = counts(actor.tenantId(), incidentId);
            if (c.releasing()) throw new CodedRefusal.Conflict(SdvErrorCode.QUA_010, RELEASING);
            // Task 0052: from the incident's counts. Every document in force must be of a level the person reviews
            // (one whose level isn't known yet counts as the highest).
            Set<SensitivityLevel> levels = reviewable(actor);
            if (levels != null) {
                long reviewableInForce = 0;
                for (SensitivityLevel l : levels) reviewableInForce += c.inForceAtLevel(l);
                if (reviewableInForce < c.inForce()) {
                    throw new CodedRefusal.Forbidden(SdvErrorCode.QUA_002,
                            "This incident holds documents of a level you can't open, so you can't ask for its release.");
                }
            }
            ReleaseRequest request = new ReleaseRequest(UUID.randomUUID(), actor.tenantId(), incidentId, why,
                    actor.actorId(), displayName(actor), now, "PENDING", null, null, null,
                    now.plus(settings.pendingWindow(actor.tenantId())));
            quarantine.insertRequest(request);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("requestId", request.requestId().toString());
            details.put("documentCount", c.inForce());
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

    /**
     * One request decided by a Business Admin who didn't make it; never in bulk. Task 0052: approving starts the whole
     * release, which runs in batches in the background ({@link #release}); the first batch is released at once, so a
     * small incident is released when this returns. The incident, and its filter's blocking, ends only when every
     * document is released.
     */
    public ReleaseRequestView decide(Actor actor, String requestReference, boolean approve) {
        UUID requestId = parse(requestReference, SdvErrorCode.QUA_010, NO_REQUEST);
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> expire(actor.tenantId(), now, actor.correlationId()));
        ReleaseRequest decided = transactions.execute(status -> {
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
            Counts c = counts(actor.tenantId(), incident.incidentId());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("requestId", requestId.toString());
            details.put("requestedBy", request.requestedBy());
            if (approve) details.put("documentCount", incident.open() ? c.inForce() : 0);
            append(actor, approve ? RELEASE_APPROVED_ACTION : RELEASE_DECLINED_ACTION, INCIDENT_TARGET,
                    incident.incidentId().toString(), now, details);
            if (approve && incident.open()) {
                // The request's reason is the release reason, kept on each row, never in the audit; each document's
                // release is audited as it is released.
                if (c.inForce() > 0) quarantine.startRelease(actor.tenantId(), incident.incidentId(), requestId);
                else closeIfEmpty(actor, incident.incidentId(), now);
            }
            return new ReleaseRequest(request.requestId(), request.tenantId(), request.incidentId(),
                    request.reason(), request.requestedBy(), request.requestedByName(), request.requestedAt(), decision,
                    actor.actorId(), displayName(actor), now, request.expiresAt());
        });
        if (approve) {
            // The first batch now; a failure here is retried by the background job (nothing is lost, nothing twice).
            try {
                releaseBatch(new IncidentRef(actor.tenantId(), decided.incidentId()), actor.correlationId());
            } catch (RuntimeException ex) {
                // The documents stay quarantined until the job releases them.
            }
        }
        return requestView(actor.tenantId(), decided);
    }

    // ------------------------------------------------------------------ exposure report

    /** The first page of the exposure report. */
    public ExposureReport exposure(Actor actor, String incidentReference) {
        return exposure(actor, incidentReference, null);
    }

    /**
     * Per incident: documents by level, and per document who opened, printed or downloaded it before its quarantine.
     * Task 0052: a page of {@link #PAGE} documents at a time, in the review list's order; the counts come from the
     * incident (levels the person can't open are counted, never listed). The export has every document.
     */
    public ExposureReport exposure(Actor actor, String incidentReference, String after) {
        UUID incidentId = parse(incidentReference, SdvErrorCode.QUA_007, NO_INCIDENT);
        PageKey from = pageKey(after);
        Incident incident = quarantine.findIncident(actor.tenantId(), incidentId)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_007, NO_INCIDENT));
        Counts c = counts(actor.tenantId(), incidentId);
        Page page = page(actor, incidentId, from, PAGE);
        List<ExposureDocument> documents = exposureDocuments(actor, incidentId, page.rows());
        Set<SensitivityLevel> levels = reviewable(actor);
        long standard = levels == null || levels.contains(SensitivityLevel.STANDARD) ? c.standard() : 0;
        long confidential = levels == null || levels.contains(SensitivityLevel.CONFIDENTIAL) ? c.confidential() : 0;
        long highly = levels == null || levels.contains(SensitivityLevel.HIGHLY_CONFIDENTIAL) ? c.highlyConfidential() : 0;
        long hidden = hidden(actor, c);
        return new ExposureReport(incident.incidentId(), incident.name(), incident.status(), incident.createdAt(),
                narrow(standard), narrow(confidential), narrow(highly), narrow(hidden), documents,
                narrow(c.documents() - hidden), page.next(), PAGE);
    }

    private List<ExposureDocument> exposureDocuments(Actor actor, UUID incidentId, List<Visible> rows) {
        Map<UUID, List<ExposureEntry>> byDocument = new LinkedHashMap<>();
        for (Exposure e : quarantine.findExposure(actor.tenantId(), incidentId,
                rows.stream().map(v -> v.row().document().documentId()).distinct().toList())) {
            byDocument.computeIfAbsent(e.documentId(), k -> new ArrayList<>())
                    .add(new ExposureEntry(e.action(), actionWords(e.action()), e.actorName() == null ? e.actorId() : e.actorName(), e.occurredAt()));
        }
        List<ExposureDocument> documents = new ArrayList<>(rows.size());
        for (Visible v : rows) {
            Quarantined q = v.row();
            DocumentFacts d = q.document();
            documents.add(new ExposureDocument(d.documentId(), d.primaryIdentifierLabel(), d.primaryIdentifier(), v.level(),
                    d.receivedAt(), d.pageCount(), q.quarantinedAt(), q.inForce(),
                    byDocument.getOrDefault(d.documentId(), List.of())));
        }
        return documents;
    }

    /** A whole export in memory (the tests; the web layer streams with {@link #openExposureExport}). */
    public record Export(byte[] csv, long rowCount, String filename, UUID reference, String fingerprint) { }

    public Export exportExposure(Actor actor, String incidentReference) {
        ExposureExport export = openExposureExport(actor, incidentReference);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            Written written = export.writeTo(out);
            return new Export(out.toByteArray(), written.rowCount(), export.filename(), export.reference(), written.fingerprint());
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    /** What a finished export wrote: rows, documents and the SHA-256 of the file (also in the audit). */
    public record Written(long rowCount, long documentCount, String fingerprint) { }

    /**
     * Task 0052: an exposure export ready to stream. Its reference and file name are known before the first byte; the
     * start is already in the audit. {@link #writeTo} writes the CSV a page at a time, works out the fingerprint while
     * writing, and records the finished export (rows, documents, fingerprint) in the audit.
     */
    public interface ExposureExport {
        UUID reference();

        String filename();

        Written writeTo(OutputStream target) throws IOException;
    }

    private final class StreamedExposure implements ExposureExport {
        private final Actor actor;
        private final UUID incidentId;
        private final String name;
        private final UUID reference;
        private final String filename;

        private StreamedExposure(Actor actor, UUID incidentId, String name, UUID reference, String filename) {
            this.actor = actor;
            this.incidentId = incidentId;
            this.name = name;
            this.reference = reference;
            this.filename = filename;
        }

        @Override public UUID reference() { return reference; }

        @Override public String filename() { return filename; }

        @Override public Written writeTo(OutputStream target) throws IOException {
            MessageDigest digest = sha256();
            DigestOutputStream hashed = new DigestOutputStream(target, digest);
            Writer out = new BufferedWriter(new OutputStreamWriter(hashed, StandardCharsets.UTF_8), 64 * 1024);
            out.write("﻿");
            out.write(CSV_HEADER);
            out.write("\r\n");
            long rows = 0;
            long documents = 0;
            PageKey key = PageKey.FIRST;
            while (true) {
                Page page = page(actor, incidentId, key, EXPORT_PAGE);
                for (ExposureDocument d : exposureDocuments(actor, incidentId, page.rows())) {
                    documents++;
                    List<ExposureEntry> entries = d.exposure().isEmpty()
                            ? List.of(new ExposureEntry(null, "Not opened, printed or downloaded", "", null)) : d.exposure();
                    for (ExposureEntry e : entries) {
                        out.write(AuditCsv.cell(name));
                        out.write(',');
                        out.write(AuditCsv.cell(d.documentId().toString()));
                        out.write(',');
                        out.write(AuditCsv.cell(nullToEmpty(d.primaryIdentifierLabel())));
                        out.write(',');
                        out.write(AuditCsv.cell(nullToEmpty(d.primaryIdentifier())));
                        out.write(',');
                        out.write(AuditCsv.cell(levelWords(d.level())));
                        out.write(',');
                        out.write(AuditCsv.cell(d.receivedAt() == null ? "" : d.receivedAt().toString()));
                        out.write(',');
                        out.write(d.pageCount() == null ? "" : d.pageCount().toString());
                        out.write(',');
                        out.write(AuditCsv.cell(e.what()));
                        out.write(',');
                        out.write(AuditCsv.cell(nullToEmpty(e.person())));
                        out.write(',');
                        out.write(AuditCsv.cell(e.at() == null ? "" : e.at().toString()));
                        out.write("\r\n");
                        rows++;
                    }
                }
                if (page.next() == null) break;
                key = pageKey(page.next());
            }
            out.flush();
            String fingerprint = HexFormat.of().formatHex(digest.digest());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reference", reference.toString());
            details.put("fingerprint", fingerprint);
            details.put("rowCount", rows);
            details.put("documentCount", documents);
            append(actor, EXPOSURE_EXPORTED_ACTION, INCIDENT_TARGET, incidentId.toString(), clock.instant(), details);
            return new Written(rows, documents, fingerprint);
        }
    }

    /**
     * Task 0052: starts an exposure export. The start is audited (with its reference) before any content leaves, so
     * an export that stops part-way is still on record; the finished one is audited again with its fingerprint.
     */
    public ExposureExport openExposureExport(Actor actor, String incidentReference) {
        UUID incidentId = parse(incidentReference, SdvErrorCode.QUA_007, NO_INCIDENT);
        Incident incident = quarantine.findIncident(actor.tenantId(), incidentId)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_007, NO_INCIDENT));
        UUID reference = UUID.randomUUID();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", reference.toString());
        transactions.executeWithoutResult(status ->
                append(actor, EXPOSURE_EXPORT_STARTED_ACTION, INCIDENT_TARGET, incidentId.toString(), clock.instant(), details));
        String filename = "vero-quarantine-exposure-" + LocalDate.now(clock.withZone(ZoneOffset.UTC)) + "-"
                + reference.toString().substring(0, 8) + ".csv";
        return new StreamedExposure(actor, incident.incidentId(), incident.name(), reference, filename);
    }

    // ------------------------------------------------------------------ views

    public record IncidentSummary(UUID incidentId, String name, String scope, String status, String createdByName,
                                  Instant createdAt, Instant closedAt, int documentsInForce, boolean releaseRequested,
                                  boolean preparing, long preparedCount, Long expectedCount, boolean releasing,
                                  long documentCount, long releasedCount) { }

    /**
     * hiddenCount: documents of levels the person can't open (counted, never shown). Task 0051: preparing while the
     * background job writes the rows (preparedCount of expectedCount looked at). Task 0052: documents is one page of at
     * most listLimit; next starts the following page (null: this is the last); releasing while an approved whole
     * release runs in the background (releasedCount of documentCount released so far).
     */
    public record IncidentView(UUID incidentId, String name, String reason, String scope, String status,
                               String createdByName, Instant createdAt, Instant closedAt, int documentsInForce,
                               int documentCount, int hiddenCount, List<QuarantinedDocumentView> documents,
                               UUID pendingRequestId, String pendingRequestedByName, Instant pendingExpiresAt,
                               boolean preparing, long preparedCount, Long expectedCount, int listLimit,
                               boolean releasing, long releasedCount, String next) { }

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
                                 List<ExposureDocument> documents, int documentCount, String next, int listLimit) { }

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

    private void place(Actor actor, UUID incidentId, DocumentFacts document, SensitivityLevel level, Instant now) {
        UUID quarantineId = UUID.randomUUID();
        quarantine.insertQuarantine(quarantineId, actor.tenantId(), incidentId, document.documentId(), actor.actorId(),
                displayName(actor), now, level, document.receivedAt());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("incidentId", incidentId.toString());
        details.put("quarantineId", quarantineId.toString());
        append(actor, QUARANTINED_ACTION, "DOCUMENT", document.documentId().toString(), now, details);
    }

    private void closeIfEmpty(Actor actor, UUID incidentId, Instant at) {
        // Task 0051: an incident still being prepared stays open (its filter quarantines the documents not yet written).
        if (progress(actor.tenantId(), incidentId).preparing()) return;
        if (counts(actor.tenantId(), incidentId).inForce() > 0) return;
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
        // Task 0052: from the incident's counts. A document whose level isn't known yet counts as Highly Confidential.
        Counts c = counts(tenantId, r.incidentId());
        boolean waiting = "PENDING".equals(r.status());
        long total = waiting ? c.inForce() : c.documents();
        long standard = waiting ? c.standardInForce() : c.standard();
        long confidential = waiting ? c.confidentialInForce() : c.confidential();
        long highly = Math.max(0, total - standard - confidential);
        return new ReleaseRequestView(r.requestId(), incident.incidentId(), incident.name(), narrow(total), narrow(standard),
                narrow(confidential), narrow(highly), incident.createdAt(), c.firstReceivedAt(), c.lastReceivedAt(), r.requestedByName(), r.requestedAt(), r.expiresAt(), r.status(),
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
        audit.append(event(actor, action, targetType, targetId, at, details));
    }

    private static AuditEvent event(Actor actor, String action, String targetType, String targetId, Instant at,
                                    Map<String, Object> details) {
        return new AuditEvent(UUID.randomUUID(), actor.tenantId(), "USER", actor.actorId(), action, targetType,
                targetId, at, actor.correlationId(), AuditOutcome.SUCCESS, json(details), actor.displayName());
    }

    private static String json(Map<String, Object> details) {
        try {
            return JSON.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw InternalCheckFailed.auditNotWritten("The quarantine change could not be audited, so nothing was changed.", ex);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
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
