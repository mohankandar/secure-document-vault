package com.sdv.platform.persistence.quarantine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.QuarantineRepository;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.InternalCheckFailed;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0048: quarantine tables (changeset 036). Every statement is limited to one entity (tenant_id), except
 * {@link #findPreparing}, which the background job uses to find work in every entity.
 * <p>
 * Task 0051 (changeset 038): an open filter incident quarantines every document matching its filter and received up to
 * its cut-off, before the document's own row exists ({@link #filterBlocked}). Every document path and the retention
 * cleanup ask through the same SQL, so there is one rule.
 */
@Repository
public class JdbcQuarantineRepository implements QuarantineRepository {
    /** The record's primary identifier, as for legal holds: its first business identifier in display order. */
    static final String PRIMARY_IDENTIFIER = """
            left join lateral (
                select v.stored_value, f.display_label
                  from business_metadata_values v
                  join metadata_field_definitions f on f.definition_id=v.definition_id and f.business_identifier
                  left join metadata_field_display_order o
                    on o.tenant_id=v.tenant_id and o.source_id=f.source_id and o.field_key=v.field_key
                 where v.tenant_id=d.tenant_id and v.submission_id=d.submission_id and btrim(v.stored_value)<>''
                 order by o.display_position nulls last, v.field_key
                 limit 1) pid on true
            """;
    static final String DOCUMENT_COLUMNS = """
            d.document_id, d.source_id, d.submission_id, d.status, d.sensitivity_level, d.ingested_at, d.page_count,
            pid.stored_value as primary_identifier, pid.display_label as primary_identifier_label
            """;
    static final String NOT_DELETED = "d.status not in ('EXPIRED_DELETED', 'RETENTION_DELETING')";
    static final String IN_FORCE = """
            exists (select 1 from quarantined_documents q
                     where q.tenant_id=d.tenant_id and q.document_id=d.document_id and q.released_at is null)""";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Task 0051: the document (a row of {@code documents} named {@code d}) is quarantined by an open filter incident:
     * it matches the incident's filter, was received up to the cut-off, and no release since the incident's
     * confirmation freed it. Used by every document path ({@link #isQuarantined}, {@link #quarantined}) and by the
     * retention cleanup.
     */
    public static String filterBlocked(String d) {
        return "exists (select 1 from quarantine_incidents qi where " + blockedBy(d) + ")";
    }

    /** The open incident {@code qi} quarantines the document {@code d} by its filter: the rule, written once. */
    static String blockedBy(String d) {
        return """
                qi.tenant_id=%1$s.tenant_id and qi.status='OPEN' and qi.filter_received_from is not null
                and %1$s.ingested_at >= qi.filter_received_from and %1$s.ingested_at < qi.filter_received_to
                and %1$s.ingested_at <= qi.cutoff_received_at
                and (qi.filter_source_id is null or %1$s.source_id=qi.filter_source_id)
                and not exists (select 1 from jsonb_each_text(qi.filter_fields) ff
                                 where not exists (select 1 from business_metadata_values bv
                                                    where bv.tenant_id=%1$s.tenant_id
                                                      and bv.submission_id=%1$s.submission_id
                                                      and bv.field_key=ff.key
                                                      and coalesce(bv.normalized_value, bv.stored_value)=ff.value))
                and not exists (select 1 from quarantined_documents qr
                                 where qr.tenant_id=%1$s.tenant_id and qr.document_id=%1$s.document_id
                                   and qr.released_at is not null and qr.released_at >= qi.created_at)"""
                .formatted(d);
    }

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcQuarantineRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isQuarantined(UUID tenantId, UUID documentId) {
        if (tenantId == null || documentId == null) return false;
        Boolean any = jdbc.queryForObject("""
                select exists(select 1 from quarantined_documents
                               where tenant_id=:tenantId and document_id=:documentId and released_at is null)
                    or exists(select 1 from documents d
                               where d.tenant_id=:tenantId and d.document_id=:documentId and %s)
                """.formatted(filterBlocked("d")), document(tenantId, documentId), Boolean.class);
        return Boolean.TRUE.equals(any);
    }

    @Override
    public Set<UUID> quarantined(UUID tenantId, Collection<UUID> documentIds) {
        if (tenantId == null || documentIds == null || documentIds.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.query("""
                select document_id from quarantined_documents
                 where tenant_id=:tenantId and document_id in (:ids) and released_at is null
                union
                select d.document_id from documents d
                 where d.tenant_id=:tenantId and d.document_id in (:ids) and %s
                """.formatted(filterBlocked("d")),
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("ids", List.copyOf(documentIds)),
                (rs, row) -> rs.getObject(1, UUID.class)));
    }

    @Override
    public Optional<DocumentFacts> lockDocument(UUID tenantId, UUID documentId) {
        List<UUID> locked = jdbc.query("select document_id from documents where tenant_id=:tenantId and document_id=:documentId for update",
                document(tenantId, documentId), (rs, row) -> rs.getObject(1, UUID.class));
        if (locked.isEmpty()) return Optional.empty();
        return jdbc.query("select " + DOCUMENT_COLUMNS + " from documents d " + PRIMARY_IDENTIFIER
                + " where d.tenant_id=:tenantId and d.document_id=:documentId", document(tenantId, documentId),
                (rs, row) -> facts(rs, "")).stream().findFirst();
    }

    @Override
    public void insertIncident(Incident i) {
        jdbc.update("""
                insert into quarantine_incidents(incident_id, tenant_id, name, reason, scope, created_by,
                    created_by_display_name, created_at, status)
                values (:id, :tenantId, :name, :reason, :scope, :by, :byName, :at, 'OPEN')
                """, new MapSqlParameterSource().addValue("id", i.incidentId()).addValue("tenantId", i.tenantId())
                .addValue("name", i.name()).addValue("reason", i.reason()).addValue("scope", i.scope())
                .addValue("by", i.createdBy()).addValue("byName", i.createdByName())
                .addValue("at", Timestamp.from(i.createdAt())));
    }

    private static final String INCIDENT = """
            select incident_id, tenant_id, name, reason, scope, created_by, created_by_display_name, created_at, status,
                   closed_at from quarantine_incidents
            """;

    @Override
    public Optional<Incident> findIncident(UUID tenantId, UUID incidentId) {
        return jdbc.query(INCIDENT + " where tenant_id=:tenantId and incident_id=:id", incident(tenantId, incidentId),
                (rs, row) -> incident(rs)).stream().findFirst();
    }

    @Override
    public Optional<Incident> lockIncident(UUID tenantId, UUID incidentId) {
        return jdbc.query(INCIDENT + " where tenant_id=:tenantId and incident_id=:id for update",
                incident(tenantId, incidentId), (rs, row) -> incident(rs)).stream().findFirst();
    }

    @Override
    public List<Incident> findIncidents(UUID tenantId, int limit) {
        return jdbc.query(INCIDENT + " where tenant_id=:tenantId order by status, created_at desc, incident_id limit :limit",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("limit", limit), (rs, row) -> incident(rs));
    }

    @Override
    public void closeIncident(UUID tenantId, UUID incidentId, Instant closedAt) {
        jdbc.update("update quarantine_incidents set status='RELEASED', closed_at=:at where tenant_id=:tenantId and incident_id=:id and status='OPEN'",
                incident(tenantId, incidentId).addValue("at", Timestamp.from(closedAt)));
    }

    @Override
    public void insertQuarantine(UUID quarantineId, UUID tenantId, UUID incidentId, UUID documentId, String by,
                                 String byName, Instant at) {
        jdbc.update("""
                insert into quarantined_documents(quarantine_id, tenant_id, incident_id, document_id, quarantined_by,
                    quarantined_by_display_name, quarantined_at)
                values (:id, :tenantId, :incidentId, :documentId, :by, :byName, :at)
                """, new MapSqlParameterSource().addValue("id", quarantineId).addValue("tenantId", tenantId)
                .addValue("incidentId", incidentId).addValue("documentId", documentId).addValue("by", by)
                .addValue("byName", byName).addValue("at", Timestamp.from(at)));
    }

    private static final String QUARANTINED = "select q.quarantine_id, q.incident_id, q.quarantined_by, "
            + "q.quarantined_by_display_name, q.quarantined_at, q.released_by, q.released_by_display_name, q.released_at, "
            + DOCUMENT_COLUMNS + " from quarantined_documents q join documents d on d.tenant_id=q.tenant_id "
            + "and d.document_id=q.document_id " + PRIMARY_IDENTIFIER;

    @Override
    public Optional<Quarantined> findActive(UUID tenantId, UUID documentId) {
        return jdbc.query(QUARANTINED + " where q.tenant_id=:tenantId and q.document_id=:documentId and q.released_at is null",
                document(tenantId, documentId), (rs, row) -> quarantined(rs)).stream().findFirst();
    }

    @Override
    public List<Quarantined> findInIncident(UUID tenantId, UUID incidentId, int limit) {
        return jdbc.query(QUARANTINED + " where q.tenant_id=:tenantId and q.incident_id=:id "
                        + "order by (q.released_at is not null), q.quarantined_at, d.ingested_at, d.document_id limit :limit",
                incident(tenantId, incidentId).addValue("limit", limit), (rs, row) -> quarantined(rs));
    }

    @Override
    public List<Quarantined> findInIncidentPage(UUID tenantId, UUID incidentId, boolean inForceOnly,
                                                UUID afterQuarantineId, int limit) {
        MapSqlParameterSource p = incident(tenantId, incidentId).addValue("limit", limit);
        StringBuilder sql = new StringBuilder(QUARANTINED + " where q.tenant_id=:tenantId and q.incident_id=:id");
        if (inForceOnly) sql.append(" and q.released_at is null");
        if (afterQuarantineId != null) { sql.append(" and q.quarantine_id > :after"); p.addValue("after", afterQuarantineId); }
        sql.append(" order by q.quarantine_id limit :limit");
        return jdbc.query(sql.toString(), p, (rs, row) -> quarantined(rs));
    }

    @Override
    public long countInIncident(UUID tenantId, UUID incidentId) {
        Long n = jdbc.queryForObject("select count(*) from quarantined_documents where tenant_id=:tenantId and incident_id=:id",
                incident(tenantId, incidentId), Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public Map<UUID, Integer> countInForce(UUID tenantId, List<UUID> incidentIds) {
        Map<UUID, Integer> out = new LinkedHashMap<>();
        if (incidentIds.isEmpty()) return out;
        jdbc.query("""
                select incident_id, count(*) from quarantined_documents
                 where tenant_id=:tenantId and incident_id in (:ids) and released_at is null group by incident_id
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("ids", incidentIds),
                rs -> { out.put(rs.getObject(1, UUID.class), rs.getInt(2)); });
        return out;
    }

    @Override
    public boolean release(UUID tenantId, UUID quarantineId, String by, String byName, String reason, UUID requestId,
                           Instant at) {
        int updated = jdbc.update("""
                update quarantined_documents
                   set released_by=:by, released_by_display_name=:byName, released_at=:at, release_reason=:reason,
                       release_request_id=:requestId
                 where tenant_id=:tenantId and quarantine_id=:id and released_at is null
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", quarantineId)
                .addValue("by", by).addValue("byName", byName).addValue("reason", reason)
                .addValue("requestId", requestId).addValue("at", Timestamp.from(at)));
        if (updated > 1) throw new InternalCheckFailed("A quarantine release changed more than one row.");
        return updated == 1;
    }

    @Override
    public List<Released> releaseAllInForce(UUID tenantId, UUID incidentId, String by, String byName, String reason,
                                            UUID requestId, Instant at) {
        return jdbc.query("""
                update quarantined_documents
                   set released_by=:by, released_by_display_name=:byName, released_at=greatest(:at, quarantined_at),
                       release_reason=:reason, release_request_id=:requestId
                 where tenant_id=:tenantId and incident_id=:id and released_at is null
                returning quarantine_id, document_id, quarantined_at
                """, incident(tenantId, incidentId).addValue("by", by).addValue("byName", byName)
                .addValue("reason", reason).addValue("requestId", requestId).addValue("at", Timestamp.from(at)),
                (rs, row) -> new Released(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getTimestamp(3).toInstant()));
    }

    /** The filter's own conditions on {@code d}: received dates, sending system, field values (normalized, as search). */
    private static String matches(Filter f, MapSqlParameterSource p) {
        StringBuilder sql = new StringBuilder();
        if (f.receivedFrom() != null) { sql.append(" and d.ingested_at >= :from"); p.addValue("from", Timestamp.from(f.receivedFrom())); }
        if (f.receivedTo() != null) { sql.append(" and d.ingested_at < :to"); p.addValue("to", Timestamp.from(f.receivedTo())); }
        if (f.sourceId() != null) { sql.append(" and d.source_id = :sourceId"); p.addValue("sourceId", f.sourceId()); }
        int i = 0;
        for (Map.Entry<String, String> field : f.fields().entrySet()) {
            String k = "fk" + i, v = "fv" + i;
            sql.append(" and exists (select 1 from business_metadata_values v where v.tenant_id=d.tenant_id")
                    .append(" and v.submission_id=d.submission_id and v.field_key=:").append(k)
                    .append(" and coalesce(v.normalized_value, v.stored_value)=:").append(v).append(")");
            p.addValue(k, field.getKey()).addValue(v, field.getValue());
            i++;
        }
        return sql.toString();
    }

    /** Documents the filter would quarantine now: matching, not deleted, not quarantined in any way already. */
    private static String where(Filter f, MapSqlParameterSource p) {
        return " where d.tenant_id=:tenantId and " + NOT_DELETED + " and not " + IN_FORCE + " and not " + filterBlocked("d")
                + matches(f, p);
    }

    @Override
    public long countMatching(UUID tenantId, Filter filter) {
        return matchStats(tenantId, filter).count();
    }

    @Override
    public MatchStats matchStats(UUID tenantId, Filter filter) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenantId", tenantId);
        return jdbc.queryForObject("select count(*), max(d.ingested_at) from documents d" + where(filter, p), p,
                (rs, row) -> new MatchStats(rs.getLong(1), instant(rs, 2)));
    }

    // ------------------------------------------------------------------ task 0051: blocking and the background job

    @Override
    public void startFilterBlocking(UUID tenantId, UUID incidentId, Filter filter, Instant cutoffReceivedAt,
                                    long expectedCount) {
        int updated = jdbc.update("""
                update quarantine_incidents
                   set filter_received_from=:from, filter_received_to=:to, filter_source_id=:sourceId,
                       filter_fields=cast(:fields as jsonb), cutoff_received_at=:cutoff, expected_count=:expected
                 where tenant_id=:tenantId and incident_id=:id and scope='FILTER' and filter_received_from is null
                """, incident(tenantId, incidentId)
                .addValue("from", Timestamp.from(filter.receivedFrom())).addValue("to", Timestamp.from(filter.receivedTo()))
                .addValue("sourceId", filter.sourceId()).addValue("fields", json(filter.fields()))
                .addValue("cutoff", Timestamp.from(cutoffReceivedAt)).addValue("expected", expectedCount));
        if (updated != 1) throw new InternalCheckFailed("The filter of a quarantine incident could not be stored.");
    }

    private static final String PREPARATION = """
            select incident_id, tenant_id, filter_received_from, filter_received_to, filter_source_id, filter_fields::text as fields,
                   cutoff_received_at, expected_count, prepared_count, prepared_at, cursor_received_at, cursor_document_id,
                   created_by, created_by_display_name, created_at, status
              from quarantine_incidents
            """;

    @Override
    public Optional<Preparation> findPreparation(UUID tenantId, UUID incidentId) {
        return jdbc.query(PREPARATION + " where tenant_id=:tenantId and incident_id=:id and filter_received_from is not null",
                incident(tenantId, incidentId), (rs, row) -> preparation(rs)).stream().findFirst();
    }

    @Override
    public Optional<Preparation> lockPreparation(UUID tenantId, UUID incidentId) {
        return jdbc.query(PREPARATION + " where tenant_id=:tenantId and incident_id=:id and filter_received_from is not null"
                        + " for update skip locked", incident(tenantId, incidentId), (rs, row) -> preparation(rs))
                .stream().findFirst();
    }

    @Override
    public List<IncidentRef> findPreparing(int limit) {
        return jdbc.query("""
                select tenant_id, incident_id from quarantine_incidents
                 where filter_received_from is not null and prepared_at is null and status='OPEN'
                 order by created_at, incident_id limit :limit
                """, new MapSqlParameterSource("limit", limit),
                (rs, row) -> new IncidentRef(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
    }

    @Override
    public Batch prepareBatch(Preparation p, int size, String by, String byName, Instant at) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tenantId", p.tenantId())
                .addValue("cutoff", Timestamp.from(p.cutoffReceivedAt())).addValue("size", size);
        StringBuilder scan = new StringBuilder("select d.document_id, d.ingested_at from documents d where d.tenant_id=:tenantId"
                + " and d.ingested_at <= :cutoff" + matches(p.filter(), params));
        if (p.cursorReceivedAt() != null) {
            scan.append(" and (d.ingested_at, d.document_id) > (:cursorAt, :cursorId)");
            params.addValue("cursorAt", Timestamp.from(p.cursorReceivedAt())).addValue("cursorId", p.cursorDocumentId());
        }
        scan.append(" order by d.ingested_at, d.document_id limit :size");
        List<UUID> ids = new ArrayList<>();
        Instant[] last = new Instant[1];
        jdbc.query(scan.toString(), params, rs -> {
            ids.add(rs.getObject(1, UUID.class));
            last[0] = rs.getTimestamp(2).toInstant();
        });
        if (ids.isEmpty()) return new Batch(0, List.of(), null, null);
        // Each one still blocked by this incident and with no quarantine in force gets its row (none twice: the
        // unique index on rows in force, and a release since the confirmation frees the document).
        List<Placed> placed = jdbc.query("""
                insert into quarantined_documents(quarantine_id, tenant_id, incident_id, document_id, quarantined_by,
                    quarantined_by_display_name, quarantined_at)
                select gen_random_uuid(), d.tenant_id, :incidentId, d.document_id, :by, :byName, :at
                  from documents d
                 where d.tenant_id=:tenantId and d.document_id in (:ids) and %s
                   and not exists (select 1 from quarantined_documents q
                                    where q.tenant_id=d.tenant_id and q.document_id=d.document_id and q.released_at is null)
                   and not exists (select 1 from quarantined_documents r
                                    where r.tenant_id=d.tenant_id and r.document_id=d.document_id
                                      and r.released_at is not null and r.released_at >= :createdAt)
                 order by d.ingested_at, d.document_id
                on conflict (tenant_id, document_id) where released_at is null do nothing
                returning quarantine_id, document_id
                """.formatted(NOT_DELETED), new MapSqlParameterSource().addValue("tenantId", p.tenantId())
                .addValue("incidentId", p.incidentId()).addValue("ids", ids).addValue("by", by).addValue("byName", byName)
                .addValue("at", Timestamp.from(at)).addValue("createdAt", Timestamp.from(p.createdAt())),
                (rs, row) -> new Placed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)));
        return new Batch(ids.size(), placed, last[0], ids.getLast());
    }

    @Override
    public void advancePreparation(UUID tenantId, UUID incidentId, long preparedCount, Instant cursorReceivedAt,
                                   UUID cursorDocumentId, Instant preparedAt) {
        jdbc.update("""
                update quarantine_incidents
                   set prepared_count=:count, cursor_received_at=:cursorAt, cursor_document_id=:cursorId, prepared_at=:preparedAt
                 where tenant_id=:tenantId and incident_id=:id and filter_received_from is not null
                """, incident(tenantId, incidentId).addValue("count", preparedCount)
                .addValue("cursorAt", cursorReceivedAt == null ? null : Timestamp.from(cursorReceivedAt))
                .addValue("cursorId", cursorDocumentId)
                .addValue("preparedAt", preparedAt == null ? null : Timestamp.from(preparedAt)));
    }

    @Override
    public Optional<UUID> blockingIncident(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                select qi.incident_id from documents d, quarantine_incidents qi
                 where d.tenant_id=:tenantId and d.document_id=:documentId and %s
                 order by qi.created_at, qi.incident_id limit 1
                """.formatted(blockedBy("d")), document(tenantId, documentId), (rs, row) -> rs.getObject(1, UUID.class))
                .stream().findFirst();
    }

    @Override
    public void insertRequest(ReleaseRequest r) {
        jdbc.update("""
                insert into quarantine_release_requests(request_id, tenant_id, incident_id, reason, requested_by,
                    requested_by_display_name, requested_at, status, expires_at)
                values (:id, :tenantId, :incidentId, :reason, :by, :byName, :at, 'PENDING', :expires)
                """, new MapSqlParameterSource().addValue("id", r.requestId()).addValue("tenantId", r.tenantId())
                .addValue("incidentId", r.incidentId()).addValue("reason", r.reason()).addValue("by", r.requestedBy())
                .addValue("byName", r.requestedByName()).addValue("at", Timestamp.from(r.requestedAt()))
                .addValue("expires", Timestamp.from(r.expiresAt())));
    }

    private static final String REQUEST = """
            select request_id, tenant_id, incident_id, reason, requested_by, requested_by_display_name, requested_at,
                   status, decided_by, decided_by_display_name, decided_at, expires_at from quarantine_release_requests
            """;

    @Override
    public Optional<ReleaseRequest> lockRequest(UUID tenantId, UUID requestId) {
        return jdbc.query(REQUEST + " where tenant_id=:tenantId and request_id=:id for update",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", requestId),
                (rs, row) -> request(rs)).stream().findFirst();
    }

    @Override
    public Optional<ReleaseRequest> findPendingRequest(UUID tenantId, UUID incidentId) {
        return jdbc.query(REQUEST + " where tenant_id=:tenantId and incident_id=:id and status='PENDING'",
                incident(tenantId, incidentId), (rs, row) -> request(rs)).stream().findFirst();
    }

    @Override
    public List<ReleaseRequest> findPendingRequests(UUID tenantId, int limit) {
        return jdbc.query(REQUEST + " where tenant_id=:tenantId and status='PENDING' order by requested_at, request_id limit :limit",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("limit", limit), (rs, row) -> request(rs));
    }

    @Override
    public boolean decideRequest(UUID tenantId, UUID requestId, String status, String by, String byName, Instant at) {
        return jdbc.update("""
                update quarantine_release_requests
                   set status=:status, decided_by=:by, decided_by_display_name=:byName, decided_at=:at
                 where tenant_id=:tenantId and request_id=:id and status='PENDING'
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", requestId)
                .addValue("status", status).addValue("by", by).addValue("byName", byName)
                .addValue("at", Timestamp.from(at))) == 1;
    }

    @Override
    public List<ReleaseRequest> expireRequests(UUID tenantId, Instant now) {
        return jdbc.query("""
                update quarantine_release_requests set status='EXPIRED'
                 where tenant_id=:tenantId and status='PENDING' and expires_at <= :now
                returning request_id, tenant_id, incident_id, reason, requested_by, requested_by_display_name,
                          requested_at, status, decided_by, decided_by_display_name, decided_at, expires_at
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("now", Timestamp.from(now)),
                (rs, row) -> request(rs));
    }

    @Override
    public List<ReleaseRequest> expirePending(UUID tenantId, UUID incidentId) {
        return jdbc.query("""
                update quarantine_release_requests set status='EXPIRED'
                 where tenant_id=:tenantId and incident_id=:id and status='PENDING'
                returning request_id, tenant_id, incident_id, reason, requested_by, requested_by_display_name,
                          requested_at, status, decided_by, decided_by_display_name, decided_at, expires_at
                """, incident(tenantId, incidentId), (rs, row) -> request(rs));
    }

    @Override
    public List<Exposure> findExposureInIncident(UUID tenantId, UUID incidentId) {
        // One query for the whole incident: each document's accesses before its first quarantine in the incident.
        return jdbc.query("""
                with q as (select document_id, min(quarantined_at) as quarantined_at from quarantined_documents
                            where tenant_id=:tenantId and incident_id=:id group by document_id)
                select a.target_id, a.actor_id, a.actor_display_name, a.action, a.occurred_at
                  from q join audit_events a on a.tenant_id=:tenantId and a.target_type='DOCUMENT'
                       and a.target_id=q.document_id::text
                 where a.action in ('DOCUMENT_VIEW_OPENED', 'DOCUMENT_PRINTED', 'DOCUMENT_DOWNLOADED')
                   and a.outcome='SUCCESS' and a.occurred_at < q.quarantined_at
                 order by a.target_id, a.occurred_at, a.audit_event_id
                """, incident(tenantId, incidentId),
                (rs, row) -> new Exposure(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getTimestamp(5).toInstant()));
    }

    // ------------------------------------------------------------------ mapping

    private static MapSqlParameterSource document(UUID tenantId, UUID documentId) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("documentId", documentId);
    }

    private static MapSqlParameterSource incident(UUID tenantId, UUID incidentId) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", incidentId);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static String json(Map<String, String> fields) {
        try {
            return JSON.writeValueAsString(fields == null ? Map.of() : fields);
        } catch (JsonProcessingException ex) {
            throw new InternalCheckFailed("The filter of a quarantine incident could not be written.");
        }
    }

    static DocumentFacts facts(ResultSet rs, String prefix) throws SQLException {
        String level = rs.getString("sensitivity_level");
        int pages = rs.getInt("page_count");
        Integer pageCount = rs.wasNull() ? null : pages;
        return new DocumentFacts(rs.getObject("document_id", UUID.class), rs.getObject("source_id", UUID.class),
                rs.getObject("submission_id", UUID.class), rs.getString("status"),
                level == null ? null : SensitivityLevel.valueOf(level), instant(rs, "ingested_at"),
                pageCount, rs.getString("primary_identifier"), rs.getString("primary_identifier_label"));
    }

    private static Incident incident(ResultSet rs) throws SQLException {
        return new Incident(rs.getObject("incident_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("name"), rs.getString("reason"), rs.getString("scope"), rs.getString("created_by"),
                rs.getString("created_by_display_name"), instant(rs, "created_at"), rs.getString("status"),
                instant(rs, "closed_at"));
    }

    private static Preparation preparation(ResultSet rs) throws SQLException {
        Map<String, String> fields;
        try {
            fields = JSON.readValue(rs.getString("fields"), new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (JsonProcessingException ex) {
            throw new InternalCheckFailed("The filter of a quarantine incident could not be read.");
        }
        Filter filter = new Filter(instant(rs, "filter_received_from"), instant(rs, "filter_received_to"),
                rs.getObject("filter_source_id", UUID.class), fields);
        return new Preparation(rs.getObject("incident_id", UUID.class), rs.getObject("tenant_id", UUID.class), filter,
                instant(rs, "cutoff_received_at"), rs.getLong("expected_count"), rs.getLong("prepared_count"),
                instant(rs, "prepared_at"), instant(rs, "cursor_received_at"), rs.getObject("cursor_document_id", UUID.class),
                rs.getString("created_by"), rs.getString("created_by_display_name"), instant(rs, "created_at"),
                rs.getString("status"));
    }

    private static Quarantined quarantined(ResultSet rs) throws SQLException {
        return new Quarantined(rs.getObject("quarantine_id", UUID.class), rs.getObject("incident_id", UUID.class),
                facts(rs, ""), rs.getString("quarantined_by"), rs.getString("quarantined_by_display_name"),
                instant(rs, "quarantined_at"), rs.getString("released_by"), rs.getString("released_by_display_name"),
                instant(rs, "released_at"));
    }

    private static ReleaseRequest request(ResultSet rs) throws SQLException {
        return new ReleaseRequest(rs.getObject("request_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("incident_id", UUID.class), rs.getString("reason"), rs.getString("requested_by"),
                rs.getString("requested_by_display_name"), instant(rs, "requested_at"), rs.getString("status"),
                rs.getString("decided_by"), rs.getString("decided_by_display_name"), instant(rs, "decided_at"),
                instant(rs, "expires_at"));
    }
}
