package com.sdv.platform.persistence.audit;

import com.sdv.platform.application.port.AuditReportRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Task 0050: the sealed audit report register (changeset 037). Insert and read only: the table is append-only. */
@Repository
public class JdbcAuditReportRepository implements AuditReportRepository {
    private static final String COLUMNS = """
            report_id, tenant_id, generated_by, generated_by_display_name, generated_at, range_from, range_to,
            filters::text as filters, row_count, fingerprint
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAuditReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(StoredReport r) {
        jdbc.update("""
                insert into audit_reports(report_id, tenant_id, generated_by, generated_by_display_name, generated_at,
                                          range_from, range_to, filters, row_count, fingerprint, csv)
                values (:reportId, :tenantId, :generatedBy, :generatedByName, :generatedAt, :rangeFrom, :rangeTo,
                        cast(:filters as jsonb), :rowCount, :fingerprint, :csv)
                """, new MapSqlParameterSource()
                .addValue("reportId", r.reportId())
                .addValue("tenantId", r.tenantId())
                .addValue("generatedBy", r.generatedBy())
                .addValue("generatedByName", r.generatedByName())
                .addValue("generatedAt", Timestamp.from(r.generatedAt()))
                .addValue("rangeFrom", Timestamp.from(r.rangeFrom()))
                .addValue("rangeTo", Timestamp.from(r.rangeTo()))
                .addValue("filters", r.filtersJson())
                .addValue("rowCount", r.rowCount())
                .addValue("fingerprint", r.fingerprint())
                .addValue("csv", r.csv()));
    }

    @Override
    public Optional<StoredReport> find(UUID tenantId, UUID reportId) {
        return jdbc.query("select " + COLUMNS + ", csv from audit_reports where tenant_id = :tenantId and report_id = :reportId",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("reportId", reportId),
                (rs, n) -> map(rs, rs.getBytes("csv"))).stream().findFirst();
    }

    @Override
    public List<StoredReport> list(UUID tenantId, int limit) {
        return jdbc.query("select " + COLUMNS + " from audit_reports where tenant_id = :tenantId "
                        + "order by generated_at desc, report_id limit :limit",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("limit", limit),
                (rs, n) -> map(rs, null));
    }

    @Override
    public List<StoredReport> findByFingerprint(UUID tenantId, String fingerprint) {
        return jdbc.query("select " + COLUMNS + " from audit_reports where tenant_id = :tenantId and fingerprint = :fingerprint "
                        + "order by generated_at desc, report_id",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("fingerprint", fingerprint),
                (rs, n) -> map(rs, null));
    }

    private static StoredReport map(ResultSet rs, byte[] csv) throws SQLException {
        return new StoredReport(rs.getObject("report_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("generated_by"), rs.getString("generated_by_display_name"), instant(rs, "generated_at"),
                instant(rs, "range_from"), instant(rs, "range_to"), rs.getString("filters"), rs.getInt("row_count"),
                rs.getString("fingerprint"), csv);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
