package com.sdv.platform.persistence.reports;

import com.sdv.platform.application.port.IntakeReportRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

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
 * Task 0032: the Daily intake report's reads. Only identifiers, times, checksums, page counts, statuses and codes are
 * selected: never a file name, title, other metadata or content. Every query is limited to one tenant.
 * <p>
 * Indexes (changeset 031 and earlier): submissions (tenant_id, created_at), documents (tenant_id, ingested_at),
 * document_duplicate_receipts (tenant_id, received_at, source_id), audit_events (tenant_id, action, occurred_at),
 * business_metadata_values (tenant_id, field_key, normalized_value).
 */
@Repository
public class JdbcIntakeReportRepository implements IntakeReportRepository {
    private static final int CHUNK = 1000;
    /** The primary identifier of submission s: its first non-blank business identifier, in display order. */
    public static final String PRIMARY_IDENTIFIER = """
            left join lateral (
                select v.field_key, v.stored_value, v.normalized_value, f.display_label
                  from business_metadata_values v
                  join metadata_field_definitions f on f.definition_id=v.definition_id and f.business_identifier
                  left join metadata_field_display_order o
                    on o.tenant_id=v.tenant_id and o.source_id=f.source_id and o.field_key=v.field_key
                 where v.tenant_id=s.tenant_id and v.submission_id=s.submission_id and btrim(v.stored_value)<>''
                 order by o.display_position nulls last, v.field_key
                 limit 1) pid on true
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcIntakeReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> findTenantTimeZone(UUID tenantId) {
        return jdbc.query("select time_zone from tenants where tenant_id=:tenantId",
                        new MapSqlParameterSource("tenantId", tenantId), (rs, row) -> rs.getString("time_zone"))
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    @Override
    public List<SendingSystem> findSendingSystems(UUID tenantId) {
        return jdbc.query("""
                select source_id, display_name from ingestion_sources
                 where tenant_id=:tenantId
                 order by lower(display_name), source_id
                """, new MapSqlParameterSource("tenantId", tenantId),
                (rs, row) -> new SendingSystem(rs.getObject("source_id", UUID.class), rs.getString("display_name")));
    }

    @Override
    public List<Item> findItems(UUID tenantId, UUID sourceId, Instant from, Instant to, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to))
                .addValue("limit", limit);
        String submissionSource = "";
        String documentSource = "";
        String duplicateSource = "";
        String refusalSource = "";
        if (sourceId != null) {
            p.addValue("sourceId", sourceId).addValue("sourceText", sourceId.toString());
            submissionSource = " and s.source_id=:sourceId";
            documentSource = " and d.source_id=:sourceId";
            duplicateSource = " and r.source_id=:sourceId";
            refusalSource = " and (a.safe_details ->> 'sourceId')=:sourceText";
        }
        // A refusal names the submission only when it is this sending system's own (a wrong ID is refused before any
        // submission is found); otherwise it is a delivery refused before a submission existed.
        String sql = """
                select kind, item_id, source_id, submission_id, request_id, content_sha256, pages, at, document_status,
                       refusal_code
                  from (
                    select 'DELIVERY' as kind, s.submission_id as item_id, s.source_id, s.submission_id,
                           s.source_submission_id as request_id, cast(null as text) as content_sha256,
                           cast(null as integer) as pages, s.created_at as at, cast(null as text) as document_status,
                           cast(null as text) as refusal_code
                      from submissions s
                     where s.tenant_id=:tenantId and s.created_at >= :from and s.created_at < :to""" + submissionSource + """

                    union all
                    select 'DOCUMENT', d.document_id, d.source_id, d.submission_id, s.source_submission_id,
                           d.content_sha256, d.page_count, d.ingested_at, d.status, null
                      from documents d
                      join submissions s on s.tenant_id=d.tenant_id and s.submission_id=d.submission_id
                     where d.tenant_id=:tenantId and d.ingested_at >= :from and d.ingested_at < :to""" + documentSource + """

                    union all
                    select 'DUPLICATE', r.duplicate_id, r.source_id, r.submission_id, s.source_submission_id,
                           r.content_sha256, d.page_count, r.received_at, null, null
                      from document_duplicate_receipts r
                      join submissions s on s.tenant_id=r.tenant_id and s.submission_id=r.submission_id
                      join documents d on d.tenant_id=r.tenant_id and d.document_id=r.existing_document_id
                     where r.tenant_id=:tenantId and r.received_at >= :from and r.received_at < :to""" + duplicateSource + """

                    union all
                    select 'REFUSED', a.audit_event_id, cast(a.safe_details ->> 'sourceId' as uuid), s.submission_id,
                           coalesce(s.source_submission_id, a.safe_details ->> 'requestId'), null, null, a.occurred_at,
                           null, a.safe_details ->> 'code'
                      from audit_events a
                      left join submissions s
                        on s.tenant_id=a.tenant_id
                       and s.submission_id=cast(nullif(a.safe_details ->> 'submissionId', '') as uuid)
                       and s.source_id=cast(a.safe_details ->> 'sourceId' as uuid)
                     where a.tenant_id=:tenantId and a.action='INTAKE_REFUSED'
                       and a.occurred_at >= :from and a.occurred_at < :to""" + refusalSource + """

                  ) items
                 order by at, kind, item_id
                 limit :limit
                """;
        return jdbc.query(sql, p, (rs, row) -> {
            Timestamp at = rs.getTimestamp("at");
            return new Item(Item.Kind.valueOf(rs.getString("kind")), rs.getObject("item_id", UUID.class),
                    rs.getObject("source_id", UUID.class), rs.getObject("submission_id", UUID.class),
                    rs.getString("request_id"), rs.getString("content_sha256"), rs.getObject("pages", Integer.class),
                    at.toInstant(), rs.getString("document_status"), rs.getString("refusal_code"));
        });
    }

    @Override
    public Map<UUID, PrimaryIdentifier> findPrimaryIdentifiers(UUID tenantId, Collection<UUID> submissionIds) {
        Map<UUID, PrimaryIdentifier> result = new LinkedHashMap<>();
        for (List<UUID> chunk : chunks(List.copyOf(submissionIds))) {
            jdbc.query("select s.submission_id, pid.field_key, pid.stored_value, pid.normalized_value, pid.display_label"
                            + " from submissions s " + PRIMARY_IDENTIFIER
                            + " where s.tenant_id=:tenantId and s.submission_id in (:ids) and pid.stored_value is not null",
                    new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("ids", chunk),
                    rs -> {
                        result.put(rs.getObject("submission_id", UUID.class), new PrimaryIdentifier(
                                rs.getString("field_key"), rs.getString("stored_value"),
                                rs.getString("normalized_value"), rs.getString("display_label")));
                    });
        }
        return result;
    }

    @Override
    public Set<String> findValuesKnownBefore(UUID tenantId, Collection<String> fieldKeys,
                                             Collection<String> normalizedValues, Instant before) {
        Set<String> known = new HashSet<>();
        if (fieldKeys.isEmpty() || normalizedValues.isEmpty()) return known;
        for (List<String> chunk : chunks(List.copyOf(normalizedValues))) {
            known.addAll(jdbc.queryForList("""
                    select distinct coalesce(nullif(btrim(v.normalized_value), ''), v.stored_value) as compared
                      from business_metadata_values v
                      join metadata_field_definitions f on f.definition_id=v.definition_id and f.business_identifier
                      join submissions s on s.tenant_id=v.tenant_id and s.submission_id=v.submission_id
                     where v.tenant_id=:tenantId and v.field_key in (:keys)
                       and (v.normalized_value in (:values)
                            or (coalesce(btrim(v.normalized_value), '')='' and v.stored_value in (:values)))
                       and s.created_at < :before
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("keys", List.copyOf(fieldKeys))
                    .addValue("values", chunk)
                    .addValue("before", Timestamp.from(before)), String.class));
        }
        return known;
    }

    private static <T> List<List<T>> chunks(List<T> all) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += CHUNK) out.add(all.subList(i, Math.min(all.size(), i + CHUNK)));
        return out;
    }
}
