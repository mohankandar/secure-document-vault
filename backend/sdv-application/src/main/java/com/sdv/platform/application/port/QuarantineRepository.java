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

    /**
     * Task 0051: a filter incident's blocking and its background preparation. From its confirmation every document
     * matching {@code filter} and received up to {@code cutoffReceivedAt} is quarantined (while the incident is open),
     * unless a release after the confirmation freed it. The job writes the rows after {@code cursor*}, oldest first.
     */
    record Preparation(UUID incidentId, UUID tenantId, Filter filter, Instant cutoffReceivedAt, long expectedCount,
                       long preparedCount, Instant preparedAt, Instant cursorReceivedAt, UUID cursorDocumentId,
                       String createdBy, String createdByName, Instant createdAt, String status) {
        public boolean preparing() { return preparedAt == null; }
    }

    /** An incident, by entity: what the background job works through. */
    record IncidentRef(UUID tenantId, UUID incidentId) { }

    /** A row the job wrote: the document now has its quarantine row in the incident. */
    record Placed(UUID quarantineId, UUID documentId) { }

    /**
     * One batch of the job: how many documents it looked at, the rows it wrote, and the last document looked at (the
     * next batch starts after it). Fewer looked at than asked: the incident is prepared.
     */
    record Batch(int scanned, List<Placed> placed, Instant lastReceivedAt, UUID lastDocumentId) { }

    /** A row released with a whole incident. */
    record Released(UUID quarantineId, UUID documentId, Instant quarantinedAt) { }

    /** The filter count and the latest received time among the matches (the cut-off), in one look. */
    record MatchStats(long count, Instant latestReceivedAt) { }

    /**
     * Task 0052: an incident's counts, kept on the incident as its rows are written and released (changeset 039), so
     * no screen reads the whole incident. Levels are each document's level when it was quarantined; a row written
     * before 039 has no level until the background job fills it in (counted in {@code documents} only).
     * {@code releasingRequestId}: the approved whole release the background job is carrying out, if any.
     */
    record Counts(long documents, long released, long standard, long confidential, long highlyConfidential,
                  long standardInForce, long confidentialInForce, long highlyConfidentialInForce,
                  Instant firstReceivedAt, Instant lastReceivedAt, UUID releasingRequestId) {
        public static final Counts NONE = new Counts(0, 0, 0, 0, 0, 0, 0, 0, null, null, null);

        public long inForce() { return documents - released; }

        public long atLevel(SensitivityLevel level) {
            return switch (level) {
                case STANDARD -> standard;
                case CONFIDENTIAL -> confidential;
                case HIGHLY_CONFIDENTIAL -> highlyConfidential;
            };
        }

        public long inForceAtLevel(SensitivityLevel level) {
            return switch (level) {
                case STANDARD -> standardInForce;
                case CONFIDENTIAL -> confidentialInForce;
                case HIGHLY_CONFIDENTIAL -> highlyConfidentialInForce;
            };
        }

        public boolean releasing() { return releasingRequestId != null; }
    }

    /**
     * Task 0052: where a page of an incident starts. The incident is read in force first, then released; each part
     * oldest first: quarantined, then received, then document. {@code null} position: the start of that part.
     */
    record PageKey(boolean inForce, Instant quarantinedAt, Instant receivedAt, UUID documentId) {
        public static final PageKey FIRST = new PageKey(true, null, null, null);
        public boolean atStart() { return quarantinedAt == null; }
    }

    /** Task 0052: a row written before changeset 039, still without its level. */
    record LevelGap(UUID tenantId, UUID quarantineId, DocumentFacts document) { }

    Optional<DocumentFacts> lockDocument(UUID tenantId, UUID documentId);

    void insertIncident(Incident incident);

    Optional<Incident> findIncident(UUID tenantId, UUID incidentId);

    Optional<Incident> lockIncident(UUID tenantId, UUID incidentId);

    List<Incident> findIncidents(UUID tenantId, int limit);

    void closeIncident(UUID tenantId, UUID incidentId, Instant closedAt);

    /** Task 0052: with the document's level now and its received time (the incident's counts follow). */
    void insertQuarantine(UUID quarantineId, UUID tenantId, UUID incidentId, UUID documentId, String by, String byName,
                          Instant at, SensitivityLevel level, Instant receivedAt);

    /** The document's quarantine in force, if any. */
    Optional<Quarantined> findActive(UUID tenantId, UUID documentId);

    /** Task 0052: the incidents' counts as kept on them; an incident not found is left out. */
    Map<UUID, Counts> counts(UUID tenantId, List<UUID> incidentIds);

    boolean release(UUID tenantId, UUID quarantineId, String by, String byName, String reason, UUID requestId, Instant at);

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

    // ------------------------------------------------------------------ task 0051: no limit

    /** The filter's count and cut-off now (documents that can be quarantined now). */
    MatchStats matchStats(UUID tenantId, Filter filter);

    /** Stores the filter and cut-off on a new filter incident: from this moment its documents are quarantined. */
    void startFilterBlocking(UUID tenantId, UUID incidentId, Filter filter, Instant cutoffReceivedAt, long expectedCount);

    Optional<Preparation> findPreparation(UUID tenantId, UUID incidentId);

    /** Locks the incident for one batch; empty when another worker holds it or it isn't a filter incident. */
    Optional<Preparation> lockPreparation(UUID tenantId, UUID incidentId);

    /** Filter incidents still being prepared, oldest first, across entities (the background job). */
    List<IncidentRef> findPreparing(int limit);

    /**
     * Writes the rows of the next {@code size} documents after the cursor: each one still blocked by this incident's
     * filter and with no quarantine in force gets its row; deleted ones and ones quarantined elsewhere are skipped.
     * Running it again for the same documents writes nothing. Task 0052: {@code levels} gives the documents' levels
     * now (kept on each row for the incident's counts).
     */
    Batch prepareBatch(Preparation preparation, int size, String by, String byName, Instant at,
                       java.util.function.Function<List<DocumentFacts>, Map<UUID, SensitivityLevel>> levels);

    /** Moves the cursor after a batch; {@code preparedAt} not null: the incident is prepared. */
    void advancePreparation(UUID tenantId, UUID incidentId, long preparedCount, Instant cursorReceivedAt,
                            UUID cursorDocumentId, Instant preparedAt);

    /** The open filter incident whose filter quarantines the document now (it may have no row yet). */
    Optional<UUID> blockingIncident(UUID tenantId, UUID documentId);

    // ------------------------------------------------------------------ task 0052: a million documents

    /**
     * Up to {@code limit} rows of one part of the incident (in force, or released, as {@code from} says), after
     * {@code from}'s position, oldest first. {@code levels}: only rows kept at these levels; null: every row.
     */
    List<Quarantined> findPage(UUID tenantId, UUID incidentId, PageKey from, java.util.Set<SensitivityLevel> levels,
                               int limit);

    /** Opened, printed or downloaded before each document's first quarantine in this incident, oldest first. */
    List<Exposure> findExposure(UUID tenantId, UUID incidentId, java.util.Collection<UUID> documentIds);

    /** Marks the incident's approved whole release as started; the background job carries it out. */
    void startRelease(UUID tenantId, UUID incidentId, UUID requestId);

    /** Open incidents with a whole release being carried out, oldest first, across entities (the background job). */
    List<IncidentRef> findReleasing(int limit);

    /** Locks the incident for one release batch; empty when another worker holds it, or nothing is being released. */
    Optional<ReleaseRequest> lockReleasing(UUID tenantId, UUID incidentId);

    /** Releases the next {@code size} rows in force of the incident, oldest first; returns them. */
    List<Released> releaseBatch(UUID tenantId, UUID incidentId, String by, String byName, String reason, UUID requestId,
                                Instant at, int size);

    /** Rows written before changeset 039 still without a level, across entities (the background job). */
    List<LevelGap> findLevelGaps(int limit);

    /** Fills in the levels of those rows (only rows still without one; the incident's counts follow). */
    void fillLevels(UUID tenantId, Map<UUID, SensitivityLevel> levelsByQuarantineId);
}
