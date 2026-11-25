package com.sdv.platform.persistence.approvals;

import com.sdv.platform.persistence.business.PrimaryIdentifierSql;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.application.approvals.AccessRequestKind;
import com.sdv.platform.application.approvals.AccessRequestStatus;
import com.sdv.platform.application.business.RecordKey;
import com.sdv.platform.application.port.AccessRequestRepository;
import com.sdv.platform.domain.document.SensitivityLevel;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0028: access_requests (migration 027), and the document facts the approvals read. Every statement is limited
 * to one tenant. No statement reads a file name or a record title.
 */
@Repository
public class JdbcAccessRequestRepository implements AccessRequestRepository {
    static final String COLUMNS = """
            request_id, tenant_id, kind, document_id, source_id, record_id, requested_level, reason, requested_by,
            requested_by_display_name, requested_at, status, decided_by, decided_by_display_name, decided_at,
            expires_at, used_at
            """;
    /** The record's primary identifier of document d and its label: the one rule (task 0056). */
    static final String PRIMARY_IDENTIFIER = PrimaryIdentifierSql.join("d.tenant_id", "d.submission_id");

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAccessRequestRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertPending(AccessRequest r) {
        return insertPending(r, null);
    }

    @Override
    public boolean insertHoldRelease(AccessRequest request, UUID holdId) {
        return insertPending(request, holdId);
    }

    @Override
    public Optional<UUID> holdOf(UUID tenantId, UUID requestId) {
        return jdbc.query("select hold_id from access_requests where tenant_id=:tenantId and request_id=:id and hold_id is not null",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", requestId),
                (rs, row) -> rs.getObject("hold_id", UUID.class)).stream().findFirst();
    }

