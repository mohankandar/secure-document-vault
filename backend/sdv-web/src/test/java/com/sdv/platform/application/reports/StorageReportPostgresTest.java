package com.sdv.platform.application.reports;

import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.reports.JdbcIntakeReportRepository;
import com.sdv.platform.persistence.reports.JdbcStorageReportRepository;
import com.sdv.platform.domain.document.SensitivityLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0053: the Storage and retention report against a real PostgreSQL database (changeset 040).
 * <ol>
 *   <li>Exact counts on a small entity: storage by sending system and effective level (a record level raises its
 *       documents), totals, growth by month, due per retention rule (keep forever shows none), held back by a legal
 *       hold, a quarantine row, a filter's block, or both; nothing of another entity; the CSV holds no file name.</li>
 *   <li>At scale: {@link #N} documents (250,000; SDV_IT_REPORT_SCALE sets another size for a local run). The count
 *       runs in the background; the screen's API reads the kept counts and answers within {@link #SCREEN_MS}.</li>
 * </ol>
 * Runs only when SDV_IT_JDBC_URL is set to the scratch database sdv_schema_test; never against the sdv database.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class StorageReportPostgresTest {
    private static final UUID T = UUID.fromString("10000000-0000-0000-0000-000000005301");
    private static final UUID TX = UUID.fromString("10000000-0000-0000-0000-000000005302");
    private static final UUID TS = UUID.fromString("10000000-0000-0000-0000-000000005303");
    private static final UUID S1 = UUID.fromString("20000000-0000-0000-0000-000000005301");
    private static final UUID S2 = UUID.fromString("20000000-0000-0000-0000-000000005302");
    private static final UUID SX = UUID.fromString("20000000-0000-0000-0000-000000005303");
    private static final UUID SS = UUID.fromString("20000000-0000-0000-0000-000000005304");
    private static final int N = Integer.parseInt(System.getenv().getOrDefault("SDV_IT_REPORT_SCALE", "250000"));
    /** The agreed time for a screen's API (task 0053, as task 0052). */
    static final long SCREEN_MS = 1000;

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final JdbcStorageReportRepository repository = new JdbcStorageReportRepository(jdbc);
    private final StorageReportService service = new StorageReportService(repository, new JdbcIntakeReportRepository(jdbc),
            new JdbcFoundationRepository(jdbc), "UTC", Clock.systemUTC(), 15);

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("storage-0053-" + name).getBytes(StandardCharsets.UTF_8));
    }

    private void tenant(UUID tenant, String key, UUID... sources) {
        sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + tenant + "', '" + key + "', '" + key + "', 'ACTIVE')");
        int i = 0;
        for (UUID source : sources) {
            i++;
            sql("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values ('"
                    + source + "', '" + tenant + "', 'sys" + i + "', 'System " + key + " " + i + "', 'ACTIVE', 'JWT_BEARER')");
        }
    }

    private void submission(UUID tenant, UUID source, UUID submission) {
        sql("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) values ('"
                + submission + "', '" + tenant + "', '" + source + "', 'sub-" + submission + "', 10, 'COMPLETE')");
    }

    /** A document: level, status, pages, bytes, received (an SQL time), retention days (null: forever) and expiry. */
    private UUID document(UUID tenant, UUID source, UUID submission, String name, String level, String status, int pages,
                          long bytes, String received, Integer days, String expires) {
        UUID id = id(name);
        sql("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, "
                + "content_sha256, content_size_bytes, status, available_at, ingested_at, page_count, retention_days_snapshot, expires_at, "
                + "sensitivity_level) values ('" + id + "', '" + tenant + "', '" + submission + "', '" + source + "', '" + name
                + "', 'secret-" + name + ".pdf', 'application/pdf', repeat('a', 64), " + bytes + ", '" + status + "', now(), " + received
                + ", " + pages + ", " + (days == null ? "null" : days) + ", " + (expires == null ? "null" : expires) + ", '" + level + "')");
        return id;
    }

    @Test
    void theCountsAreExactAndTheScreenReadsThemQuicklyAtScale() throws Exception {
        // ------------------------------------------------------------ 1. exact counts
        tenant(T, "storage-0053", S1, S2);
        tenant(TX, "storage-0053-other", SX);
        UUID sub1 = id("sub1"), sub2 = id("sub2"), subX = id("subx");
        submission(T, S1, sub1);
        submission(T, S2, sub2);
        submission(TX, SX, subX);
        String lastMonth = "date_trunc('month', now()) - interval '10 days'";
        String later = "now() + interval '400 days'";
        document(T, S1, sub1, "s1-a", "STANDARD", "AVAILABLE", 3, 100, "now() - interval '1 hour'", 30, "now() + interval '10 days'");
        document(T, S1, sub1, "s1-b", "STANDARD", "AVAILABLE", 2, 200, lastMonth, 365, "now() + interval '45 days'");
        document(T, S1, sub1, "s1-c", "HIGHLY_CONFIDENTIAL", "AVAILABLE", 5, 500, "now() - interval '1 hour'", 365, "now() + interval '80 days'");
        document(T, S2, sub2, "s2-a", "STANDARD", "AVAILABLE", 1, 50, "now() - interval '1 hour'", null, null);
        document(T, S2, sub2, "s2-failed", "STANDARD", "PROCESSING_FAILED", 1, 999, "now() - interval '1 hour'", 30, later);
        UUID deleted = document(T, S2, sub2, "s2-deleted", "STANDARD", "AVAILABLE", 1, 999, "now() - interval '2 hours'", 30, "now() + interval '400 days'");
        sql("update documents set status='EXPIRED_DELETED', deleted_at=now(), deletion_reason='RETENTION_EXPIRED' where document_id='" + deleted + "'");
        // Past retention: kept by a hold, by a quarantine row, by a filter's block, by both; one not kept at all.
        UUID held = document(T, S1, sub1, "held", "STANDARD", "AVAILABLE", 1, 10, "now() - interval '1 hour'", 30, "now() - interval '1 day'");
        UUID quarantined = document(T, S1, sub1, "quarantined", "STANDARD", "AVAILABLE", 1, 10, "now() - interval '1 hour'", 30, "now() - interval '1 day'");
        UUID both = document(T, S1, sub1, "both", "STANDARD", "AVAILABLE", 1, 10, "now() - interval '1 hour'", 30, "now() - interval '1 day'");
        document(T, S1, sub1, "free", "STANDARD", "AVAILABLE", 1, 10, "now() - interval '1 hour'", 30, "now() - interval '1 day'");
        // The other entity is never counted.
        document(TX, SX, subX, "other", "STANDARD", "AVAILABLE", 9, 9999, "now() - interval '1 hour'", 30, "now() + interval '5 days'");
        // S2's submission is a record at Confidential: its stored document counts at Confidential.
        String key = "submission:" + sub2;
        sql("insert into record_sensitivity_levels(record_level_id, tenant_id, source_id, record_key, record_key_sha256, sensitivity_level, "
                + "set_by, set_at, updated_at) values ('" + id("level") + "', '" + T + "', '" + S2 + "', '" + key + "', '"
                + com.sdv.platform.application.business.RecordKey.sha256(key) + "', 'CONFIDENTIAL', 'SOURCE', now(), now())");
        for (UUID d : List.of(held, both)) {
            sql("insert into legal_holds(hold_id, tenant_id, document_id, reason, placed_by, placed_at) values (gen_random_uuid(), '"
                    + T + "', '" + d + "', 'Court order', 'ada', now())");
        }
        UUID incident = id("incident");
        sql("insert into quarantine_incidents(incident_id, tenant_id, name, reason, scope, created_by, created_at, status) values ('"
                + incident + "', '" + T + "', 'Leak', 'Suspected leak', 'DOCUMENT', 'stan', now(), 'OPEN')");
        for (UUID d : List.of(quarantined, both)) {
            sql("insert into quarantined_documents(quarantine_id, tenant_id, incident_id, document_id, quarantined_by, quarantined_at, level, received_at) "
                    + "values (gen_random_uuid(), '" + T + "', '" + incident + "', '" + d + "', 'stan', now(), 'STANDARD', now() - interval '1 hour')");
        }
        sql("insert into retention_policy_versions(policy_version_id, tenant_id, version, policy_name, retention_days, effective_from, effective_until, created_by, created_at) values "
                + "(gen_random_uuid(), '" + T + "', 1, 'Thirty days', 30, now() - interval '2 years', now() - interval '1 year', 'ada', now() - interval '2 years'), "
                + "(gen_random_uuid(), '" + T + "', 2, 'One year', 365, now() - interval '1 year', null, 'ada', now() - interval '1 year')");

        StorageReportService.StorageReport r = service.report(T);
        assertNotNull(r.countedAt(), "counted the first time it was opened");
        // Storage now: 4 + held/quarantined/both/free (all stored, AVAILABLE) = 8 stored; failed and deleted are not.
        assertEquals(8, r.total().documents());
        assertEquals(3 + 2 + 5 + 1 + 4, r.total().pages());
        assertEquals(100 + 200 + 500 + 50 + 40, r.total().bytes());
        assertEquals(6, r.totals().stream().filter(t -> t.level() == SensitivityLevel.STANDARD).findFirst().orElseThrow().documents());
        assertEquals(1, r.totals().stream().filter(t -> t.level() == SensitivityLevel.CONFIDENTIAL).findFirst().orElseThrow().documents(),
                "the record level raises S2's document");
        assertEquals(1, r.totals().stream().filter(t -> t.level() == SensitivityLevel.HIGHLY_CONFIDENTIAL).findFirst().orElseThrow().documents());
        assertTrue(r.stored().stream().anyMatch(s -> s.sourceId().equals(S2) && s.level() == SensitivityLevel.CONFIDENTIAL && s.bytes() == 50));
        // Growth: 12 months; this month holds everything received this month (the deleted one too, not the failed one).
        assertEquals(StorageReportService.MONTHS, r.growth().size());
        String thisMonth = YearMonth.now(ZoneOffset.UTC).toString();
        assertEquals(thisMonth, r.growth().getLast().month());
        assertEquals(8, r.growth().getLast().documents(), "received this month: the deleted one counts, the failed one doesn't");
        assertEquals(1, r.growth().get(StorageReportService.MONTHS - 2).documents(), "s1-b, last month");
        // Due: per rule, keep forever shows none.
        StorageReportService.RuleRow thirty = r.retention().stream().filter(x -> Integer.valueOf(30).equals(x.retentionDays())).findFirst().orElseThrow();
        assertEquals("Thirty days", thirty.ruleName());
        assertEquals(1, thirty.within30());
        StorageReportService.RuleRow year = r.retention().stream().filter(x -> Integer.valueOf(365).equals(x.retentionDays())).findFirst().orElseThrow();
        assertEquals("One year", year.ruleName());
        assertEquals(0, year.within30());
        assertEquals(1, year.within60());
        assertEquals(2, year.within90());
        StorageReportService.RuleRow forever = r.retention().stream().filter(StorageReportService.RuleRow::keptForever).findFirst().orElseThrow();
        assertNull(forever.within30());
        assertEquals(1, forever.stored());
        // Held back: one by a hold, one by a quarantine row, one by both; the free one isn't counted.
        assertEquals(1, r.heldBack().legalHold());
        assertEquals(1, r.heldBack().quarantine());
        assertEquals(1, r.heldBack().both());
        // A filter's block counts as a quarantine: the free one is caught by an open filter incident.
        UUID filterIncident = id("filter");
        sql("insert into quarantine_incidents(incident_id, tenant_id, name, reason, scope, created_by, created_at, status, filter_received_from, "
                + "filter_received_to, filter_source_id, filter_fields, cutoff_received_at, expected_count) values ('" + filterIncident + "', '" + T
                + "', 'Filter', 'Suspected leak', 'FILTER', 'rita', now(), 'OPEN', now() - interval '3 hours', now() + interval '1 hour', '" + S1
                + "', '{}', now(), 1)");
        service.count(T);
        assertEquals(2, service.report(T).heldBack().quarantine(), "the filter's block keeps the free one too");
        // The CSV: counts only, its reference and fingerprint, audited.
        StorageReportService.Export export = service.export(T, "ada", "corr");
        String csv = new String(export.csv(), StandardCharsets.UTF_8);
        assertFalse(csv.contains("secret-"), "no file names");
        assertTrue(csv.replace("\"", "").contains("Held back from deletion,Legal hold and quarantine,All levels,Documents,2,"),
                "the hold that the filter now blocks too counts as both");
        assertEquals(64, export.fingerprint().length());
        assertEquals(1L, jdbc.queryForObject("select count(*) from audit_events where tenant_id=:t and action='STORAGE_REPORT_EXPORTED' "
                + "and safe_details->>'fingerprint'=:f", new MapSqlParameterSource("t", T).addValue("f", export.fingerprint()), Long.class));
        // Nothing past its retention is left for the other PostgreSQL tests' cleanup (same database).
        sql("update documents set expires_at = now() + interval '400 days' where tenant_id='" + T + "' and expires_at <= now()");
        sql("update quarantine_incidents set status='RELEASED', closed_at=now() where tenant_id='" + T + "'");

        // ------------------------------------------------------------ 2. at scale
        tenant(TS, "storage-0053-scale", SS);
        int submissions = (N + 49) / 50;
        String subs = "(select n, md5('5303-' || n)::uuid as submission_id from generate_series(1, " + submissions + ") n)";
        long seedStart = System.nanoTime();
        sql("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) "
                + "select submission_id, '" + TS + "', '" + SS + "', 'sub-' || n, 50, 'COMPLETE' from " + subs + " s");
        sql("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, "
                + "content_sha256, content_size_bytes, status, available_at, ingested_at, page_count, retention_days_snapshot, expires_at, "
                + "sensitivity_level) select gen_random_uuid(), '" + TS + "', s.submission_id, '" + SS + "', 'doc-' || g, 'secret-' || g || '.pdf', "
                + "'application/pdf', repeat('a', 64), 1000 + g % 100, 'AVAILABLE', now(), now() - (g % 360) * interval '1 day', 1 + g % 7, "
                + "case when g % 5 = 0 then null else 2555 end, case when g % 5 = 0 then null else now() + (g % 120) * interval '1 day' end, "
                + "case when g % 10 = 0 then 'HIGHLY_CONFIDENTIAL' when g % 3 = 0 then 'CONFIDENTIAL' else 'STANDARD' end "
                + "from generate_series(1, " + N + ") g join " + subs + " s on s.n = (g - 1) / 50 + 1");
        sql("analyze documents");
        long seedMs = (System.nanoTime() - seedStart) / 1_000_000;

        long countStart = System.nanoTime();
        service.count(TS);
        long countMs = (System.nanoTime() - countStart) / 1_000_000;
        service.report(TS); // warm-up
        long[] ms = new long[3];
        StorageReportService.StorageReport scale = null;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            scale = service.report(TS);
            ms[i] = (System.nanoTime() - start) / 1_000_000;
        }
        java.util.Arrays.sort(ms);
        assertEquals(N, scale.total().documents());
        assertTrue(ms[1] <= SCREEN_MS, "the report took " + ms[1] + " ms at " + N + " documents (agreed: " + SCREEN_MS + " ms)");
        long exportStart = System.nanoTime();
        service.export(TS, "ada", "corr");
        long exportMs = (System.nanoTime() - exportStart) / 1_000_000;
        assertTrue(exportMs <= SCREEN_MS, "the export took " + exportMs + " ms (agreed: " + SCREEN_MS + " ms)");

        List<String> lines = new ArrayList<>();
        lines.add("Storage and retention report (task 0053): " + N + " documents; the screen's API is the median of 3 after one warm-up.");
        lines.add(String.format("%-48s %,10d", "seed (test set-up)", seedMs));
        lines.add(String.format("%-48s %,10d", "background count (reads the documents)", countMs));
        lines.add(String.format("%-48s %,10d", "report (API, from the kept counts)", ms[1]));
        lines.add(String.format("%-48s %,10d", "CSV export (API)", exportMs));
        lines.forEach(line -> System.out.println("STORAGE-REPORT " + line));
        Files.write(Path.of("target", "storage-report-times.txt"), lines, StandardCharsets.UTF_8);
    }
}
