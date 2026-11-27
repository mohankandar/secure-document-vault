package com.sdv.platform.application.reports;

import com.sdv.platform.persistence.fields.CaseRows;
import com.sdv.platform.application.business.RecordReferences;
import com.sdv.platform.application.document.DocumentFinalizationService;
import com.sdv.platform.application.document.DocumentIntakeService;
import com.sdv.platform.application.document.DocumentReceipt;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.application.storage.StoragePlacementService;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import com.sdv.platform.persistence.approvals.JdbcAccessRequestRepository;
import com.sdv.platform.persistence.business.JdbcBusinessRecordRepository;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.ingestion.JdbcSecureIngestionRepository;
import com.sdv.platform.persistence.reports.JdbcIntakeReportRepository;
import com.sdv.platform.persistence.reports.JdbcStewardNoticeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0032 against a real PostgreSQL database, with the application's own repositories and services:
 * <ol>
 *   <li>Section 0: a corrected document sent again. The checksum a document had before a permanent corrective
 *       redaction (kept in its DOCUMENT_PERMANENTLY_REDACTED audit event) is found by intake (changeset 031's index):
 *       the original after correction is not stored, the corrected document is unchanged, the duplicate row, the
 *       DOCUMENT_UNCORRECTED_COPY_RECEIVED event and the Stewards' notice are written, and the report counts it as a
 *       duplicate. The same file from another sending system is stored (same scope as task 0031).</li>
 *   <li>The report's SQL on a seeded day with every outcome: the tenant's time zone (changeset 030) and its day
 *       boundary, a contract from two sending systems counted once, new and updated, refusals with and without a
 *       submission, failures, the CSV and its audit.</li>
 * </ol>
 * Runs only when SDV_IT_JDBC_URL is set. Test-SDV-SchemaUpgrade.ps1 sets it to its scratch database (sdv_schema_test)
 * after the schema step; it never points at the sdv database. In the normal build this test is skipped.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class IntakeReportPostgresTest {
    /** Section 0's tenant (dates around now) and the report's tenant (fixed dates). */
    private static final UUID T0 = UUID.fromString("10000000-0000-0000-0000-0000000000f0");
    private static final UUID T1 = UUID.fromString("10000000-0000-0000-0000-0000000000f1");
    private static final UUID S0 = UUID.fromString("20000000-0000-0000-0000-0000000000f0");
    private static final UUID S0B = UUID.fromString("20000000-0000-0000-0000-0000000000f4");
    private static final UUID RS = UUID.fromString("20000000-0000-0000-0000-0000000000f1");
    private static final UUID LOS = UUID.fromString("20000000-0000-0000-0000-0000000000f2");
    private static final UUID FIRST = UUID.fromString("30000000-0000-0000-0000-0000000000f0");
    private static final UUID AGAIN = UUID.fromString("30000000-0000-0000-0000-0000000000f1");
    private static final UUID OTHER_SOURCE = UUID.fromString("30000000-0000-0000-0000-0000000000f2");
    private static final UUID CORRECTED = UUID.fromString("41000000-0000-0000-0000-0000000000f0");
    private static final String CONTRACT = "300000000093";
    private static final byte[] ORIGINAL_PDF = "%PDF-1.7\nbefore correction\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final String ORIGINAL_SHA = sha(ORIGINAL_PDF);
    private static final String CORRECTED_SHA = "c".repeat(64);

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final JdbcFoundationRepository foundation = new JdbcFoundationRepository(jdbc);
    private final JdbcSecureIngestionRepository intake = new JdbcSecureIngestionRepository(jdbc);
    private final JdbcIntakeReportRepository reportRepository = new JdbcIntakeReportRepository(jdbc);
    private final StorageProvider storage = mock(StorageProvider.class);
    private final StoragePlacementService placement = mock(StoragePlacementService.class);
    private final StreamingCryptoPort crypto = mock(StreamingCryptoPort.class);
    private final DocumentIntakeService service = new DocumentIntakeService(foundation, foundation, intake, intake, placement,
            List.of(storage), crypto, mock(DocumentFinalizationService.class), foundation,
            new RecordReferences(new JdbcBusinessRecordRepository(jdbc)), new JdbcAccessRequestRepository(jdbc),
            new DataSourceTransactionManager(dataSource), com.sdv.platform.web.ingestion.TestPdfs.UNCHECKED);

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Task 0081: no zone rows are left for the other tests on this database. */
    @org.junit.jupiter.api.AfterEach
    void removeZoneRows() {
        IdentityZones.removeAll(jdbc.getJdbcTemplate());
    }

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private long count(String query) {
        Long value = jdbc.queryForObject(query, new MapSqlParameterSource(), Long.class);
        return value == null ? 0 : value;
    }

    private String one(String query) {
        return jdbc.queryForObject(query, new MapSqlParameterSource(), String.class);
    }

    private void tenant(UUID id, String key) {
        sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + id + "', '" + key + "', '" + key + "', 'ACTIVE')");
    }

    private void source(UUID tenant, UUID source, String key, String name) {
        sql("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values ('"
                + source + "', '" + tenant + "', '" + key + "', '" + name + "', 'ACTIVE', 'JWT_BEARER')");
        CaseRows.field(jdbc.getJdbcTemplate(), tenant, source, "contractNumber", "Contract number", true, "DIGITS_ONLY", 0);
    }

    private static UUID definition(UUID source) {
        return UUID.nameUUIDFromBytes(("definition-" + source).getBytes(StandardCharsets.UTF_8));
    }

    private void submission(UUID tenant, UUID source, UUID submission, String requestId, int expected, String created, String contract) {
        sql("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status, created_at, last_activity_at) values ('"
                + submission + "', '" + tenant + "', '" + source + "', '" + requestId + "', " + expected + ", 'OPEN', '" + created + "', '" + created + "')");
        CaseRows.value(jdbc.getJdbcTemplate(), tenant, source, submission, "contractNumber", contract);
    }

    private void document(UUID tenant, UUID source, UUID submission, UUID document, String sha, Integer pages, String received, String status) {
        sql("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, "
                + "content_sha256, content_size_bytes, status, ingested_at, available_at, page_count, sensitivity_level, processing_error_code) values ('"
                + document + "', '" + tenant + "', '" + submission + "', '" + source + "', 'doc-" + document + "', 'secret-name.pdf', 'application/pdf', '"
                + sha + "', 100, '" + status + "', '" + received + "', " + ("AVAILABLE".equals(status) ? "'" + received + "'" : "null") + ", "
                + (pages == null ? "null" : pages) + ", 'STANDARD', " + ("PROCESSING_FAILED".equals(status) ? "'FINALIZATION_FAILED'" : "null") + ")");
    }

    private void refusal(UUID tenant, UUID source, UUID submission, String requestId, String code, String at) {
        sql("insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, action, target_type, target_id, occurred_at, outcome, safe_details) values ('"
                + UUID.randomUUID() + "', '" + tenant + "', 'SOURCE', 'producer', 'INTAKE_REFUSED', '"
                + (submission == null ? "INGESTION_SOURCE" : "SUBMISSION") + "', '" + (submission == null ? source : submission) + "', '" + at + "', 'DENIED', "
                + "jsonb_build_object('stage', '" + (submission == null ? "SUBMISSION" : "DOCUMENT") + "', 'sourceId', '" + source + "', 'submissionId', "
                + (submission == null ? "null" : "'" + submission + "'") + ", 'requestId', " + (requestId == null ? "null" : "'" + requestId + "'")
                + ", 'documentRequestId', null, 'code', '" + code + "'))");
    }

    private void storageMocks(UUID tenant) throws Exception {
        StorageVolume volume = new StorageVolume(UUID.randomUUID(), tenant, "LOCAL_FILESYSTEM", "primary-a", "/tmp",
                StorageVolumeState.values()[0], 1, 80, 95, now, now);
        StoragePlacementService.PlacementDecision decision = mock(StoragePlacementService.PlacementDecision.class);
        when(decision.volume()).thenReturn(volume);
        when(placement.select(eq(tenant), anyLong())).thenReturn(decision);
        when(storage.providerId()).thenReturn("LOCAL_FILESYSTEM");
        when(storage.store(eq(tenant), eq(volume), any())).thenAnswer(call -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            call.getArgument(2, StorageProvider.ContentWriter.class).write(out);
            return new StorageProvider.StoredObject("tmp-object-" + UUID.randomUUID(), out.size());
        });
        when(crypto.encrypt(org.mockito.ArgumentMatchers.argThat(b -> b != null && b.entityId().equals(tenant) && b.sealedId() == null), any(), any())).thenAnswer(call -> {
            byte[] plain = call.getArgument(1, InputStream.class).readAllBytes();
            call.getArgument(2, OutputStream.class).write(plain);
            return new StreamingCryptoPort.CryptoEnvelope("p", 1, "k", "ref", "v1", plain.length, "e".repeat(64));
        });
    }

    @Test
    void reportsAndUncorrectedCopiesOnPostgres() throws Exception {
        // ---- The schema: the new indexes; task 0081: tenants.time_zone is gone (zones are identity's).
        assertEquals(3, count("select count(*) from pg_indexes where indexname in ('idx_submissions_tenant_created', "
                + "'idx_documents_tenant_ingested', 'idx_audit_events_redaction_old_sha')"));
        assertEquals(0, count("select count(*) from pg_index i join pg_class c on c.oid=i.indexrelid where not i.indisvalid "
                + "and c.relname like 'idx_%'"), "no invalid index left by a concurrent build");
        assertEquals(0, count("select count(*) from information_schema.columns where table_name = 'tenants' and column_name = 'time_zone'"),
                "task 0081: zones are identity's");
        tenant(T0, "rep-t0");
        tenant(T1, "rep-t1");
        IdentityZones.clear(new org.springframework.jdbc.core.JdbcTemplate(dataSource), T0);
        IdentityZones.set(new org.springframework.jdbc.core.JdbcTemplate(dataSource), T1, "America/New_York", "Europe/London");

        uncorrectedCopy();
        seededDay();
    }

    private void uncorrectedCopy() throws Exception {
        source(T0, S0, "loans", "Redaction Service");
        source(T0, S0B, "cards", "Card system");
        submission(T0, S0, FIRST, "first", 1, now.minus(2, ChronoUnit.DAYS).toString(), CONTRACT);
        submission(T0, S0, AGAIN, "again", 2, now.minus(1, ChronoUnit.HOURS).toString(), CONTRACT);
        submission(T0, S0B, OTHER_SOURCE, "other", 1, now.minus(1, ChronoUnit.HOURS).toString(), CONTRACT);
        document(T0, S0, FIRST, CORRECTED, CORRECTED_SHA, 4, now.minus(2, ChronoUnit.DAYS).toString(), "AVAILABLE");
        // The permanent correction that changed ORIGINAL_SHA into CORRECTED_SHA, as CorrectiveRedactionService audits it.
        sql("insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, action, target_type, target_id, occurred_at, outcome, safe_details) values ('"
                + UUID.randomUUID() + "', '" + T0 + "', 'USER', 'steward', 'DOCUMENT_PERMANENTLY_REDACTED', 'DOCUMENT', '" + CORRECTED + "', '"
                + now.minus(1, ChronoUnit.DAYS) + "', 'SUCCESS', jsonb_build_object('oldContentSha256', '" + ORIGINAL_SHA
                + "', 'newContentSha256', '" + CORRECTED_SHA + "'))");
        storageMocks(T0);

        assertEquals(CORRECTED, intake.findCorrectedOriginalOf(T0, S0, List.of(FIRST, AGAIN), ORIGINAL_SHA).orElseThrow().documentId());
        assertTrue(intake.findCorrectedOriginalOf(T0, S0, List.of(AGAIN), ORIGINAL_SHA).isEmpty(), "another record's submissions only");
        assertTrue(intake.findCorrectedOriginalOf(T0, S0B, List.of(FIRST, AGAIN), ORIGINAL_SHA).isEmpty(), "another sending system");
        assertTrue(intake.findCorrectedOriginalOf(T1, S0, List.of(FIRST, AGAIN), ORIGINAL_SHA).isEmpty(), "another tenant");
        assertTrue(intake.findCorrectedOriginalOf(T0, S0, List.of(FIRST, AGAIN), CORRECTED_SHA).isEmpty(), "the current checksum is an exact duplicate, not this");

        long documentsBefore = count("select count(*) from documents where tenant_id='" + T0 + "'");
        DocumentReceipt receipt = service.receive(T0, S0, AGAIN, "d1", null, "again.pdf", "application/pdf", null,
                ORIGINAL_PDF.length, new ByteArrayInputStream(ORIGINAL_PDF), "loans-producer", "corr-0032");
        assertEquals(DocumentIntakeService.DUPLICATE_ALREADY_STORED, receipt.receiptAcknowledgement());
        assertEquals(CORRECTED, receipt.document().documentId());
        // The original after correction is not stored; the corrected document is unchanged.
        assertEquals(documentsBefore, count("select count(*) from documents where tenant_id='" + T0 + "'"));
        assertEquals(0, count("select count(*) from documents where tenant_id='" + T0 + "' and content_sha256='" + ORIGINAL_SHA + "'"));
        assertEquals(CORRECTED_SHA, one("select content_sha256 from documents where document_id='" + CORRECTED + "'"));
        assertEquals("AVAILABLE", one("select status from documents where document_id='" + CORRECTED + "'"));
        assertEquals(1, count("select count(*) from document_duplicate_receipts where tenant_id='" + T0 + "' and submission_id='" + AGAIN
                + "' and existing_document_id='" + CORRECTED + "' and content_sha256='" + ORIGINAL_SHA + "' and source_document_id='d1'"));
        assertEquals(1, count("select count(*) from audit_events where tenant_id='" + T0 + "' and action='DOCUMENT_UNCORRECTED_COPY_RECEIVED' "
                + "and target_id='" + CORRECTED + "' and safe_details->>'correctedContentSha256'='" + CORRECTED_SHA + "'"));
        assertEquals(0, count("select count(*) from audit_events where tenant_id='" + T0 + "' and action='DOCUMENT_DUPLICATE_RECEIVED'"));
        verify(storage, times(1)).delete(eq(T0), any(), anyString());

        // The Stewards' notice (task 0033, 0.1: stored, changeset 032): the record and the sending system, never a file,
        // made from the audit event, in the same transaction as the receipt.
        assertEquals(1, count("select count(*) from steward_notices n join audit_events a on a.audit_event_id=n.audit_event_id "
                + "where n.tenant_id='" + T0 + "' and a.action='DOCUMENT_UNCORRECTED_COPY_RECEIVED' and n.kind='UNCORRECTED_COPY_RECEIVED' "
                + "and n.source_id='" + S0 + "' and n.record_identifier='" + CONTRACT + "' and n.read_by='{}'::jsonb"));
        assertEquals(0, count("select count(*) from steward_notices where tenant_id='" + T0 + "' and (record_identifier like '%.pdf' "
                + "or record_identifier_label like '%.pdf')"), "no file name");
        StewardNoticeService stewards = new StewardNoticeService(new JdbcStewardNoticeRepository(jdbc), foundation);
        StewardNoticeService.Notices forDee = stewards.unread(T0, "dee-steward");
        assertEquals(1, forDee.unread());
        StewardNoticeService.Notice notice = forDee.notices().get(0);
        assertEquals(CONTRACT, notice.primaryIdentifier());
        assertEquals("Contract number", notice.primaryIdentifierLabel());
        assertEquals("Redaction Service", notice.sendingSystem());
        assertEquals(IntakeReportService.NOTICE_MESSAGE, notice.message());
        assertEquals(0, stewards.unread(T1, "dee-steward").unread(), "tenants apart");
        // Each steward marks their own: Dee's mark leaves it unread for Rae, and a second mark keeps Dee's first time.
        assertEquals(1, stewards.markRead(T0, "dee-steward", "corr-read", List.of(notice.noticeId())));
        assertEquals(0, stewards.unread(T0, "dee-steward").unread());
        assertEquals(1, stewards.unread(T0, "rae-restricted").unread());
        String firstRead = one("select read_by->>'dee-steward' from steward_notices where notice_id='" + notice.noticeId() + "'");
        assertEquals(0, stewards.markRead(T0, "dee-steward", "corr-read", List.of(notice.noticeId())));
        assertEquals(firstRead, one("select read_by->>'dee-steward' from steward_notices where notice_id='" + notice.noticeId() + "'"));
        assertEquals(1, count("select count(*) from audit_events where tenant_id='" + T0 + "' and action='STEWARD_NOTICE_READ' "
                + "and actor_id='dee-steward' and target_id='" + notice.noticeId() + "'"), "audited once");
        assertEquals("SDV-INT-002", assertThrows(com.sdv.platform.domain.error.CodedRefusal.NotFound.class,
                () -> stewards.markRead(T1, "dee-steward", "c", List.of(notice.noticeId()))).errorCode().code(), "another tenant's notice");
        // The table's checks.
        assertThrows(RuntimeException.class, () -> sql("insert into steward_notices(notice_id, tenant_id, kind, audit_event_id, created_at) "
                + "select gen_random_uuid(), tenant_id, 'SOMETHING_ELSE', audit_event_id, now() from audit_events where tenant_id='" + T0
                + "' and action='STEWARD_NOTICE_READ'"), "kind");
        assertThrows(RuntimeException.class, () -> sql("update steward_notices set read_by='[]'::jsonb where tenant_id='" + T0 + "'"), "read_by is an object");
        assertThrows(RuntimeException.class, () -> sql("insert into steward_notices(notice_id, tenant_id, kind, audit_event_id, created_at) "
                + "select gen_random_uuid(), tenant_id, kind, audit_event_id, now() from steward_notices where tenant_id='" + T0 + "'"), "one notice per event");
        IntakeReportService reports = new IntakeReportService(reportRepository, foundation, Clock.fixed(now, ZoneOffset.UTC));

        // The Daily intake report counts it as a duplicate (today, in UTC: T0 has no zone of its own).
        String yesterday = java.time.LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
        String tomorrow = java.time.LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
        IntakeReportService.IntakeReport today = reports.intake(T0, yesterday, tomorrow, S0.toString());
        assertEquals(1, today.totals().duplicates());
        assertEquals(0, today.totals().documents());
        assertEquals("NONE", today.timeZoneSource());
        String csv = new String(reports.export(T0, "admin", "corr-csv", yesterday, tomorrow, S0.toString()).csv(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"" + ORIGINAL_SHA + "\",\"again\",\"Redaction Service\",\"" + CONTRACT + "\",\"4\","), csv);
        assertTrue(csv.contains(",\"DUPLICATE_ALREADY_STORED\","), csv);

        // The same file from another sending system is another scope: neither lookup finds anything for it, so intake
        // stores it as a new document (that path is task 0031's, tested in DuplicateIntakePostgresTest).
        List<UUID> otherRecord = new RecordReferences(new JdbcBusinessRecordRepository(jdbc)).ofSubmission(T0, S0B, OTHER_SOURCE).submissionIds();
        assertEquals(List.of(OTHER_SOURCE), otherRecord);
        assertTrue(intake.findCorrectedOriginalOf(T0, S0B, otherRecord, ORIGINAL_SHA).isEmpty());
        assertTrue(intake.findDuplicateOf(T0, S0B, otherRecord, ORIGINAL_SHA).isEmpty());
    }

    /** Friday 20 November 2026 in New York: 05:00 UTC on the 20th to 05:00 UTC on the 21st. */
    private void seededDay() {
        source(T1, RS, "redaction-service", "Redaction Service");
        source(T1, LOS, "los", "Loan Origination System");
        UUID a1 = UUID.randomUUID(), a2 = UUID.randomUUID(), b1 = UUID.randomUUID(), dupTarget = UUID.randomUUID();
        submission(T1, RS, a1, "req-a1", 9, "2026-11-20T14:00:00Z", "300000000001");
        submission(T1, RS, a2, "req-a2", 9, "2026-11-19T20:00:00Z", "300000000002");
        submission(T1, LOS, b1, "=req-b1", 9, "2026-11-20T17:00:00Z", "300000000001");
        document(T1, RS, a1, dupTarget, "1".repeat(64), 5, "2026-11-20T14:01:00Z", "AVAILABLE");
        document(T1, RS, a1, UUID.randomUUID(), "2".repeat(64), 7, "2026-11-20T14:02:00Z", "AVAILABLE");
        document(T1, RS, a1, UUID.randomUUID(), "3".repeat(64), null, "2026-11-20T14:04:00Z", "PROCESSING_FAILED");
        document(T1, RS, a1, UUID.randomUUID(), "4".repeat(64), 1, "2026-11-21T04:59:00Z", "AVAILABLE");
        document(T1, RS, a1, UUID.randomUUID(), "5".repeat(64), 9, "2026-11-20T04:59:59Z", "AVAILABLE");
        document(T1, RS, a2, UUID.randomUUID(), "6".repeat(64), 3, "2026-11-20T16:00:00Z", "AVAILABLE");
        document(T1, LOS, b1, UUID.randomUUID(), "7".repeat(64), 4, "2026-11-20T17:01:00Z", "AVAILABLE");
        sql("insert into document_duplicate_receipts(duplicate_id, tenant_id, source_id, submission_id, source_document_id, existing_document_id, "
                + "content_sha256, content_size_bytes, sent_level, level_outcome, received_at) values ('" + UUID.randomUUID() + "', '" + T1 + "', '"
                + RS + "', '" + a1 + "', 'dup-1', '" + dupTarget + "', '" + "1".repeat(64) + "', 100, 'STANDARD', 'KEPT', '2026-11-20T14:03:00Z')");
        refusal(T1, RS, a2, null, "SDV-SEN-001", "2026-11-20T16:05:00Z");
        refusal(T1, LOS, null, "req-bad", "SDV-SEN-006", "2026-11-20T18:00:00Z");
        // A refusal naming another sending system's submission is not attributed to it.
        refusal(T1, LOS, a1, null, "NOT_FOUND", "2026-11-20T18:30:00Z");
        // Another tenant's activity on the same day is never counted.
        refusal(T0, S0, null, "t0-refusal", "BAD_REQUEST", "2026-11-20T12:00:00Z");

        IntakeReportService reports = new IntakeReportService(reportRepository, foundation,
                Clock.fixed(Instant.parse("2026-11-20T15:00:00Z"), ZoneOffset.UTC));
        IntakeReportService.IntakeReport r = reports.intake(T1, null, null, null);
        assertEquals("America/New_York", r.timeZone());
        assertEquals("ENTITY", r.timeZoneSource());
        assertEquals("2026-11-20", r.today());
        assertEquals("Contract number", r.primaryIdentifierLabel());
        IntakeReportService.Totals t = r.totals();
        assertEquals(5, t.deliveries(), "A1, A2, B1, the refusal before a submission, the unattributed refusal");
        assertEquals(2, t.sendingSystems());
        assertEquals(5, t.documents());
        assertEquals(20, t.pages());
        assertEquals(1, t.duplicates());
        assertEquals(2, t.contracts(), "300000000001 from both sending systems counts once");
        assertEquals(1, t.newContracts());
        assertEquals(1, t.updatedContracts());
        assertEquals(3, t.refused());
        assertEquals(1, t.failed());
        IntakeReportService.SystemBlock rs = r.systems().stream().filter(b -> b.sourceId().equals(RS)).findFirst().orElseThrow();
        assertEquals(new IntakeReportService.SystemTotals(2, 2, 4, 16, 1, 1, 1), rs.totals());
        assertEquals("req-a1", rs.deliveries().get(0).requestId());
        assertEquals("2026-11-20T09:00", rs.deliveries().get(0).receivedLocal());
        IntakeReportService.SystemBlock los = r.systems().stream().filter(b -> b.sourceId().equals(LOS)).findFirst().orElseThrow();
        assertEquals(3, los.totals().deliveries());
        assertEquals(1, los.totals().contracts());
        assertTrue(los.deliveries().stream().anyMatch(l -> "req-bad".equals(l.requestId()) && l.refusedCodes().equals(List.of("SDV-SEN-006"))));
        assertTrue(los.deliveries().stream().anyMatch(l -> l.requestId() == null && l.refusedCodes().equals(List.of("NOT_FOUND"))));

        // One sending system; the day before (A2 is new then); a range.
        assertEquals(1, reports.intake(T1, null, null, LOS.toString()).systems().size());
        IntakeReportService.IntakeReport before = reports.intake(T1, "2026-11-19", "2026-11-19", null);
        // A2 was created on the 19th; A1's document at 23:59:59 New York time is on the 19th too. Neither contract
        // was delivered before the 19th, so both are new that day.
        assertEquals(2, before.totals().contracts());
        assertEquals(2, before.totals().newContracts());
        assertEquals(1, before.totals().documents(), "the document at 23:59:59 New York time on the 19th");
        IntakeReportService.IntakeReport range = reports.intake(T1, "2026-11-19", "2026-11-21", null);
        assertEquals(6, range.totals().documents(), "seven stored in the three days, one failed");
        assertEquals(2, range.totals().newContracts());

        // The CSV: one line per document, the formula guard, both times, the outcome and the code; audited.
        IntakeReportService.Export export = reports.export(T1, "priya", "corr-export", null, null, null);
        String csv = new String(export.csv(), StandardCharsets.UTF_8);
        assertEquals(10, export.rowCount(), "6 documents (one failed), 1 duplicate, 3 refusals");
        assertTrue(csv.contains("\"" + "1".repeat(64) + "\",\"req-a1\",\"Redaction Service\",\"300000000001\",\"5\",\"2026-11-20T09:01-05:00\",\"2026-11-20T14:01:00Z\",\"ACCEPTED\",\"\""), csv);
        assertTrue(csv.contains(",\"FAILED\","));
        assertTrue(csv.contains("\"'=req-b1\""), "formula guard");
        assertTrue(csv.contains("\"\",\"req-bad\",\"Loan Origination System\",\"\",\"\",\"2026-11-20T13:00-05:00\",\"2026-11-20T18:00:00Z\",\"REFUSED\",\"SDV-SEN-006\""), csv);
        assertFalse(csv.contains("secret-name"), "never a file name");
        assertFalse(csv.contains("t0-refusal"), "never another tenant's");
        assertEquals(1, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action='INTAKE_REPORT_EXPORTED' "
                + "and actor_id='priya' and (safe_details->>'rowCount')::int=10 and safe_details->>'timeZone'='America/New_York'"));
    }
}
