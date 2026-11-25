package com.sdv.platform.application.restrictions;

import com.sdv.platform.persistence.fields.CaseRows;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.reports.StorageReportService;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.reports.JdbcIntakeReportRepository;
import com.sdv.platform.persistence.reports.JdbcStorageReportRepository;
import com.sdv.platform.persistence.restrictions.JdbcRestrictionsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0055: the Restrictions page against a real PostgreSQL database (changeset 042). One entity with
 * {@link #N} documents (250,000; SDV_IT_REPORT_SCALE sets another size), 20,000 records with a record level, 30,000
 * level changes in the last 30 days (and older ones), legal holds, a waiting lowering request and open quarantine
 * incidents. Each section counts what it should; records are named by their primary identifier, never a document ID,
 * file name or reason; the lists page; the overview and each page answer within {@link #SCREEN_MS}.
 * <p>
 * Runs only when SDV_IT_JDBC_URL is set to the scratch database sdv_schema_test; never against the sdv database.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class RestrictionsPostgresTest {
    private static final UUID T = UUID.fromString("10000000-0000-0000-0000-000000005501");
    private static final UUID S = UUID.fromString("20000000-0000-0000-0000-000000005501");
    private static final int N = Integer.parseInt(System.getenv().getOrDefault("SDV_IT_REPORT_SCALE", "250000"));
    private static final int RECORDS = 20_000;
    private static final int CHANGES = 30_000;
    static final long SCREEN_MS = 1000;

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final StorageReportService storage = new StorageReportService(new JdbcStorageReportRepository(jdbc),
            new JdbcIntakeReportRepository(jdbc), new JdbcFoundationRepository(jdbc), "UTC", 900_000L);
    private final RestrictionsService service = new RestrictionsService(new JdbcRestrictionsRepository(jdbc), storage);

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private long ms;

    private <R> R timed(java.util.function.Supplier<R> call) {
        call.get();
        long[] t = new long[3];
        R r = null;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            r = call.get();
            t[i] = (System.nanoTime() - start) / 1_000_000;
        }
        java.util.Arrays.sort(t);
        ms = t[1];
        return r;
    }

    @Test
    void everySectionCountsAndNothingRestrictedLeaksAndItIsQuick() throws Exception {
        sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + T + "', 'restrictions-0055', 'Restrictions 0055', 'ACTIVE')");
        sql("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values ('" + S + "', '" + T
                + "', 'loans', 'Loans', 'ACTIVE', 'JWT_BEARER')");
        CaseRows.field(jdbc.getJdbcTemplate(), T, S, "contractNumber", "Contract number", true, "DIGITS_ONLY", 0);
        // Every record has a submission; the documents are on the first ones.
        int submissions = Math.max((N + 49) / 50, Math.max(RECORDS, CHANGES + 1000));
        String subs = "(select n, md5('5501-' || n)::uuid as submission_id from generate_series(1, " + submissions + ") n)";
        sql("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) "
                + "select submission_id, '" + T + "', '" + S + "', 'sub-' || n, 50, 'COMPLETE' from " + subs + " s");
        CaseRows.values(jdbc.getJdbcTemplate(), T, S, subs, "contractNumber", "'5500' || lpad(n::text, 8, '0')");
        sql("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, "
                + "content_sha256, content_size_bytes, status, available_at, ingested_at, page_count, retention_days_snapshot, expires_at, "
                + "sensitivity_level) select md5('5501-doc-' || g)::uuid, '" + T + "', s.submission_id, '" + S + "', 'doc-' || g, 'secret-' || g || '.pdf', "
                + "'application/pdf', repeat('a', 64), 1000, 'AVAILABLE', now(), now() - interval '2 days', 2, 2555, now() + interval '2000 days', "
                + "case when g % 10 = 0 then 'HIGHLY_CONFIDENTIAL' when g % 3 = 0 then 'CONFIDENTIAL' else 'STANDARD' end "
                + "from generate_series(1, " + N + ") g join " + subs + " s on s.n = (g - 1) / 50 + 1");
        // 20,000 records with a record level (by contract number), set over the last 400 days.
        String record = "md5('5501-' || n)::uuid";
        sql("insert into record_sensitivity_levels(record_level_id, tenant_id, source_id, record_id, sensitivity_level, "
                + "reason, set_by, set_at, updated_at) select gen_random_uuid(), '" + T + "', '" + S + "', " + record + ", case when n % 4 = 0 then 'HIGHLY_CONFIDENTIAL' else 'CONFIDENTIAL' end, 'Secret reason', 'ada', "
                + "now() - (n % 400) * interval '1 hour', now() from generate_series(1, " + RECORDS + ") n");
        // 30,000 level changes of records in the last 30 days, and 1,000 older; 100 changes of documents.
        sql("insert into sensitivity_level_changes(change_id, tenant_id, scope, source_id, record_id, direction, "
                + "from_level, to_level, reason, changed_by, changed_by_display_name, changed_at) select gen_random_uuid(), '" + T + "', 'RECORD', '"
                + S + "', " + record + ", 'RAISED', 'STANDARD', 'CONFIDENTIAL', 'Secret reason', "
                + "'dee', 'Dee Steward', now() - case when n > " + CHANGES + " then interval '40 days' else (n % 2500) * interval '1000 seconds' end "
                + "from generate_series(1, " + (CHANGES + 1000) + ") n");
        sql("insert into sensitivity_level_changes(change_id, tenant_id, scope, document_id, direction, from_level, to_level, reason, "
                + "changed_by, changed_at) select gen_random_uuid(), '" + T + "', 'DOCUMENT', md5('5501-doc-' || n)::uuid, 'RAISED', 'STANDARD', "
                + "'CONFIDENTIAL', 'Secret reason', 'dee', now() - interval '1 hour' from generate_series(1, 100) n");
        // Holds on 500 documents, one waiting lowering, two open quarantine incidents.
        sql("insert into legal_holds(hold_id, tenant_id, document_id, reason, placed_by, placed_at) select gen_random_uuid(), '" + T
                + "', md5('5501-doc-' || n)::uuid, 'Court order', 'ada', now() from generate_series(1, 500) n");
        sql("insert into access_requests(request_id, tenant_id, kind, document_id, requested_level, reason, requested_by, requested_at, status, "
                + "expires_at) values (gen_random_uuid(), '" + T + "', 'LOWER_LEVEL', md5('5501-doc-3')::uuid, 'STANDARD', 'Wrong level', 'dee', "
                + "now() - interval '2 days', 'PENDING', now() + interval '5 days')");
        sql("insert into quarantine_incidents(incident_id, tenant_id, name, reason, scope, created_by, created_at, status) values "
                + "(gen_random_uuid(), '" + T + "', 'One', 'Suspected leak', 'DOCUMENT', 'stan', now(), 'OPEN')");
        sql("analyze");

        // The levels come from the Storage and retention report's counts, kept in the background.
        storage.count(T);
        RestrictionsService.Overview o = timed(() -> service.overview(T));
        long overviewMs = ms;
        long highly = N / 10;
        assertTrue(o.highlyConfidential() > highly, "the documents' own, and those raised by their record's level");
        assertTrue(o.confidential() + o.highlyConfidential() > 0);
        assertEquals(RECORDS, o.restrictedRecords());
        assertEquals(CHANGES + 100, o.levelChanges(), "the last 30 days only");
        assertEquals(new RestrictionsService.Lowerings(1, o.lowerings().oldest()), o.lowerings());
        assertEquals(new RestrictionsService.LegalHolds(500, 500), o.legalHolds());
        assertEquals(1, o.quarantine().incidents());
        // Records by their primary identifier only; a document's change names its record.
        assertEquals("Contract number", o.records().items().getFirst().identifierLabel());
        assertTrue(o.records().items().getFirst().identifier().startsWith("5500"));
        assertTrue(o.changes().items().stream().anyMatch(c -> c.scope().equals("DOCUMENT") && c.identifier().startsWith("5500")));
        String json = com.sdv.platform.application.json.VeroJson.mapper().writeValueAsString(o);
        for (String never : List.of("Secret reason", "secret-", ".pdf", "documentId", "5501-doc")) assertFalse(json.contains(never), never);
        // Pages: every restricted record once, 50 at a time; a deep page of changes.
        List<String> seen = new ArrayList<>();
        RestrictionsService.RecordPage page = o.records();
        int pages = 0;
        long deepMs = 0;
        while (true) {
            page.items().forEach(r -> seen.add(r.identifier()));
            if (page.next() == null) break;
            pages++;
            String after = page.next();
            if (pages == 300) {
                page = timed(() -> service.records(T, after));
                deepMs = ms;
            } else {
                page = service.records(T, after);
            }
        }
        assertEquals(RECORDS, seen.size());
        assertEquals(RECORDS, seen.stream().distinct().count(), "every record once");
        RestrictionsService.ChangePage changes = o.changes();
        for (int i = 0; i < 200 && changes.next() != null; i++) changes = service.changes(T, changes.next());
        String afterChanges = changes.next();
        timed(() -> service.changes(T, afterChanges));
        long changesMs = ms;
        assertTrue(overviewMs <= SCREEN_MS, "the overview took " + overviewMs + " ms (agreed: " + SCREEN_MS + " ms)");
        assertTrue(deepMs <= SCREEN_MS, "a deep page of records took " + deepMs + " ms");
        assertTrue(changesMs <= SCREEN_MS, "a deep page of level changes took " + changesMs + " ms");

        // Nothing left on hold or waiting for the other PostgreSQL tests (same database).
        sql("update legal_holds set released_by='ada', released_at=now() where tenant_id='" + T + "' and released_at is null");
        sql("update access_requests set status='EXPIRED' where tenant_id='" + T + "' and status='PENDING'");
        sql("update quarantine_incidents set status='RELEASED', closed_at=now() where tenant_id='" + T + "' and status='OPEN'");

        List<String> lines = new ArrayList<>();
        lines.add("Restrictions page (task 0055): " + N + " documents, " + RECORDS + " records with a record level, " + (CHANGES + 1100)
                + " level changes. APIs: median of 3 after one warm-up.");
        lines.add(String.format("%-48s %,10d", "overview (API)", overviewMs));
        lines.add(String.format("%-48s %,10d", "records, page 301 (API)", deepMs));
        lines.add(String.format("%-48s %,10d", "level changes, page 202 (API)", changesMs));
        lines.forEach(line -> System.out.println("RESTRICTIONS " + line));
        Files.write(Path.of("target", "restrictions-times.txt"), lines, StandardCharsets.UTF_8);
    }
}