    @Override
    public boolean holdReleaseWaiting(UUID tenantId, UUID holdId, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from access_requests
                               where tenant_id=:tenantId and hold_id=:holdId and status='PENDING' and expires_at>:now)
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("holdId", holdId)
                .addValue("now", Timestamp.from(now)), Boolean.class));
    }

    @Override
    public List<HoldReleaseWaiting> holdReleasesWaiting(UUID tenantId, Instant now) {
        return jdbc.query("""
                select hold_id, request_id, coalesce(requested_by_display_name, requested_by) as who, requested_at
                  from access_requests
                 where tenant_id=:tenantId and kind='RELEASE_HOLD' and status='PENDING' and expires_at>:now
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("now", Timestamp.from(now)),
                (rs, row) -> new HoldReleaseWaiting(rs.getObject("hold_id", UUID.class), rs.getObject("request_id", UUID.class),
                        rs.getString("who"), rs.getTimestamp("requested_at").toInstant()));
    }

    private boolean insertPending(AccessRequest r, UUID holdId) {
        // The partial unique indexes allow one pending request per person and target; a second one inserts nothing.
        int inserted = jdbc.update("""
                insert into access_requests(request_id, tenant_id, kind, document_id, source_id, record_id,
                    requested_level, reason, requested_by, requested_by_display_name, requested_at,
                    status, expires_at, hold_id)
                values (:id, :tenantId, :kind, :documentId, :sourceId, :recordId, :requestedLevel, :reason,
                    :requestedBy, :requestedByName, :requestedAt, 'PENDING', :expiresAt, :holdId)
                on conflict do nothing
                """, new MapSqlParameterSource()
                .addValue("id", r.requestId())
                .addValue("tenantId", r.tenantId())
                .addValue("kind", r.kind().name())
                .addValue("documentId", r.documentId())
                .addValue("sourceId", r.sourceId())
                .addValue("recordId", r.recordId())
                .addValue("requestedLevel", r.requestedLevel() == null ? null : r.requestedLevel().name())
                .addValue("reason", r.reason())
                .addValue("requestedBy", r.requestedBy())
                .addValue("requestedByName", r.requestedByName())
                .addValue("requestedAt", Timestamp.from(r.requestedAt()))
                .addValue("expiresAt", Timestamp.from(r.expiresAt()))
                .addValue("holdId", holdId));
        return inserted == 1;
    }

    @Override
    public void insertHeldRedaction(AccessRequest r, String regionsJson, String contentSha256) {
        jdbc.update("""
                insert into held_redactions(request_id, tenant_id, document_id, regions_json, content_sha256, state)
                values (:id, :tenantId, :documentId, cast(:regions as jsonb), :sha, 'WAITING')
                """, new MapSqlParameterSource()
                .addValue("id", r.requestId()).addValue("tenantId", r.tenantId()).addValue("documentId", r.documentId())
                .addValue("regions", regionsJson).addValue("sha", contentSha256));
    }

    @Override
    public boolean approveHeldRedaction(UUID tenantId, UUID requestId, UUID documentId) {
        return jdbc.update("""
                update held_redactions h
                   set state='APPROVED'
                 where h.tenant_id=:tenantId and h.request_id=:id and h.document_id=:documentId and h.state='WAITING'
                   and exists (select 1 from documents d
                                where d.tenant_id=h.tenant_id and d.document_id=h.document_id
                                  and d.status='AVAILABLE' and d.content_sha256=h.content_sha256)
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", requestId)
                .addValue("documentId", documentId)) == 1;
    }

    @Override
    public Optional<AccessRequest> lock(UUID tenantId, UUID requestId) {
        return jdbc.query("select " + COLUMNS + " from access_requests where tenant_id=:tenantId and request_id=:id for update",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", requestId),
                (rs, row) -> request(rs)).stream().findFirst();
    }

    @Override
    public boolean decide(UUID tenantId, UUID requestId, AccessRequestStatus status, String decidedBy, String decidedByName,
                       Instant decidedAt, Instant expiresAt) {
        int updated = jdbc.update("""
                update access_requests
                   set status=:status, decided_by=:decidedBy, decided_by_display_name=:decidedByName,
                       decided_at=:decidedAt, expires_at=:expiresAt
                 where tenant_id=:tenantId and request_id=:id and status='PENDING'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", requestId)
                .addValue("status", status.name())
                .addValue("decidedBy", decidedBy)
                .addValue("decidedByName", decidedByName)
                .addValue("decidedAt", Timestamp.from(decidedAt))
                .addValue("expiresAt", Timestamp.from(expiresAt)));
        if (updated > 1) throw new InternalCheckFailed("The decision changed more than one pending request.");
        return updated == 1;
    }

    @Override
    public List<AccessRequest> expireDue(UUID tenantId, Instant now) {
        // The old status is returned (from the row as it was) so the audit can say what expired.
        return jdbc.query("""
                with due as (
                    select request_id, status as was
                      from access_requests
                     where tenant_id=:tenantId and expires_at<=:now
                       and (status='PENDING' or (status='APPROVED' and kind in ('PRINT', 'DOWNLOAD')))
                       for update skip locked)
                update access_requests a
                   set status='EXPIRED'
                  from due
                 where a.tenant_id=:tenantId and a.request_id=due.request_id
                returning a.request_id, a.tenant_id, a.kind, a.document_id, a.source_id, a.record_id, a.requested_level,
                          a.reason, a.requested_by, a.requested_by_display_name, a.requested_at, due.was as status,
                          a.decided_by, a.decided_by_display_name, a.decided_at, a.expires_at, a.used_at
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("now", Timestamp.from(now)),
                (rs, row) -> request(rs));
    }

    @Override
    public Optional<UUID> findValidGrant(UUID tenantId, UUID documentId, String requestedBy, AccessRequestKind kind,
                                         Instant now) {
        return jdbc.query("""
                select request_id from access_requests
                 where tenant_id=:tenantId and document_id=:documentId and requested_by=:requestedBy and kind=:kind
                   and status='APPROVED' and expires_at>:now
                 order by decided_at, request_id
                 limit 1
                """, grant(tenantId, documentId, requestedBy, kind, now),
                (rs, row) -> rs.getObject("request_id", UUID.class)).stream().findFirst();
    }

    @Override
    public Optional<UUID> useGrant(UUID tenantId, UUID documentId, String requestedBy, AccessRequestKind kind, Instant now) {
        // One statement: the oldest valid grant is locked and marked used; a second use at the same time skips it and
        // finds no other.
        return jdbc.query("""
                update access_requests
                   set status='USED', used_at=greatest(:now, decided_at)
                 where tenant_id=:tenantId and request_id = (
                       select request_id from access_requests
                        where tenant_id=:tenantId and document_id=:documentId and requested_by=:requestedBy
                          and kind=:kind and status='APPROVED' and expires_at>:now
                        order by decided_at, request_id
                        limit 1
                          for update skip locked)
                   and status='APPROVED'
                returning request_id
                """, grant(tenantId, documentId, requestedBy, kind, now),
                (rs, row) -> rs.getObject("request_id", UUID.class)).stream().findFirst();
    }

    @Override
    public boolean giveBackGrant(UUID tenantId, UUID requestId, UUID documentId, String requestedBy,
                                 AccessRequestKind kind) {
        return jdbc.update("""
                update access_requests
                   set status='APPROVED', used_at=null
                 where tenant_id=:tenantId and request_id=:requestId and document_id=:documentId
                   and requested_by=:requestedBy and kind=:kind and status='USED'
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("requestId", requestId)
                .addValue("documentId", documentId)
                .addValue("requestedBy", requestedBy)
                .addValue("kind", kind.name())) == 1;
    }

    @Override
    public List<AccessRequest> findPending(UUID tenantId, Collection<AccessRequestKind> kinds, String requester, int limit) {
        List<String> kindNames = kinds == null || kinds.isEmpty()
                ? List.of(AccessRequestKind.values()).stream().map(Enum::name).toList()
                : kinds.stream().map(Enum::name).toList();
        return jdbc.query("select " + COLUMNS + """
                  from access_requests
                 where tenant_id=:tenantId and status='PENDING' and kind in (:kinds)
                   and (cast(:requester as text) is null or requested_by=:requester
                        or requested_by_display_name=:requester)
                 order by requested_at, request_id
                 limit :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("kinds", kindNames)
                .addValue("requester", requester)
                .addValue("limit", limit), (rs, row) -> request(rs));
    }

    @Override
    public List<AccessRequest> findByRequester(UUID tenantId, String requestedBy, int limit) {
        return jdbc.query("select " + COLUMNS + """
                  from access_requests
                 where tenant_id=:tenantId and requested_by=:requestedBy
                 order by requested_at desc, request_id
                 limit :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("requestedBy", requestedBy)
                .addValue("limit", limit), (rs, row) -> request(rs));
    }

    @Override
    public List<AccessRequest> findDecidedSince(UUID tenantId, Instant since, int limit) {
        return jdbc.query("select " + COLUMNS + """
                  from access_requests
                 where tenant_id=:tenantId and decided_at is not null and decided_at>=:since
                 order by decided_at desc, request_id
                 limit :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("since", Timestamp.from(since))
                .addValue("limit", limit), (rs, row) -> request(rs));
    }

    @Override
    public List<DocumentFacts> findDocumentFacts(UUID tenantId, Collection<UUID> documentIds) {
        if (documentIds.isEmpty()) return List.of();
        return jdbc.query("""
                select d.document_id, d.source_id, d.submission_id, d.status, d.sensitivity_level, d.ingested_at,
                       d.page_count, pid.stored_value as primary_identifier, pid.display_label as primary_identifier_label
                  from documents d
                """ + PRIMARY_IDENTIFIER + """
                 where d.tenant_id=:tenantId and d.document_id in (:ids)
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("ids", List.copyOf(documentIds)),
                (rs, row) -> {
                    String level = rs.getString("sensitivity_level");
                    Timestamp received = rs.getTimestamp("ingested_at");
                    return new DocumentFacts(
                            rs.getObject("document_id", UUID.class),
                            rs.getObject("source_id", UUID.class),
                            rs.getObject("submission_id", UUID.class),
                            rs.getString("status"),
                            level == null ? null : SensitivityLevel.valueOf(level),
                            received == null ? null : received.toInstant(),
                            rs.getObject("page_count", Integer.class),
                            rs.getString("primary_identifier"),
                            rs.getString("primary_identifier_label"));
                });
    }

    @Override
    public Optional<PrimaryIdentifier> findPrimaryIdentifier(UUID tenantId, UUID submissionId) {
        return jdbc.query("""
                select pid.stored_value, pid.display_label
                  from submissions d
                """ + PRIMARY_IDENTIFIER + """
                 where d.tenant_id=:tenantId and d.submission_id=:submissionId and pid.stored_value is not null
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("submissionId", submissionId),
                (rs, row) -> new PrimaryIdentifier(rs.getString("stored_value"), rs.getString("display_label")))
                .stream().findFirst();
    }

    @Override
    public Optional<LockedDocument> lockDocument(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                select document_id, status, sensitivity_level from documents
                 where tenant_id=:tenantId and document_id=:documentId
                 for update
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("documentId", documentId),
                (rs, row) -> {
                    String level = rs.getString("sensitivity_level");
                    return new LockedDocument(rs.getObject("document_id", UUID.class), rs.getString("status"),
                            level == null ? null : SensitivityLevel.valueOf(level));
                }).stream().findFirst();
    }

    @Override
    public void updateDocumentLevel(UUID tenantId, UUID documentId, SensitivityLevel level) {
        int updated = jdbc.update("""
                update documents set sensitivity_level=:level where tenant_id=:tenantId and document_id=:documentId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("documentId", documentId)
                .addValue("level", level.name()));
        if (updated != 1) throw new InternalCheckFailed("The document's level could not be changed.");
    }

    static final String CHANGE_COLUMNS = """
            change_id, tenant_id, scope, document_id, source_id, record_id, direction, from_level, to_level, reason,
            changed_by, changed_by_display_name, changed_at, access_request_id
            """;

    @Override
    public void insertLevelChange(LevelChange c) {
        jdbc.update("insert into sensitivity_level_changes(" + CHANGE_COLUMNS + ") values ("
                + ":changeId, :tenantId, :scope, :documentId, :sourceId, :recordId, :direction, :fromLevel, :toLevel,"
                + " :reason, :changedBy, :changedByName, :changedAt, :accessRequestId)", new MapSqlParameterSource()
                .addValue("changeId", c.changeId())
                .addValue("tenantId", c.tenantId())
                .addValue("scope", c.scope().name())
                .addValue("documentId", c.documentId())
                .addValue("sourceId", c.sourceId())
                .addValue("recordId", c.recordId())
                .addValue("direction", c.direction().name())
                .addValue("fromLevel", c.from() == null ? null : c.from().name())
                .addValue("toLevel", c.to().name())
                .addValue("reason", c.reason())
                .addValue("changedBy", c.changedBy())
                .addValue("changedByName", c.changedByName())
                .addValue("changedAt", Timestamp.from(c.changedAt()))
                .addValue("accessRequestId", c.accessRequestId()));
    }

    @Override
    public List<LevelChange> findDocumentLevelChanges(UUID tenantId, UUID documentId, int limit) {
        return jdbc.query("select " + CHANGE_COLUMNS + """
                  from sensitivity_level_changes
                 where tenant_id=:tenantId and document_id=:documentId
                 order by changed_at desc, change_id
                 limit :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("documentId", documentId)
                .addValue("limit", limit), (rs, rowNum) -> change(rs));
    }

    @Override
    public List<LevelChange> findRecordLevelChanges(UUID tenantId, UUID sourceId, UUID recordId, int limit) {
        return jdbc.query("select " + CHANGE_COLUMNS + """
                  from sensitivity_level_changes
                 where tenant_id=:tenantId and scope='RECORD' and source_id=:sourceId and record_id=:recordId
                 order by changed_at desc, change_id
                 limit :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("recordId", recordId)
                .addValue("limit", limit), (rs, rowNum) -> change(rs));
    }

    private static LevelChange change(ResultSet rs) throws SQLException {
        String from = rs.getString("from_level");
        return new LevelChange(
                rs.getObject("change_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                LevelChange.Scope.valueOf(rs.getString("scope")),
                rs.getObject("document_id", UUID.class),
                rs.getObject("source_id", UUID.class),
                rs.getObject("record_id", UUID.class),
                LevelChange.Direction.valueOf(rs.getString("direction")),
                from == null ? null : SensitivityLevel.valueOf(from),
                SensitivityLevel.valueOf(rs.getString("to_level")),
                rs.getString("reason"),
                rs.getString("changed_by"),
                rs.getString("changed_by_display_name"),
                instant(rs, "changed_at"),
                rs.getObject("access_request_id", UUID.class));
    }

    private static MapSqlParameterSource grant(UUID tenantId, UUID documentId, String requestedBy, AccessRequestKind kind,
                                               Instant now) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("documentId", documentId)
                .addValue("requestedBy", requestedBy)
                .addValue("kind", kind.name())
                .addValue("now", Timestamp.from(now));
    }

    private static AccessRequest request(ResultSet rs) throws SQLException {
        String level = rs.getString("requested_level");
        return new AccessRequest(
                rs.getObject("request_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                AccessRequestKind.valueOf(rs.getString("kind")),
                rs.getObject("document_id", UUID.class),
                rs.getObject("source_id", UUID.class),
                rs.getObject("record_id", UUID.class),
                level == null ? null : SensitivityLevel.valueOf(level),
                rs.getString("reason"),
                rs.getString("requested_by"),
                rs.getString("requested_by_display_name"),
                instant(rs, "requested_at"),
                AccessRequestStatus.valueOf(rs.getString("status")),
                rs.getString("decided_by"),
                rs.getString("decided_by_display_name"),
                instant(rs, "decided_at"),
                instant(rs, "expires_at"),
                instant(rs, "used_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
