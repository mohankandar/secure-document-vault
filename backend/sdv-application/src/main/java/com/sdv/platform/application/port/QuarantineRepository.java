package com.sdv.platform.application.port;

import com.sdv.platform.application.access.QuarantineLookup;
import com.sdv.platform.domain.document.SensitivityLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Task 0048: quarantine_incidents, quarantined_documents, quarantine_release_requests (changeset 036). One entity per call. */
public interface QuarantineRepository extends QuarantineLookup {

    record Incident(UUID incidentId, UUID tenantId, String name, String reason, String scope, String createdBy,
                    String createdByName, Instant createdAt, String status, Instant closedAt) {
        public boolean open() { return "OPEN".equals(status); }
    }

    /** A document as the quarantine views need it: never a file name, title or content. */
    record DocumentFacts(UUID documentId, UUID sourceId, UUID submissionId, String status, SensitivityLevel ownLevel,
                         Instant receivedAt, Integer pageCount, String primaryIdentifier, String primaryIdentifierLabel) { }

    record Quarantined(UUID quarantineId, UUID incidentId, DocumentFacts document, String quarantinedBy,
                       String quarantinedByName, Instant quarantinedAt, String releasedBy, String releasedByName,
                       Instant releasedAt) {
        public boolean inForce() { return releasedAt == null; }
    }

    record ReleaseRequest(UUID requestId, UUID tenantId, UUID incidentId, String reason, String requestedBy,
                          String requestedByName, Instant requestedAt, String status, String decidedBy,
                          String decidedByName, Instant decidedAt, Instant expiresAt) { }

    /** The incident filter: received [from, to), optional sending system, and field values (exact, normalized). */
    record Filter(Instant receivedFrom, Instant receivedTo, UUID sourceId, Map<String, String> fields) {
        public Filter {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }

    /** One access to a document before its quarantine: who, what (opened, printed, downloaded), when. */
    record Exposure(UUID documentId, String actorId, String actorName, String action, Instant occurredAt) { }

    Optional<DocumentFacts> lockDocument(UUID tenantId, UUID documentId);

    void insertIncident(Incident incident);

    Optional<Incident> findIncident(UUID tenantId, UUID incidentId);

    Optional<Incident> lockIncident(UUID tenantId, UUID incidentId);

    List<Incident> findIncidents(UUID tenantId, int limit);

    void closeIncident(UUID tenantId, UUID incidentId, Instant closedAt);

    void insertQuarantine(UUID quarantineId, UUID tenantId, UUID incidentId, UUID documentId, String by, String byName,
                          Instant at);

    /** The document's quarantine in force, if any. */
    Optional<Quarantined> findActive(UUID tenantId, UUID documentId);

    /** Every document of the incident, in force first, oldest first. */
    List<Quarantined> findInIncident(UUID tenantId, UUID incidentId);

    Map<UUID, Integer> countInForce(UUID tenantId, List<UUID> incidentIds);

    boolean release(UUID tenantId, UUID quarantineId, String by, String byName, String reason, UUID requestId, Instant at);

    /** Documents matching the filter that can be quarantined now (not deleted, not quarantined), oldest first. */
    List<DocumentFacts> findMatching(UUID tenantId, Filter filter, int limit);

    long countMatching(UUID tenantId, Filter filter);

    void insertRequest(ReleaseRequest request);

    Optional<ReleaseRequest> lockRequest(UUID tenantId, UUID requestId);

    Optional<ReleaseRequest> findPendingRequest(UUID tenantId, UUID incidentId);

    List<ReleaseRequest> findPendingRequests(UUID tenantId, int limit);

    boolean decideRequest(UUID tenantId, UUID requestId, String status, String by, String byName, Instant at);

    /** Pending requests whose time ran out become EXPIRED; returns them. */
    List<ReleaseRequest> expireRequests(UUID tenantId, Instant now);

    /** The incident's waiting request (if any) becomes EXPIRED: its incident has nothing left to release. */
    List<ReleaseRequest> expirePending(UUID tenantId, UUID incidentId);

    /** Opened, printed or downloaded before {@code before}, per document, oldest first. */
    List<Exposure> findExposure(UUID tenantId, Map<UUID, Instant> before);
}
