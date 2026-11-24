package com.sdv.platform.application.quarantine;

import com.sdv.platform.application.port.QuarantineRepository;
import com.sdv.platform.domain.document.SensitivityLevel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Task 0048: the quarantine store in memory, for the service tests (one entity per call, as the JDBC one). */
class InMemoryQuarantineRepository implements QuarantineRepository {
    record Doc(UUID tenantId, DocumentFacts facts, Map<String, String> fields) { }

    final Map<UUID, Doc> documents = new LinkedHashMap<>();
    final Map<UUID, Incident> incidents = new LinkedHashMap<>();
    final Map<UUID, Row> quarantines = new LinkedHashMap<>();
    final Map<UUID, ReleaseRequest> requests = new LinkedHashMap<>();
    final List<Exposure> exposure = new ArrayList<>();
    /** Task 0051: filter incidents' filter, cut-off and progress. */
    final Map<UUID, Preparation> preparations = new LinkedHashMap<>();

    static final class Row {
        UUID quarantineId, tenantId, incidentId, documentId, requestId;
        String by, byName, releasedBy, releasedByName, reason;
        Instant at, releasedAt, receivedAt;
        /** Task 0052: the level kept on the row (null: written before changeset 039). */
        SensitivityLevel level;
    }

    /** Task 0052: the approved whole release being carried out, by incident. */
    final Map<UUID, UUID> releasing = new LinkedHashMap<>();

    void add(UUID tenantId, DocumentFacts facts, Map<String, String> fields) {
        documents.put(facts.documentId(), new Doc(tenantId, facts, fields));
    }

    private Optional<DocumentFacts> doc(UUID tenantId, UUID documentId) {
        Doc d = documents.get(documentId);
        return d == null || !d.tenantId().equals(tenantId) ? Optional.empty() : Optional.of(d.facts());
    }

    @Override public boolean isQuarantined(UUID tenantId, UUID documentId) {
        return hasRowInForce(tenantId, documentId) || blockingIncident(tenantId, documentId).isPresent();
    }

    private boolean hasRowInForce(UUID tenantId, UUID documentId) {
        return quarantines.values().stream().anyMatch(r -> r.tenantId.equals(tenantId) && r.documentId.equals(documentId) && r.releasedAt == null);
    }

    @Override public Set<UUID> quarantined(UUID tenantId, Collection<UUID> documentIds) {
        Set<UUID> out = new HashSet<>();
        for (UUID id : documentIds) if (isQuarantined(tenantId, id)) out.add(id);
        return out;
    }

    @Override public Optional<DocumentFacts> lockDocument(UUID tenantId, UUID documentId) { return doc(tenantId, documentId); }

    @Override public void insertIncident(Incident incident) { incidents.put(incident.incidentId(), incident); }

    @Override public Optional<Incident> findIncident(UUID tenantId, UUID incidentId) {
        Incident i = incidents.get(incidentId);
        return i == null || !i.tenantId().equals(tenantId) ? Optional.empty() : Optional.of(i);
    }

    @Override public Optional<Incident> lockIncident(UUID tenantId, UUID incidentId) { return findIncident(tenantId, incidentId); }

    @Override public List<Incident> findIncidents(UUID tenantId, int limit) {
        return incidents.values().stream().filter(i -> i.tenantId().equals(tenantId))
                .sorted(Comparator.comparing(Incident::status).thenComparing(Incident::createdAt, Comparator.reverseOrder()))
                .limit(limit).toList();
    }

    @Override public void closeIncident(UUID tenantId, UUID incidentId, Instant closedAt) {
        Incident i = incidents.get(incidentId);
        if (i == null || !i.tenantId().equals(tenantId) || !i.open()) return;
        incidents.put(incidentId, new Incident(i.incidentId(), i.tenantId(), i.name(), i.reason(), i.scope(), i.createdBy(),
                i.createdByName(), i.createdAt(), "RELEASED", closedAt));
    }

    @Override public void insertQuarantine(UUID quarantineId, UUID tenantId, UUID incidentId, UUID documentId, String by,
                                           String byName, Instant at, SensitivityLevel level, Instant receivedAt) {
        if (hasRowInForce(tenantId, documentId)) throw new IllegalStateException("uq_quarantined_documents_active");
        Row r = new Row();
        r.quarantineId = quarantineId; r.tenantId = tenantId; r.incidentId = incidentId; r.documentId = documentId;
        r.by = by; r.byName = byName; r.at = at;
        r.level = level == null ? SensitivityLevel.HIGHLY_CONFIDENTIAL : level;
        Instant received = documents.get(documentId).facts().receivedAt();
        r.receivedAt = received == null ? Instant.EPOCH : received;
        quarantines.put(quarantineId, r);
    }

