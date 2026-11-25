package com.sdv.platform.application.quarantine;

import com.sdv.platform.persistence.fields.CaseRows;
import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.approvals.ApprovalSettings;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.persistence.business.JdbcBusinessRecordRepository;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.ingestion.JdbcSecureIngestionRepository;
import com.sdv.platform.persistence.quarantine.JdbcQuarantineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0052: quarantine at scale, against a real PostgreSQL database (changesets 036, 038 and 039). One filter
 * incident of {@link #N} documents (250,000 in CI and the schema check; SDV_IT_QUARANTINE_SCALE sets another size for
 * a one-off local run, such as 1,000,000):
 * <ul>
 *   <li>blocking is immediate on every path when the confirmation returns, before the rows exist;</li>
 *   <li>the background preparation and the background whole release both finish, in batches, nothing twice;</li>
 *   <li>each screen's API answers within the agreed time ({@link #SCREEN_MS}, {@link #CHECK_MS}), whatever the size:
 *       the incident list, the incident page (first and deep), the exposure page, the Approvals row, the count;</li>
 *   <li>the exposure export streams every document, with its fingerprint.</li>
 * </ul>
 * The times are printed and written to target/quarantine-scale-times.txt for the report. No limit is raised to pass.
 * <p>
 * Runs only when SDV_IT_JDBC_URL is set to the scratch database sdv_schema_test; never against the sdv database.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class QuarantineScalePostgresTest {
    private static final UUID T = UUID.fromString("10000000-0000-0000-0000-000000005201");
    private static final UUID S = UUID.fromString("20000000-0000-0000-0000-000000005201");
    private static final int N = Integer.parseInt(System.getenv().getOrDefault("SDV_IT_QUARANTINE_SCALE", "250000"));
    /** Documents per submission (each submission is one record with its own contract number). */
    private static final int PER_SUBMISSION = 50;
    /** The agreed times (task 0052, set before the first measured run): a screen's API, and a one-document check. */
    static final long SCREEN_MS = 1000;
    static final long CHECK_MS = 100;
    private static final Clearance RESTRICTED = new Clearance(true, true, List.of("RESTRICTED_STEWARD"), true);
    private static final Clearance STEWARD = new Clearance(true, false, List.of("DOCUMENT_STEWARD"), true);
    private static final Clearance ADMIN = new Clearance(false, false, List.of("BUSINESS_ADMIN"), false);
    private static final Clearance BUSINESS_USER = new Clearance(false, false);

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
    private final JdbcFoundationRepository foundation = new JdbcFoundationRepository(jdbc);
    private final JdbcQuarantineRepository repository = new JdbcQuarantineRepository(jdbc);
    private final DocumentAccessGuard guard = new DocumentAccessGuard(new JdbcBusinessRecordRepository(jdbc), foundation,
            com.sdv.platform.application.access.SensitiveAccessGrants.NONE, repository);
    private final JdbcSecureIngestionRepository ingestion = new JdbcSecureIngestionRepository(jdbc);
    private final Map<String, Long> times = new LinkedHashMap<>();

    private QuarantineService service() {
        return new QuarantineService(repository, guard, new com.sdv.platform.persistence.fields.PlatformFieldRepository(jdbc.getJdbcTemplate().getDataSource()), foundation, ApprovalSettings.defaults(), tx, com.sdv.platform.application.port.RecordValuesPort.NONE);
    }

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private long count(String query) {
        Long value = jdbc.queryForObject(query, new MapSqlParameterSource(), Long.class);
        return value == null ? 0 : value;
    }

    private static QuarantineService.Actor as(String who, Clearance clearance) {
        return new QuarantineService.Actor(T, who, who + " Person", clearance, "corr-" + who);
    }

    private <R> R once(String name, Supplier<R> call) {
        long start = System.nanoTime();
        R result = call.get();
        times.put(name, (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    /** A screen's API: one call to warm up, then the median of three, which must be within {@code limitMs}. */
    private <R> R screen(String name, long limitMs, Supplier<R> call) {
        call.get();
        long[] ms = new long[3];
        R result = null;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            result = call.get();
            ms[i] = (System.nanoTime() - start) / 1_000_000;
        }
        java.util.Arrays.sort(ms);
        times.put(name, ms[1]);
        assertTrue(ms[1] <= limitMs, name + " took " + ms[1] + " ms at " + N + " documents (agreed: " + limitMs + " ms)");
        return result;
    }

    /** Yesterday (UTC) at midnight: every document is received yesterday, in order. */
    private static final String YESTERDAY = "((date_trunc('day', now() at time zone 'utc') - interval '1 day') at time zone 'utc')";

    private void seed() {
        int submissions = (N + PER_SUBMISSION - 1) / PER_SUBMISSION;
        // Spread over the day: N documents in at most 23 hours.
        double stepMs = Math.max(0.01, 23.0 * 3600 * 1000 / N);
        sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + T + "', 'quarantine-0052', 'Quarantine 0052', 'ACTIVE')");
        sql("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values ('"
                + S + "', '" + T + "', 'loans', 'Loans', 'ACTIVE', 'JWT_BEARER')");
        CaseRows.field(jdbc.getJdbcTemplate(), T, S, "contractNumber", "Contract number", true, "DIGITS_ONLY", 0);
        // Each statement has its own connection here, so the submissions get IDs worked out from their number.
        String submissionsSql = "(select n, md5('5201-' || n)::uuid as submission_id from generate_series(1, " + submissions + ") n)";
        sql("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) "
                + "select submission_id, '" + T + "', '" + S + "', 'sub-' || n, " + PER_SUBMISSION + ", 'COMPLETE' from " + submissionsSql + " s");
        CaseRows.values(jdbc.getJdbcTemplate(), T, S, submissionsSql, "contractNumber", "'5200' || lpad(n::text, 8, '0')");
        sql("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, "
                + "content_sha256, content_size_bytes, status, available_at, ingested_at, page_count, retention_days_snapshot, expires_at, "
                + "sensitivity_level) select gen_random_uuid(), '" + T + "', s.submission_id, '" + S + "', 'doc-' || g, 'secret-name-' || g || '.pdf', "
                + "'application/pdf', repeat('a', 64), 100, 'AVAILABLE', now(), " + YESTERDAY + " + g * interval '" + stepMs + " milliseconds', 3, 1, "
                + "now() + interval '30 days', "
                + "case when g % 10 = 0 then 'HIGHLY_CONFIDENTIAL' when g % 3 = 0 then 'CONFIDENTIAL' else 'STANDARD' end "
                + "from generate_series(1, " + N + ") g join " + submissionsSql + " s on s.n = (g - 1) / " + PER_SUBMISSION + " + 1");
        // One document in a thousand was opened before the quarantine.
        sql("insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, actor_display_name, action, target_type, target_id, "
                + "occurred_at, correlation_id, outcome, safe_details) select gen_random_uuid(), '" + T + "', 'USER', 'uma', 'Uma User', "
                + "'DOCUMENT_VIEW_OPENED', 'DOCUMENT', document_id::text, now() - interval '1 hour', 'c', 'SUCCESS', '{}' from documents "
                + "where tenant_id='" + T + "' and right(source_document_id, 3) = '000'");
        sql("analyze documents");
        sql("analyze audit_events");
    }

    /** Counts what is written, keeps nothing: the export must stream, not build the file in memory. */
    private static final class Counting extends OutputStream {
        long bytes;
        @Override public void write(int b) { bytes++; }
        @Override public void write(byte[] b, int off, int len) { bytes += len; }
    }

    @Test
    void aQuarterOfAMillionDocumentsInOneIncident() throws IOException {
        once("seed " + N + " documents", () -> { seed(); return null; });
        List<UUID> sample = jdbc.query("select document_id from documents where tenant_id=:t order by ingested_at desc limit 100",
                new MapSqlParameterSource("t", T), (rs, row) -> rs.getObject(1, UUID.class));
        UUID last = sample.getFirst();
        String day = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
        QuarantineService.FilterRequest filter = new QuarantineService.FilterRequest(day, day, S.toString(), Map.of());
        QuarantineService.Actor restricted = as("rita", RESTRICTED);
        QuarantineService quarantine = service();

        // The count the screen shows before confirming, then the confirmation (count again, store the filter, first batch).
        assertEquals(N, screen("count before confirming (API)", SCREEN_MS * 3, () -> quarantine.countMatching(restricted, filter)).count());
        QuarantineService.IncidentView incident = once("confirm (count, filter, first batch)",
                () -> quarantine.quarantineByFilter(restricted, filter, (long) N, "Leak", "Suspected leak"));
        String id = incident.incidentId().toString();
        assertTrue(incident.preparing());

        // 1. Blocked at once, before the rows exist, on every path.
        assertTrue(screen("one-document check (every document path)", CHECK_MS, () -> repository.isQuarantined(T, last)));
        assertEquals(100, screen("many-documents check, 100 (search rows, record views)", CHECK_MS * 2,
                () -> repository.quarantined(T, sample)).size());
        com.sdv.platform.domain.document.Document lastDocument = ingestion.findDocumentByIdForTenant(T, last).orElseThrow();
        for (DocumentAccessGuard.Attempt attempt : List.of(DocumentAccessGuard.Attempt.OPEN, DocumentAccessGuard.Attempt.PRINT,
                DocumentAccessGuard.Attempt.DOWNLOAD)) {
            assertEquals("SDV-QUA-001", assertThrows(CodedRefusal.Forbidden.class, () -> guard.requireOpenable(lastDocument,
                    BUSINESS_USER, attempt, "bob", "c")).errorCode().code(), attempt.name());
        }

        // 2. The background preparation, to the end (as the scheduler runs it: 20 batches a run).
        once("preparation, to the end", () -> { while (quarantine.prepare(20) > 0) { /* next run */ } return null; });
        assertEquals(N, count("select count(*) from quarantined_documents where tenant_id='" + T + "' and released_at is null"));
        assertEquals(N, count("select count(distinct document_id) from quarantined_documents where tenant_id='" + T + "'"), "nothing twice");
        assertEquals(N, count("select count(*) from audit_events where tenant_id='" + T + "' and action='DOCUMENT_QUARANTINED'"));
        sql("analyze quarantined_documents");

        // 3. The screens, from the incident's counts and a page at a time.
        List<QuarantineService.IncidentSummary> list = screen("incident list (API)", SCREEN_MS, () -> quarantine.incidents(restricted));
        QuarantineService.IncidentSummary summary = list.stream().filter(i -> i.incidentId().toString().equals(id)).findFirst().orElseThrow();
        assertEquals(N, summary.documentsInForce());
        QuarantineService.IncidentView first = screen("incident page, first (API)", SCREEN_MS, () -> quarantine.incident(restricted, id));
        assertEquals(N, first.documentCount());
        assertEquals(QuarantineService.PAGE, first.documents().size());
        QuarantineService.IncidentView steward = screen("incident page, Steward (API)", SCREEN_MS, () -> quarantine.incident(as("stan", STEWARD), id));
        long highly = count("select count(*) from documents where tenant_id='" + T + "' and sensitivity_level='HIGHLY_CONFIDENTIAL'");
        assertEquals(highly, steward.hiddenCount(), "the Highly Confidential ones, counted from the incident");
        // A deep page: the page after the 90 percent mark, by its position.
        List<Map<String, Object>> deep = jdbc.queryForList("select quarantined_at, received_at, document_id from quarantined_documents "
                + "where tenant_id=:t and released_at is null order by quarantined_at, received_at, document_id offset :o limit 1",
                new MapSqlParameterSource("t", T).addValue("o", (int) (N * 0.9)));
        String deepCursor = QuarantineService.cursor(new com.sdv.platform.application.port.QuarantineRepository.PageKey(true,
                ((java.sql.Timestamp) deep.getFirst().get("quarantined_at")).toInstant(),
                ((java.sql.Timestamp) deep.getFirst().get("received_at")).toInstant(), (UUID) deep.getFirst().get("document_id")));
        assertEquals(QuarantineService.PAGE, screen("incident page, 90 percent in (API)", SCREEN_MS,
                () -> quarantine.incident(restricted, id, deepCursor)).documents().size());
        QuarantineService.ExposureReport exposure = screen("exposure page (API)", SCREEN_MS, () -> quarantine.exposure(restricted, id));
        assertEquals(N, exposure.documentCount());
        assertEquals(N, exposure.standard() + exposure.confidential() + exposure.highlyConfidential());

        // 4. The export streams every document; nothing is kept in memory here.
        Counting sink = new Counting();
        QuarantineService.Written written = once("exposure export, streamed", () -> {
            try {
                return quarantine.openExposureExport(restricted, id).writeTo(sink);
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        });
        assertEquals(N, written.documentCount());
        assertEquals(N, written.rowCount(), "one row per document (each opened one has one access)");
        assertEquals(64, written.fingerprint().length());
        times.put("exposure export size (KB)", sink.bytes / 1024);

        // 5. The whole release: requested, approved (Approvals row from the counts), then in batches in the background.
        quarantine.requestIncidentRelease(restricted, id, "Investigation closed");
        QuarantineService.Actor admin = as("ada", ADMIN);
        List<QuarantineService.ReleaseRequestView> pending = screen("Approvals row (API)", SCREEN_MS, () -> quarantine.pendingReleaseRequests(admin));
        assertEquals(N, pending.getFirst().documentCount());
        assertEquals(highly, pending.getFirst().highlyConfidential());
        once("approve (first batch)", () -> quarantine.decide(admin, pending.getFirst().requestId().toString(), true));
        QuarantineService.IncidentView releasing = quarantine.incident(restricted, id);
        assertTrue(releasing.releasing());
        assertEquals(QuarantineService.BATCH_SIZE, releasing.releasedCount());
        assertTrue(repository.isQuarantined(T, last), "the filter blocks until every document is released");
        once("release, to the end", () -> { while (quarantine.release(20) > 0) { /* next run */ } return null; });
        assertEquals(0, count("select count(*) from quarantined_documents where tenant_id='" + T + "' and released_at is null"));
        assertEquals(N, count("select count(*) from audit_events where tenant_id='" + T + "' and action='DOCUMENT_QUARANTINE_RELEASED'"), "nothing twice");
        assertEquals("RELEASED", quarantine.incident(restricted, id).status());
        assertTrue(repository.quarantined(T, sample).isEmpty());
        assertFalse(repository.isQuarantined(T, last));

        // The counts on the incident match its rows.
        Map<String, Object> counts = jdbc.queryForMap("select document_count, released_count, standard_count + confidential_count "
                + "+ highly_confidential_count as levels, standard_in_force + confidential_in_force + highly_confidential_in_force as in_force "
                + "from quarantine_incidents where incident_id=:id", new MapSqlParameterSource("id", incident.incidentId()));
        assertEquals((long) N, ((Number) counts.get("document_count")).longValue());
        assertEquals((long) N, ((Number) counts.get("released_count")).longValue());
        assertEquals((long) N, ((Number) counts.get("levels")).longValue());
        assertEquals(0L, ((Number) counts.get("in_force")).longValue());

        List<String> lines = new ArrayList<>();
        lines.add("Quarantine at scale (task 0052): " + N + " documents in one incident; screen APIs: median of 3 after one warm-up.");
        times.forEach((k, v) -> lines.add(String.format("%-56s %,10d", k, v)));
        lines.forEach(line -> System.out.println("QUARANTINE-SCALE " + line));
        Files.write(Path.of("target", "quarantine-scale-times.txt"), lines, StandardCharsets.UTF_8);
    }
}
