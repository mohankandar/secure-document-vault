package com.sdv.platform.application.approvals;

import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.business.RecordReferences;
import com.sdv.platform.application.business.TestRecordRefs;
import com.sdv.platform.application.document.DocumentActionService;
import com.sdv.platform.application.port.AccessRequestRepository;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessActivityQueryPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.AvailableDocumentSnapshot;
import com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot;
import com.sdv.platform.application.port.DocumentCommentRepository;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.DocumentTagRepository;
import com.sdv.platform.application.port.RecordSensitivityRepository;
import com.sdv.platform.application.sensitivity.SensitivityLevelService;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.SensitivityLevel;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0028 test world: one tenant, one sending system, two records (contracts) and their documents, with the real
 * services, guard and grant store, an in-memory request store that follows the same rules as the SQL
 * (JdbcAccessRequestRepository, proven against PostgreSQL in AccessRequestPostgresTest), and a clock the tests move.
 * <p>
 * Contract 300000000001 (submission SUB_A): H1 and H2 Highly Confidential, received the same day with 9 pages each
 * (twins, told apart by their position), C1 Confidential, S1 Standard. Contract 300000000002 (SUB_B): S2 Standard.
 * Every document has a secret file name and the record a last name ("Patel"); neither may reach an admin view.
 */
final class ApprovalsFixture {
    static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    static final UUID SUB_A = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000a");
    static final UUID SUB_B = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
    static final UUID H1 = UUID.fromString("40000000-0000-0000-0000-0000000000a1");
    static final UUID H2 = UUID.fromString("40000000-0000-0000-0000-0000000000a2");
    static final UUID C1 = UUID.fromString("40000000-0000-0000-0000-0000000000c1");
    static final UUID S1 = UUID.fromString("40000000-0000-0000-0000-0000000000d1");
    static final UUID S2 = UUID.fromString("40000000-0000-0000-0000-0000000000e2");
    static final String CONTRACT_A = "300000000001";
    static final String CONTRACT_B = "300000000002";
    static final Instant START = Instant.parse("2026-11-20T10:00:00Z");
    static final Clearance USER = Clearance.STANDARD_ONLY;
    static final Clearance STEWARD = new Clearance(true, false);
    static final Clearance RESTRICTED = Clearance.ALL;
    static final String RS = "rs-1";
    static final String RS_2 = "rs-2";
    static final String STEWARD_ID = "steward-1";
    static final String ADMIN = "ba-1";
    static final String ADMIN_2 = "ba-2";

    final MutableClock clock = new MutableClock(START);
    final Map<UUID, Document> documents = new LinkedHashMap<>();
    final MemoryRecordLevels recordLevels = new MemoryRecordLevels();
    final MemoryAccessRequests requests = new MemoryAccessRequests(this);
    final List<AuditEvent> events = new CopyOnWriteArrayList<>();
    final AuditEventPort audit = events::add;
    final BusinessRecordRepository records = mock(BusinessRecordRepository.class);
    final DocumentIntakeRepository intake = mock(DocumentIntakeRepository.class);
    final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    ApprovalSettings settings = ApprovalSettings.defaults();
    final SensitiveAccessGrantStore grants;
    final DocumentAccessGuard guard;
    final RecordReferences references;
    AccessRequestService approvals;
    SensitivityLevelService levels;
    final DocumentActionService actions;