    private Quarantined view(Row r) {
        return new Quarantined(r.quarantineId, r.incidentId, documents.get(r.documentId).facts(), r.by, r.byName, r.at,
                r.releasedBy, r.releasedByName, r.releasedAt);
    }

    @Override public long countSingleReleases(UUID tenantId, UUID incidentId, String by, Instant since) {
        return singles(incidentId).filter(r -> by.equals(r.releasedBy) && r.releasedAt.isAfter(since)).count();
    }

    @Override public List<Quarantined> findSingleReleases(UUID tenantId, UUID incidentId, int limit) {
        return singles(incidentId).sorted(java.util.Comparator.comparing((Row r) -> r.releasedAt).reversed()).limit(limit).map(this::view).toList();
    }

    private java.util.stream.Stream<Row> singles(UUID incidentId) {
        return quarantines.values().stream().filter(r -> r.incidentId.equals(incidentId) && r.releasedAt != null && r.requestId == null);
    }

    @Override public Optional<Quarantined> findActive(UUID tenantId, UUID documentId) {
        return quarantines.values().stream().filter(r -> r.tenantId.equals(tenantId) && r.documentId.equals(documentId) && r.releasedAt == null)
                .findFirst().map(this::view);
    }

    /** The review order within a part: quarantined, then received, then document. */
    private static final Comparator<Row> ORDER = Comparator.comparing((Row r) -> r.at).thenComparing(r -> r.receivedAt)
            .thenComparing(r -> r.documentId);

    @Override public List<Quarantined> findPage(UUID tenantId, UUID incidentId, PageKey from, Set<SensitivityLevel> levels,
                                                int limit) {
        Row start = null;
        if (!from.atStart()) {
            start = new Row();
            start.at = from.quarantinedAt(); start.receivedAt = from.receivedAt(); start.documentId = from.documentId();
        }
        Row after = start;
        return quarantines.values().stream().filter(r -> r.tenantId.equals(tenantId) && r.incidentId.equals(incidentId))
                .filter(r -> (r.releasedAt == null) == from.inForce())
                .filter(r -> levels == null || (r.level != null && levels.contains(r.level)))
                .filter(r -> after == null || ORDER.compare(r, after) > 0)
                .sorted(ORDER).limit(limit).map(this::view).toList();
    }

    @Override public Map<UUID, Counts> counts(UUID tenantId, List<UUID> incidentIds) {
        Map<UUID, Counts> out = new LinkedHashMap<>();
        for (UUID id : incidentIds) {
            Incident i = incidents.get(id);
            if (i == null || !i.tenantId().equals(tenantId)) continue;
            long total = 0, released = 0;
            long[] all = new long[3], inForce = new long[3];
            Instant first = null, last = null;
            for (Row r : quarantines.values()) {
                if (!r.tenantId.equals(tenantId) || !r.incidentId.equals(id)) continue;
                total++;
                if (r.releasedAt != null) released++;
                if (r.level != null) {
                    all[r.level.ordinal()]++;
                    if (r.releasedAt == null) inForce[r.level.ordinal()]++;
                }
                first = first == null || r.receivedAt.isBefore(first) ? r.receivedAt : first;
                last = last == null || r.receivedAt.isAfter(last) ? r.receivedAt : last;
            }
            out.put(id, new Counts(total, released, all[0], all[1], all[2], inForce[0], inForce[1], inForce[2], first, last,
                    i.open() ? releasing.get(id) : null));
        }
        return out;
    }

    @Override public void startRelease(UUID tenantId, UUID incidentId, UUID requestId) {
        Incident i = incidents.get(incidentId);
        if (i == null || !i.open() || releasing.containsKey(incidentId)) throw new IllegalStateException("startRelease");
        releasing.put(incidentId, requestId);
    }

    @Override public List<IncidentRef> findReleasing(int limit) {
        return releasing.keySet().stream().map(incidents::get).filter(Incident::open)
                .sorted(Comparator.comparing(Incident::createdAt)).limit(limit)
                .map(i -> new IncidentRef(i.tenantId(), i.incidentId())).toList();
    }

