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
import com.sdv.platform.application.port.QuarantineRepository.Quarantined;
import com.sdv.platform.application.port.QuarantineRepository.ReleaseRequest;
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
    /** One filter quarantine takes at most this many documents (a bigger incident is split by date range). */
    public static final int FILTER_MAX = 1000;
    public static final int FIELD_VALUE_MAX = 300;
    static final int LIST_LIMIT = 200;
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

    public record MatchCount(long count, int limit) { }

    /** The server's count of documents the filter would quarantine now. */
    public MatchCount countMatching(Actor actor, FilterRequest request) {
        Filter filter = filter(actor.tenantId(), request);
        return new MatchCount(quarantine.countMatching(actor.tenantId(), filter), FILTER_MAX);
    }

    /**
     * Quarantines every document matching the filter into a new incident, only when the count is still
     * {@code expectedCount} (what the person saw and selected); otherwise nothing is quarantined (SDV-QUA-009).
     */
    public IncidentView quarantineByFilter(Actor actor, FilterRequest request, Long expectedCount, String name,
                                           String reason) {
        Filter filter = filter(actor.tenantId(), request);
        String incidentName = name(name);
        String why = reason(reason);
        if (expectedCount == null || expectedCount < 1) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.QUA_004, "Count the matching documents first, then select them all.");
        }
        if (expectedCount > FILTER_MAX) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.QUA_004,
                    "At most " + FILTER_MAX + " documents can be quarantined at once. Narrow the dates.");
        }
        Instant now = clock.instant();
        UUID id = transactions.execute(status -> {
            List<DocumentFacts> matching = quarantine.findMatching(actor.tenantId(), filter, (int) (expectedCount + 1));
            if (matching.size() != expectedCount) {
                throw new CodedRefusal.Conflict(SdvErrorCode.QUA_009, COUNT_CHANGED);
            }
            List<DocumentFacts> locked = new ArrayList<>();
            for (DocumentFacts d : matching) {
                DocumentFacts document = quarantine.lockDocument(actor.tenantId(), d.documentId())
                        .orElseThrow(() -> new CodedRefusal.Conflict(SdvErrorCode.QUA_009, COUNT_CHANGED));
                if (NOT_QUARANTINABLE.contains(document.status()) || quarantine.isQuarantined(actor.tenantId(), d.documentId())) {
                    throw new CodedRefusal.Conflict(SdvErrorCode.QUA_009, COUNT_CHANGED);
                }
                locked.add(document);
            }
            Map<UUID, SensitivityLevel> levels = levels(actor.tenantId(), locked);
            for (DocumentFacts d : locked) {
                if (!actor.clearance().canReviewQuarantined(levels.get(d.documentId()))) {
                    throw new CodedRefusal.Forbidden(SdvErrorCode.QUA_002, LEVEL_REFUSED);
                }
            }
            UUID incidentId = createIncident(actor, incidentName, why, "FILTER", now, locked.size());
            for (DocumentFacts d : locked) place(actor, incidentId, d, now);
            return incidentId;
        });
        return incident(actor, id.toString());
    }

    // ------------------------------------------------------------------ review

    /** Incidents, open first then newest, with the documents in force the person may review (others only counted). */
    public List<IncidentSummary> incidents(Actor actor) {
        List<Incident> incidents = quarantine.findIncidents(actor.tenantId(), LIST_LIMIT);
        Map<UUID, Integer> inForce = quarantine.countInForce(actor.tenantId(),
                incidents.stream().map(Incident::incidentId).toList());
        return incidents.stream().map(i -> new IncidentSummary(i.incidentId(), i.name(), i.scope(), i.status(),
                i.createdByName(), i.createdAt(), i.closedAt(), inForce.getOrDefault(i.incidentId(), 0),
                quarantine.findPendingRequest(actor.tenantId(), i.incidentId()).isPresent())).toList();
    }

    /** One incident for review: its documents of levels the person can open; the rest only counted. */
    public IncidentView incident(Actor actor, String incidentReference) {
        UUID incidentId = parse(incidentReference, SdvErrorCode.QUA_007, NO_INCIDENT);
        Incident incident = quarantine.findIncident(actor.tenantId(), incidentId)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_007, NO_INCIDENT));
        List<Quarantined> rows = quarantine.findInIncident(actor.tenantId(), incidentId);
        Map<UUID, SensitivityLevel> levels = levels(actor.tenantId(), rows.stream().map(Quarantined::document).toList());
        List<QuarantinedDocumentView> visible = new ArrayList<>();
        int hidden = 0;
        int inForce = 0;
        for (Quarantined q : rows) {
            if (q.inForce()) inForce++;
            SensitivityLevel level = levels.get(q.document().documentId());
            if (actor.clearance().canReviewQuarantined(level)) visible.add(QuarantinedDocumentView.of(q, level));
            else hidden++;
        }
        ReleaseRequest pending = quarantine.findPendingRequest(actor.tenantId(), incidentId).orElse(null);
        return new IncidentView(incident.incidentId(), incident.name(), incident.reason(), incident.scope(),
                incident.status(), incident.createdByName(), incident.createdAt(), incident.closedAt(), inForce,
                rows.size(), hidden, visible, pending == null ? null : pending.requestId(),
                pending == null ? null : pending.requestedByName(), pending == null ? null : pending.expiresAt());
    }

    // ------------------------------------------------------------------ release one document

    public QuarantinedDocumentView releaseDocument(Actor actor, String documentReference, String reason) {
        UUID documentId = parse(documentReference, SdvErrorCode.QUA_005, NO_DOCUMENT);
        String why = reason(reason);
        Instant now = clock.instant();
        return transactions.execute(status -> {
            DocumentFacts document = quarantine.lockDocument(actor.tenantId(), documentId)
                    .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_005, NO_DOCUMENT));
            SensitivityLevel level = requireReviewable(actor, document);
            Quarantined q = quarantine.findActive(actor.tenantId(), documentId)
                    .orElseThrow(() -> new CodedRefusal.Conflict(SdvErrorCode.QUA_011, NOT_QUARANTINED));
            quarantine.lockIncident(actor.tenantId(), q.incidentId());
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
            List<Quarantined> inForce = quarantine.findInIncident(actor.tenantId(), incidentId).stream()
                    .filter(Quarantined::inForce).toList();
            Map<UUID, SensitivityLevel> levels = levels(actor.tenantId(), inForce.stream().map(Quarantined::document).toList());
            for (Quarantined q : inForce) {
                if (!actor.clearance().canReviewQuarantined(levels.get(q.document().documentId()))) {
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
            details.put("documentCount", inForce.size());
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
            int released = 0;
            if (approve && incident.open()) {
                for (Quarantined q : quarantine.findInIncident(actor.tenantId(), incident.incidentId())) {
                    if (!q.inForce()) continue;
                    Instant at = now.isBefore(q.quarantinedAt()) ? q.quarantinedAt() : now;
                    // The request's reason is the release reason (kept here, never in the audit).
                    if (!quarantine.release(actor.tenantId(), q.quarantineId(), actor.actorId(), displayName(actor),
                            request.reason(), requestId, at)) continue;
                    released++;
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("incidentId", incident.incidentId().toString());
                    details.put("quarantineId", q.quarantineId().toString());
                    details.put("requestId", requestId.toString());
                    append(actor, RELEASED_ACTION, "DOCUMENT", q.document().documentId().toString(), at, details);
                }
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("requestId", requestId.toString());
            details.put("requestedBy", request.requestedBy());
            if (approve) details.put("releasedCount", released);
            append(actor, approve ? RELEASE_APPROVED_ACTION : RELEASE_DECLINED_ACTION, INCIDENT_TARGET,
                    incident.incidentId().toString(), now, details);
            if (approve) closeIfEmpty(actor, incident.incidentId(), now);
            ReleaseRequest decided = new ReleaseRequest(request.requestId(), request.tenantId(), request.incidentId(),
                    request.reason(), request.requestedBy(), request.requestedByName(), request.requestedAt(), decision,
                    actor.actorId(), displayName(actor), now, request.expiresAt());
            return requestView(actor.tenantId(), decided);
        });
    }

    // ------------------------------------------------------------------ exposure report

    /** Per incident: documents by level, and per document who opened, printed or downloaded it before its quarantine. */
    public ExposureReport exposure(Actor actor, String incidentReference) {
        UUID incidentId = parse(incidentReference, SdvErrorCode.QUA_007, NO_INCIDENT);
        Incident incident = quarantine.findIncident(actor.tenantId(), incidentId)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.QUA_007, NO_INCIDENT));
        List<Quarantined> rows = quarantine.findInIncident(actor.tenantId(), incidentId);
        Map<UUID, SensitivityLevel> levels = levels(actor.tenantId(), rows.stream().map(Quarantined::document).toList());
        Map<UUID, Instant> before = new LinkedHashMap<>();
        List<Quarantined> visible = new ArrayList<>();
        int hidden = 0;
        for (Quarantined q : rows) {
            if (actor.clearance().canReviewQuarantined(levels.get(q.document().documentId()))) {
                visible.add(q);
                before.merge(q.document().documentId(), q.quarantinedAt(), (a, b) -> a.isBefore(b) ? a : b);
            } else {
                hidden++;
            }
        }
        Map<UUID, List<ExposureEntry>> byDocument = new LinkedHashMap<>();
        for (Exposure e : quarantine.findExposure(actor.tenantId(), before)) {
            byDocument.computeIfAbsent(e.documentId(), k -> new ArrayList<>())
                    .add(new ExposureEntry(e.action(), actionWords(e.action()), e.actorName() == null ? e.actorId() : e.actorName(), e.occurredAt()));
        }
        Map<SensitivityLevel, Integer> counts = new EnumMap<>(SensitivityLevel.class);
        for (SensitivityLevel l : SensitivityLevel.values()) counts.put(l, 0);
        List<ExposureDocument> documents = new ArrayList<>();
        for (Quarantined q : visible) {
            DocumentFacts d = q.document();
            SensitivityLevel level = levels.get(d.documentId());
            counts.merge(level, 1, Integer::sum);
            documents.add(new ExposureDocument(d.documentId(), d.primaryIdentifierLabel(), d.primaryIdentifier(), level,
                    d.receivedAt(), d.pageCount(), q.quarantinedAt(), q.inForce(),
                    byDocument.getOrDefault(d.documentId(), List.of())));
        }
        return new ExposureReport(incident.incidentId(), incident.name(), incident.status(), incident.createdAt(),
                counts.get(SensitivityLevel.STANDARD), counts.get(SensitivityLevel.CONFIDENTIAL),
                counts.get(SensitivityLevel.HIGHLY_CONFIDENTIAL), hidden, documents);
    }

    public record Export(byte[] csv, int rowCount, String filename, UUID reference, String fingerprint) { }

    /** The exposure report as CSV, with a reference and the SHA-256 of the file; the export is audited. */
    public Export exportExposure(Actor actor, String incidentReference) {
        ExposureReport report = exposure(actor, incidentReference);
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
                                  Instant createdAt, Instant closedAt, int documentsInForce, boolean releaseRequested) { }

    /** hiddenCount: documents of levels the person can't open (counted, never shown). */
    public record IncidentView(UUID incidentId, String name, String reason, String scope, String status,
                               String createdByName, Instant createdAt, Instant closedAt, int documentsInForce,
                               int documentCount, int hiddenCount, List<QuarantinedDocumentView> documents,
                               UUID pendingRequestId, String pendingRequestedByName, Instant pendingExpiresAt) { }

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
                                 List<ExposureDocument> documents) { }

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

    private UUID createIncident(Actor actor, String name, String reason, String scope, Instant now, int documents) {
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
        boolean any = quarantine.findInIncident(actor.tenantId(), incidentId).stream().anyMatch(Quarantined::inForce);
        if (any) return;
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
        List<Quarantined> rows = quarantine.findInIncident(tenantId, r.incidentId());
        // Waiting: the documents still quarantined (what approving releases); decided: every document of the incident.
        List<Quarantined> counted = "PENDING".equals(r.status())
                ? rows.stream().filter(Quarantined::inForce).toList() : rows;
        Map<UUID, SensitivityLevel> levels = levels(tenantId, counted.stream().map(Quarantined::document).toList());
        int s = 0, c = 0, h = 0;
        Instant first = null, last = null;
        for (Quarantined q : counted) {
            SensitivityLevel level = levels.get(q.document().documentId());
            if (level == SensitivityLevel.STANDARD) s++;
            else if (level == SensitivityLevel.CONFIDENTIAL) c++;
            else h++;
            Instant received = q.document().receivedAt();
            if (received != null) {
                first = first == null || received.isBefore(first) ? received : first;
                last = last == null || received.isAfter(last) ? received : last;
            }
        }
        return new ReleaseRequestView(r.requestId(), incident.incidentId(), incident.name(), counted.size(), s, c, h,
                incident.createdAt(), first, last, r.requestedByName(), r.requestedAt(), r.expiresAt(), r.status(),
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
