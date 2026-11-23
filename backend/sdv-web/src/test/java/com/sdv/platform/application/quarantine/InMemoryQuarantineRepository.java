package com.sdv.platform.application.quarantine;

import com.sdv.platform.application.port.QuarantineRepository;

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

    static final class Row {
        UUID quarantineId, tenantId, incidentId, documentId, requestId;
        String by, byName, releasedBy, releasedByName, reason;
        Instant at, releasedAt;
    }

    void add(UUID tenantId, DocumentFacts facts, Map<String, String> fields) {
        documents.put(facts.documentId(), new Doc(tenantId, facts, fields));
    }

    private Optional<DocumentFacts> doc(UUID tenantId, UUID documentId) {
        Doc d = documents.get(documentId);
        return d == null || !d.tenantId().equals(tenantId) ? Optional.empty() : Optional.of(d.facts());
    }

    @Override public boolean isQuarantined(UUID tenantId, UUID documentId) {
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
                                           String byName, Instant at) {
        if (isQuarantined(tenantId, documentId)) throw new IllegalStateException("uq_quarantined_documents_active");
        Row r = new Row();
        r.quarantineId = quarantineId; r.tenantId = tenantId; r.incidentId = incidentId; r.documentId = documentId;
        r.by = by; r.byName = byName; r.at = at;
        quarantines.put(quarantineId, r);
    }

    private Quarantined view(Row r) {
        return new Quarantined(r.quarantineId, r.incidentId, documents.get(r.documentId).facts(), r.by, r.byName, r.at,
                r.releasedBy, r.releasedByName, r.releasedAt);
    }

    @Override public Optional<Quarantined> findActive(UUID tenantId, UUID documentId) {
        return quarantines.values().stream().filter(r -> r.tenantId.equals(tenantId) && r.documentId.equals(documentId) && r.releasedAt == null)
                .findFirst().map(this::view);
    }

    @Override public List<Quarantined> findInIncident(UUID tenantId, UUID incidentId) {
        return quarantines.values().stream().filter(r -> r.tenantId.equals(tenantId) && r.incidentId.equals(incidentId))
                .sorted(Comparator.comparing((Row r) -> r.releasedAt != null).thenComparing(r -> r.at)).map(this::view).toList();
    }

    @Override public Map<UUID, Integer> countInForce(UUID tenantId, List<UUID> incidentIds) {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        for (Row r : quarantines.values()) {
            if (r.tenantId.equals(tenantId) && incidentIds.contains(r.incidentId) && r.releasedAt == null) out.merge(r.incidentId, 1, Integer::sum);
        }
        return out;
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

    @Override public List<DocumentFacts> findMatching(UUID tenantId, Filter filter, int limit) {
        return documents.values().stream().filter(d -> matches(tenantId, d, filter)).map(Doc::facts)
                .sorted(Comparator.comparing(DocumentFacts::receivedAt)).limit(limit).toList();
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

    @Override public List<Exposure> findExposure(UUID tenantId, Map<UUID, Instant> before) {
        return exposure.stream().filter(e -> before.containsKey(e.documentId()) && e.occurredAt().isBefore(before.get(e.documentId())))
                .toList();
    }
}