    @Override public Optional<ReleaseRequest> lockReleasing(UUID tenantId, UUID incidentId) {
        Incident i = incidents.get(incidentId);
        if (i == null || !i.tenantId().equals(tenantId) || !i.open() || !releasing.containsKey(incidentId)) return Optional.empty();
        return Optional.ofNullable(requests.get(releasing.get(incidentId)));
    }

    @Override public List<Released> releaseBatch(UUID tenantId, UUID incidentId, String by, String byName, String reason,
                                                 UUID requestId, Instant at, int size) {
        List<Row> next = quarantines.values().stream()
                .filter(r -> r.tenantId.equals(tenantId) && r.incidentId.equals(incidentId) && r.releasedAt == null)
                .sorted(ORDER).limit(size).toList();
        List<Released> out = new ArrayList<>();
        for (Row r : next) {
            release(tenantId, r.quarantineId, by, byName, reason, requestId, at.isBefore(r.at) ? r.at : at);
            out.add(new Released(r.quarantineId, r.documentId, r.at));
        }
        return out;
    }

    @Override public List<LevelGap> findLevelGaps(int limit) {
        return quarantines.values().stream().filter(r -> r.level == null)
                .sorted(Comparator.comparing((Row r) -> r.quarantineId)).limit(limit)
                .map(r -> new LevelGap(r.tenantId, r.quarantineId, documents.get(r.documentId).facts())).toList();
    }

    @Override public void fillLevels(UUID tenantId, Map<UUID, SensitivityLevel> levelsByQuarantineId) {
        levelsByQuarantineId.forEach((id, level) -> {
            Row r = quarantines.get(id);
            if (r != null && r.tenantId.equals(tenantId) && r.level == null) {
                r.level = level == null ? SensitivityLevel.HIGHLY_CONFIDENTIAL : level;
            }
        });
    }

    // ------------------------------------------------------------------ task 0051: the filter, as the SQL has it

    /** The filter's own conditions (dates, sending system, fields), as JdbcQuarantineRepository.matches. */
    private static boolean fits(Doc d, Filter f) {
        DocumentFacts x = d.facts();
        if (f.receivedFrom() != null && x.receivedAt().isBefore(f.receivedFrom())) return false;
        if (f.receivedTo() != null && !x.receivedAt().isBefore(f.receivedTo())) return false;
        if (f.sourceId() != null && !f.sourceId().equals(x.sourceId())) return false;
        for (Map.Entry<String, String> e : f.fields().entrySet()) {
            if (!Objects.equals(d.fields().get(e.getKey()), e.getValue())) return false;
        }
        return true;
    }

    private boolean releasedSince(UUID tenantId, UUID documentId, Instant since) {
        return quarantines.values().stream().anyMatch(r -> r.tenantId.equals(tenantId) && r.documentId.equals(documentId)
                && r.releasedAt != null && !r.releasedAt.isBefore(since));
    }

    @Override public Optional<UUID> blockingIncident(UUID tenantId, UUID documentId) {
        Doc d = documents.get(documentId);
        if (d == null || !d.tenantId().equals(tenantId)) return Optional.empty();
        return preparations.values().stream()
                .filter(p -> p.tenantId().equals(tenantId) && "OPEN".equals(incidents.get(p.incidentId()).status()))
                .filter(p -> fits(d, p.filter()) && !d.facts().receivedAt().isAfter(p.cutoffReceivedAt()))
                .filter(p -> !releasedSince(tenantId, documentId, p.createdAt()))
                .sorted(Comparator.comparing(Preparation::createdAt)).map(Preparation::incidentId).findFirst();
    }

    @Override public MatchStats matchStats(UUID tenantId, Filter filter) {
        List<DocumentFacts> m = documents.values().stream().filter(d -> matches(tenantId, d, filter)).map(Doc::facts).toList();
        return new MatchStats(m.size(), m.stream().map(DocumentFacts::receivedAt).max(Comparator.naturalOrder()).orElse(null));
    }

    @Override public void startFilterBlocking(UUID tenantId, UUID incidentId, Filter filter, Instant cutoffReceivedAt,
                                              long expectedCount) {
        Incident i = incidents.get(incidentId);
        preparations.put(incidentId, new Preparation(incidentId, tenantId, filter, cutoffReceivedAt, expectedCount, 0, null,
                null, null, i.createdBy(), i.createdByName(), i.createdAt(), i.status()));
    }