    ApprovalsFixture() {
        when(tx.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        document(H1, SUB_A, SensitivityLevel.HIGHLY_CONFIDENTIAL, Instant.parse("2026-11-14T09:00:00Z"), 9);
        document(H2, SUB_A, SensitivityLevel.HIGHLY_CONFIDENTIAL, Instant.parse("2026-11-14T15:00:00Z"), 9);
        document(C1, SUB_A, SensitivityLevel.CONFIDENTIAL, Instant.parse("2026-11-15T09:00:00Z"), 3);
        document(S1, SUB_A, SensitivityLevel.STANDARD, Instant.parse("2026-11-16T09:00:00Z"), 1);
        document(S2, SUB_B, SensitivityLevel.STANDARD, Instant.parse("2026-11-17T09:00:00Z"), 4);

        when(intake.findDocumentByIdForTenant(any(), any())).thenAnswer(i -> TENANT.equals(i.getArgument(0))
                ? Optional.ofNullable(documents.get((UUID) i.getArgument(1))) : Optional.empty());
        when(records.findMetadataSnapshots(any(), any(), anyList())).thenAnswer(i -> {
            if (!TENANT.equals(i.getArgument(0)) || !SOURCE.equals(i.getArgument(1))) return List.of();
            List<MetadataSnapshot> out = new ArrayList<>();
            for (Object sub : (List<?>) i.getArgument(2)) {
                String contract = SUB_A.equals(sub) ? CONTRACT_A : SUB_B.equals(sub) ? CONTRACT_B : null;
                if (contract == null) continue;
                out.add(new MetadataSnapshot((UUID) sub, "contractNumber", "Contract number", contract, contract, true));
                out.add(new MetadataSnapshot((UUID) sub, "lastName", "Last name", "Patel", "PATEL", false));
            }
            return out;
        });
        when(records.findSubmissionIdsByRecordIdentity(any(), any(), anyList())).thenAnswer(i -> {
            if (!TENANT.equals(i.getArgument(0)) || !SOURCE.equals(i.getArgument(1))) return List.of();
            List<?> criteria = i.getArgument(2);
            if (criteria.size() != 1) return List.of();
            BusinessRecordRepository.IdentifierCriterion c = (BusinessRecordRepository.IdentifierCriterion) criteria.getFirst();
            if (!"contractNumber".equals(c.fieldKey())) return List.of();
            return CONTRACT_A.equals(c.normalizedValue()) ? List.of(SUB_A)
                    : CONTRACT_B.equals(c.normalizedValue()) ? List.of(SUB_B) : List.of();
        });
        when(records.findRecordSensitivityLevel(any(), any(), anyString())).thenAnswer(i ->
                TENANT.equals(i.getArgument(0)) ? recordLevels.level((UUID) i.getArgument(1), i.getArgument(2)) : Optional.empty());
        when(records.findRecordSensitivityLevels(any(), any(), anyCollection())).thenAnswer(i -> {
            Map<String, SensitivityLevel> out = new HashMap<>();
            if (!TENANT.equals(i.getArgument(0))) return out;
            for (Object key : (Collection<?>) i.getArgument(2)) {
                recordLevels.level((UUID) i.getArgument(1), (String) key).ifPresent(l -> out.put((String) key, l));
            }
            return out;
        });
        when(records.findAvailableDocuments(any(), any(), anyList())).thenAnswer(i -> {
            if (!TENANT.equals(i.getArgument(0))) return List.of();
            List<?> subs = i.getArgument(2);
            return documents.values().stream()
                    .filter(d -> subs.contains(d.submissionId()) && d.status() == DocumentStatus.AVAILABLE)
                    .map(d -> new AvailableDocumentSnapshot(d.documentId(), d.submissionId(), d.sourceDocumentId(),
                            d.originalFilename(), d.ingestedAt(), d.availableAt(), null, pages.get(d.documentId()), false,
                            d.sensitivityLevel()))
                    .toList();
        });

        grants = new SensitiveAccessGrantStore(requests, audit, tx, clock);
        guard = new DocumentAccessGuard(records, audit, grants, clock);
        references = new RecordReferences(records);
        build();
        actions = new DocumentActionService(intake, mock(DocumentTagRepository.class), mock(DocumentCommentRepository.class),
                mock(BusinessActivityQueryPort.class), audit, guard);
    }

    /** (Re)builds the services, for example after a settings change. */
    void build() {
        approvals = new AccessRequestService(requests, intake, guard, references, records, recordLevels, grants, settings,
                audit, tx, clock);
        levels = new SensitivityLevelService(intake, requests, recordLevels, records, references, guard, approvals, audit,
                tx, clock);
    }

    final Map<UUID, Integer> pages = new HashMap<>();

    private void document(UUID id, UUID submission, SensitivityLevel level, Instant received, int pageCount) {
        documents.put(id, new Document(id, TENANT, submission, SOURCE, "src-" + id, null, "secret-name-" + id + ".pdf",
                "application/pdf", "sha", 1024L, DocumentStatus.AVAILABLE, 365, received, null, received, 1, null, null, level));
        pages.put(id, pageCount);
    }

    Document withLevel(UUID id, SensitivityLevel level) {
        Document d = documents.get(id);
        Document changed = new Document(d.documentId(), d.tenantId(), d.submissionId(), d.sourceId(), d.sourceDocumentId(),
                d.producerIdempotencyKey(), d.originalFilename(), d.mediaType(), d.contentSha256(), d.contentSizeBytes(),
                d.status(), d.retentionDaysSnapshot(), d.ingestedAt(), d.expiresAt(), d.availableAt(),
                d.processingAttemptCount(), d.processingErrorCode(), d.deletedAt(), level);
        documents.put(id, changed);
        return changed;
    }

    Document withStatus(UUID id, DocumentStatus status) {
        Document d = documents.get(id);
        Document changed = new Document(d.documentId(), d.tenantId(), d.submissionId(), d.sourceId(), d.sourceDocumentId(),
                d.producerIdempotencyKey(), d.originalFilename(), d.mediaType(), d.contentSha256(), d.contentSizeBytes(),
                status, d.retentionDaysSnapshot(), d.ingestedAt(), d.expiresAt(), d.availableAt(),
                d.processingAttemptCount(), d.processingErrorCode(), d.deletedAt(), d.sensitivityLevel());
        documents.put(id, changed);
        return changed;
    }

    static String recordRef(String contract) {
        return TestRecordRefs.of(SOURCE, "contractNumber", contract);
    }

    static String recordKey(String contract) {
        return TestRecordRefs.key("contractNumber", contract);
    }

    List<AuditEvent> events(String action) {
        return events.stream().filter(e -> e.action().equals(action)).toList();
    }

    AuditEvent last(String action) {
        List<AuditEvent> found = events(action);
        if (found.isEmpty()) throw new AssertionError("no " + action + " event; events: "
                + events.stream().map(AuditEvent::action).toList());
        return found.getLast();
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** record_sensitivity_levels, in memory. */
    static final class MemoryRecordLevels implements RecordSensitivityRepository {
        final Map<String, Object[]> rows = new LinkedHashMap<>();

        Optional<SensitivityLevel> level(UUID source, String key) {
            Object[] row = rows.get(source + "|" + key);
            return row == null ? Optional.empty() : Optional.of((SensitivityLevel) row[1]);
        }

        void put(String key, SensitivityLevel level) {
            rows.put(SOURCE + "|" + key, new Object[]{UUID.randomUUID(), level, "SOURCE", null});
        }

        @Override public boolean insertIfAbsent(NewRecordLevel l) {
            return rows.putIfAbsent(l.sourceId() + "|" + l.recordKey(),
                    new Object[]{l.recordLevelId(), l.level(), l.setBy(), l.reason()}) == null;
        }

        @Override public Optional<StoredRecordLevel> lockByKey(UUID tenantId, UUID sourceId, String recordKey) {
            Object[] row = TENANT.equals(tenantId) ? rows.get(sourceId + "|" + recordKey) : null;
            return row == null ? Optional.empty() : Optional.of(new StoredRecordLevel((UUID) row[0], (SensitivityLevel) row[1]));
        }

        @Override public void raise(UUID tenantId, UUID recordLevelId, SensitivityLevel level, String setBy, Instant updatedAt) {
            setLevel(tenantId, recordLevelId, level, setBy, null, updatedAt);
        }

        @Override public void setLevel(UUID tenantId, UUID recordLevelId, SensitivityLevel level, String setBy, String reason,
                                       Instant updatedAt) {
            Object[] row = rows.values().stream().filter(r -> r[0].equals(recordLevelId)).findFirst().orElseThrow();
            row[1] = level;
            row[2] = setBy;
            row[3] = reason;
        }

        @Override public long countForSource(UUID tenantId, UUID sourceId) {
            return rows.size();
        }
    }

    /**
     * access_requests, in memory, following the SQL's rules: one pending request per person and target, decisions
     * only on pending rows, grants used once, expiry of pending requests and of unused grants, and nobody deciding
     * their own request (the table's check).
     */
    static final class MemoryAccessRequests implements AccessRequestRepository {
        final Map<UUID, AccessRequest> rows = new LinkedHashMap<>();
        private final ApprovalsFixture world;

        MemoryAccessRequests(ApprovalsFixture world) {
            this.world = world;
        }

        AccessRequest get(UUID id) {
            return rows.get(id);
        }

        void put(AccessRequest r) {
            rows.put(r.requestId(), r);
        }

        @Override public synchronized boolean insertPending(AccessRequest r) {
            boolean duplicate = rows.values().stream().anyMatch(o -> o.status() == AccessRequestStatus.PENDING
                    && o.tenantId().equals(r.tenantId()) && o.requestedBy().equals(r.requestedBy())
                    && (r.documentId() != null
                        ? o.kind() == r.kind() && r.documentId().equals(o.documentId())
                        : o.kind() == r.kind() && Objects.equals(o.sourceId(), r.sourceId()) && Objects.equals(o.recordKey(), r.recordKey())));
            if (duplicate) return false;
            rows.put(r.requestId(), r);
            return true;
        }

        @Override public Optional<AccessRequest> lock(UUID tenantId, UUID requestId) {
            AccessRequest r = rows.get(requestId);
            return r != null && r.tenantId().equals(tenantId) ? Optional.of(r) : Optional.empty();
        }

        @Override public synchronized void decide(UUID tenantId, UUID requestId, AccessRequestStatus status, String decidedBy,
                                                  String decidedByName, Instant decidedAt, Instant expiresAt) {
            AccessRequest r = rows.get(requestId);
            if (r == null || r.status() != AccessRequestStatus.PENDING) throw new IllegalStateException("not pending");
            if (decidedBy.equals(r.requestedBy())) throw new IllegalStateException("ck_access_requests_not_own");
            rows.put(requestId, copy(r, status, decidedBy, decidedByName, decidedAt, expiresAt, null));
        }

        @Override public synchronized List<AccessRequest> expireDue(UUID tenantId, Instant now) {
            List<AccessRequest> changed = new ArrayList<>();
            for (AccessRequest r : List.copyOf(rows.values())) {
                boolean due = r.tenantId().equals(tenantId) && !r.expiresAt().isAfter(now)
                        && (r.status() == AccessRequestStatus.PENDING
                            || (r.status() == AccessRequestStatus.APPROVED && r.kind().grant()));
                if (!due) continue;
                changed.add(r);
                rows.put(r.requestId(), copy(r, AccessRequestStatus.EXPIRED, r.decidedBy(), r.decidedByName(), r.decidedAt(),
                        r.expiresAt(), null));
            }
            return changed;
        }

        private Optional<AccessRequest> validGrant(UUID tenantId, UUID documentId, String requestedBy, AccessRequestKind kind,
                                                   Instant now) {
            return rows.values().stream()
                    .filter(r -> r.tenantId().equals(tenantId) && documentId.equals(r.documentId())
                            && r.requestedBy().equals(requestedBy) && r.kind() == kind
                            && r.status() == AccessRequestStatus.APPROVED && r.expiresAt().isAfter(now))
                    .min(Comparator.comparing(AccessRequest::decidedAt));
        }

        @Override public Optional<UUID> findValidGrant(UUID tenantId, UUID documentId, String requestedBy, AccessRequestKind kind,
                                                       Instant now) {
            return validGrant(tenantId, documentId, requestedBy, kind, now).map(AccessRequest::requestId);
        }

        @Override public synchronized Optional<UUID> useGrant(UUID tenantId, UUID documentId, String requestedBy,
                                                              AccessRequestKind kind, Instant now) {
            Optional<AccessRequest> grant = validGrant(tenantId, documentId, requestedBy, kind, now);
            grant.ifPresent(r -> rows.put(r.requestId(), copy(r, AccessRequestStatus.USED, r.decidedBy(), r.decidedByName(),
                    r.decidedAt(), r.expiresAt(), now)));
            return grant.map(AccessRequest::requestId);
        }

        @Override public List<AccessRequest> findPending(UUID tenantId, Collection<AccessRequestKind> kinds, String requester,
                                                         int limit) {
            return rows.values().stream()
                    .filter(r -> r.tenantId().equals(tenantId) && r.status() == AccessRequestStatus.PENDING)
                    .filter(r -> kinds == null || kinds.isEmpty() || kinds.contains(r.kind()))
                    .filter(r -> requester == null || requester.equals(r.requestedBy()) || requester.equals(r.requestedByName()))
                    .sorted(Comparator.comparing(AccessRequest::requestedAt).thenComparing(AccessRequest::requestId))
                    .limit(limit).toList();
        }

        @Override public List<AccessRequest> findByRequester(UUID tenantId, String requestedBy, int limit) {
            return rows.values().stream()
                    .filter(r -> r.tenantId().equals(tenantId) && r.requestedBy().equals(requestedBy))
                    .sorted(Comparator.comparing(AccessRequest::requestedAt).reversed())
                    .limit(limit).toList();
        }

        @Override public List<AccessRequest> findDecidedSince(UUID tenantId, Instant since, int limit) {
            return rows.values().stream()
                    .filter(r -> r.tenantId().equals(tenantId) && r.decidedAt() != null && !r.decidedAt().isBefore(since))
                    .sorted(Comparator.comparing(AccessRequest::decidedAt).reversed())
                    .limit(limit).toList();
        }

        @Override public List<DocumentFacts> findDocumentFacts(UUID tenantId, Collection<UUID> documentIds) {
            if (!TENANT.equals(tenantId)) return List.of();
            return documentIds.stream().map(world.documents::get).filter(Objects::nonNull)
                    .map(d -> new DocumentFacts(d.documentId(), d.sourceId(), d.submissionId(), d.status().name(),
                            d.sensitivityLevel(), d.ingestedAt(), world.pages.get(d.documentId()),
                            SUB_A.equals(d.submissionId()) ? CONTRACT_A : CONTRACT_B, "Contract number"))
                    .toList();
        }

        @Override public Optional<PrimaryIdentifier> findPrimaryIdentifier(UUID tenantId, UUID submissionId) {
            if (!TENANT.equals(tenantId)) return Optional.empty();
            return SUB_A.equals(submissionId) ? Optional.of(new PrimaryIdentifier(CONTRACT_A, "Contract number"))
                    : SUB_B.equals(submissionId) ? Optional.of(new PrimaryIdentifier(CONTRACT_B, "Contract number"))
                    : Optional.empty();
        }

        @Override public Optional<LockedDocument> lockDocument(UUID tenantId, UUID documentId) {
            Document d = TENANT.equals(tenantId) ? world.documents.get(documentId) : null;
            return d == null ? Optional.empty() : Optional.of(new LockedDocument(d.documentId(), d.status().name(), d.sensitivityLevel()));
        }

        @Override public void updateDocumentLevel(UUID tenantId, UUID documentId, SensitivityLevel level) {
            world.withLevel(documentId, level);
        }

        /** Task 0029 (0.4): the level-change history, newest last. */
        final List<LevelChange> changes = new CopyOnWriteArrayList<>();

        /** Newest first: by time, and among changes at the same time the one written last first. */
        private java.util.stream.Stream<LevelChange> reversed() {
            List<LevelChange> all = new ArrayList<>(changes);
            java.util.Collections.reverse(all);
            return all.stream();
        }

        @Override public void insertLevelChange(LevelChange change) {
            changes.add(change);
        }

        @Override public List<LevelChange> findDocumentLevelChanges(UUID tenantId, UUID documentId, int limit) {
            return reversed().filter(c -> c.tenantId().equals(tenantId) && documentId.equals(c.documentId()))
                    .sorted(Comparator.comparing(LevelChange::changedAt).reversed()).limit(limit).toList();
        }

        @Override public List<LevelChange> findRecordLevelChanges(UUID tenantId, UUID sourceId, String recordKey, int limit) {
            return reversed().filter(c -> c.tenantId().equals(tenantId) && c.scope() == LevelChange.Scope.RECORD
                            && sourceId.equals(c.sourceId()) && recordKey.equals(c.recordKey()))
                    .sorted(Comparator.comparing(LevelChange::changedAt).reversed()).limit(limit).toList();
        }

        private static AccessRequest copy(AccessRequest r, AccessRequestStatus status, String decidedBy, String decidedByName,
                                          Instant decidedAt, Instant expiresAt, Instant usedAt) {
            return new AccessRequest(r.requestId(), r.tenantId(), r.kind(), r.documentId(), r.sourceId(), r.recordKey(),
                    r.requestedLevel(), r.reason(), r.requestedBy(), r.requestedByName(), r.requestedAt(), status, decidedBy,
                    decidedByName, decidedAt, expiresAt, usedAt);
        }
    }
}