    @Override public Optional<Preparation> findPreparation(UUID tenantId, UUID incidentId) {
        Preparation p = preparations.get(incidentId);
        if (p == null || !p.tenantId().equals(tenantId)) return Optional.empty();
        Incident i = incidents.get(incidentId);
        return Optional.of(new Preparation(p.incidentId(), p.tenantId(), p.filter(), p.cutoffReceivedAt(), p.expectedCount(),
                p.preparedCount(), p.preparedAt(), p.cursorReceivedAt(), p.cursorDocumentId(), p.createdBy(),
                p.createdByName(), p.createdAt(), i.status()));
    }

    @Override public Optional<Preparation> lockPreparation(UUID tenantId, UUID incidentId) {
        return findPreparation(tenantId, incidentId);
    }

    @Override public List<IncidentRef> findPreparing(int limit) {
        return preparations.values().stream().filter(p -> p.preparedAt() == null && "OPEN".equals(incidents.get(p.incidentId()).status()))
                .sorted(Comparator.comparing(Preparation::createdAt)).limit(limit)
                .map(p -> new IncidentRef(p.tenantId(), p.incidentId())).toList();
    }

    /** Documents in (received, ID) order, as the SQL's cursor. */
    private static final Comparator<DocumentFacts> RECEIVED = Comparator.comparing(DocumentFacts::receivedAt)
            .thenComparing(DocumentFacts::documentId);

    @Override public Batch prepareBatch(Preparation p, int size, String by, String byName, Instant at,
                                        java.util.function.Function<List<DocumentFacts>, Map<UUID, SensitivityLevel>> levels) {
        DocumentFacts cursor = p.cursorDocumentId() == null ? null
                : new DocumentFacts(p.cursorDocumentId(), null, null, null, null, p.cursorReceivedAt(), null, null, null);
        List<Doc> scanned = documents.values().stream()
                .filter(d -> d.tenantId().equals(p.tenantId()) && fits(d, p.filter())
                        && !d.facts().receivedAt().isAfter(p.cutoffReceivedAt()))
                .filter(d -> cursor == null || RECEIVED.compare(d.facts(), cursor) > 0)
                .sorted(Comparator.comparing(Doc::facts, RECEIVED)).limit(size).toList();
        Map<UUID, SensitivityLevel> known = scanned.isEmpty() ? Map.of() : levels.apply(scanned.stream().map(Doc::facts).toList());
        List<Placed> placed = new ArrayList<>();
        for (Doc d : scanned) {
            UUID id = d.facts().documentId();
            if (Set.of("EXPIRED_DELETED", "RETENTION_DELETING").contains(d.facts().status())) continue;
            if (hasRowInForce(p.tenantId(), id) || releasedSince(p.tenantId(), id, p.createdAt())) continue;
            UUID quarantineId = UUID.randomUUID();
            insertQuarantine(quarantineId, p.tenantId(), p.incidentId(), id, by, byName, at, known.get(id), d.facts().receivedAt());
            placed.add(new Placed(quarantineId, id));
        }
        if (scanned.isEmpty()) return new Batch(0, List.of(), null, null);
        DocumentFacts last = scanned.getLast().facts();
        return new Batch(scanned.size(), placed, last.receivedAt(), last.documentId());
    }

    @Override public void advancePreparation(UUID tenantId, UUID incidentId, long preparedCount, Instant cursorReceivedAt,
                                             UUID cursorDocumentId, Instant preparedAt) {
        Preparation p = preparations.get(incidentId);
        preparations.put(incidentId, new Preparation(p.incidentId(), p.tenantId(), p.filter(), p.cutoffReceivedAt(),
                p.expectedCount(), preparedCount, preparedAt, cursorReceivedAt, cursorDocumentId, p.createdBy(),
                p.createdByName(), p.createdAt(), p.status()));
    }

    @Override public boolean release(UUID tenantId, UUID quarantineId, String by, String byName, String reason, UUID requestId,
                                     Instant at) {
        Row r = quarantines.get(quarantineId);
        if (r == null || !r.tenantId.equals(tenantId) || r.releasedAt != null) return false;
        if (reason == null) throw new IllegalStateException("ck_quarantined_documents_release");
        r.releasedBy = by; r.releasedByName = byName; r.releasedAt = at; r.reason = reason; r.requestId = requestId;
        return true;
    }

    private boolean matches(UUID tenantId, Doc d, Filter f) {
        if (!d.tenantId().equals(tenantId)) return false;
        DocumentFacts x = d.facts();
        if (Set.of("EXPIRED_DELETED", "RETENTION_DELETING").contains(x.status())) return false;
        if (isQuarantined(tenantId, x.documentId())) return false;
        if (f.receivedFrom() != null && x.receivedAt().isBefore(f.receivedFrom())) return false;
        if (f.receivedTo() != null && !x.receivedAt().isBefore(f.receivedTo())) return false;
        if (f.sourceId() != null && !f.sourceId().equals(x.sourceId())) return false;
        for (Map.Entry<String, String> e : f.fields().entrySet()) {
            if (!Objects.equals(d.fields().get(e.getKey()), e.getValue())) return false;
        }
        return true;
    }

    @Override public long countMatching(UUID tenantId, Filter filter) {
        return documents.values().stream().filter(d -> matches(tenantId, d, filter)).count();
    }

    @Override public void insertRequest(ReleaseRequest request) {
        if (findPendingRequest(request.tenantId(), request.incidentId()).isPresent()) throw new IllegalStateException("uq pending");
        requests.put(request.requestId(), request);
    }

    @Override public Optional<ReleaseRequest> lockRequest(UUID tenantId, UUID requestId) {
        ReleaseRequest r = requests.get(requestId);
        return r == null || !r.tenantId().equals(tenantId) ? Optional.empty() : Optional.of(r);
    }

    @Override public Optional<ReleaseRequest> findPendingRequest(UUID tenantId, UUID incidentId) {
        return requests.values().stream().filter(r -> r.tenantId().equals(tenantId) && r.incidentId().equals(incidentId)
                && "PENDING".equals(r.status())).findFirst();
    }

    @Override public List<ReleaseRequest> findPendingRequests(UUID tenantId, int limit) {
        return requests.values().stream().filter(r -> r.tenantId().equals(tenantId) && "PENDING".equals(r.status())).limit(limit).toList();
    }

    private ReleaseRequest with(ReleaseRequest r, String status, String by, String byName, Instant at) {
        return new ReleaseRequest(r.requestId(), r.tenantId(), r.incidentId(), r.reason(), r.requestedBy(), r.requestedByName(),
                r.requestedAt(), status, by, byName, at, r.expiresAt());
    }

    @Override public boolean decideRequest(UUID tenantId, UUID requestId, String status, String by, String byName, Instant at) {
        ReleaseRequest r = requests.get(requestId);
        if (r == null || !r.tenantId().equals(tenantId) || !"PENDING".equals(r.status())) return false;
        if (by.equals(r.requestedBy())) throw new IllegalStateException("ck_quarantine_release_requests_not_own");
        requests.put(requestId, with(r, status, by, byName, at));
        return true;
    }

    @Override public List<ReleaseRequest> expireRequests(UUID tenantId, Instant now) {
        List<ReleaseRequest> out = new ArrayList<>();
        for (ReleaseRequest r : List.copyOf(requests.values())) {
            if (r.tenantId().equals(tenantId) && "PENDING".equals(r.status()) && !r.expiresAt().isAfter(now)) {
                ReleaseRequest e = with(r, "EXPIRED", null, null, null);
                requests.put(r.requestId(), e);
                out.add(e);
            }
        }
        return out;
    }

    @Override public List<ReleaseRequest> expirePending(UUID tenantId, UUID incidentId) {
        List<ReleaseRequest> out = new ArrayList<>();
        findPendingRequest(tenantId, incidentId).ifPresent(r -> {
            ReleaseRequest e = with(r, "EXPIRED", null, null, null);
            requests.put(r.requestId(), e);
            out.add(e);
        });
        return out;
    }

    @Override public List<Exposure> findExposure(UUID tenantId, UUID incidentId, Collection<UUID> documentIds) {
        Map<UUID, Instant> before = new LinkedHashMap<>();
        for (Row r : quarantines.values()) {
            if (r.tenantId.equals(tenantId) && r.incidentId.equals(incidentId)) {
                before.merge(r.documentId, r.at, (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        return exposure.stream().filter(e -> documentIds.contains(e.documentId()))
                .filter(e -> before.containsKey(e.documentId()) && e.occurredAt().isBefore(before.get(e.documentId())))
                .toList();
    }
}
