package com.sdv.platform.application.approvals;

import com.sdv.platform.persistence.fields.CaseRows;
import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.business.RecordReferences;
import com.sdv.platform.application.business.TestRecordRefs;
import com.sdv.platform.application.document.DocumentActionService;
import com.sdv.platform.application.port.BusinessActivityQueryPort;
import com.sdv.platform.application.port.DocumentCommentRepository;
import com.sdv.platform.application.port.DocumentTagRepository;
import com.sdv.platform.application.sensitivity.SensitivityLevelService;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.persistence.approvals.JdbcAccessRequestRepository;
import com.sdv.platform.persistence.business.JdbcBusinessRecordRepository;
import com.sdv.platform.persistence.business.JdbcRecordSensitivityRepository;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.ingestion.JdbcSecureIngestionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Task 0028: access requests against a real PostgreSQL database, with the application's own repositories and
 * services: a grant is used once (also when two uses race), expires, serves only its person and kind; pending requests
 * expire; the approvals list's SQL reads no file name; lowerings are written; the table's checks refuse bad rows,
 * self-decisions included.
 * <p>
 * Runs only when SDV_IT_JDBC_URL is set. Test-SDV-SchemaUpgrade.ps1 sets it to its scratch database (sdv_schema_test)
 * after the schema step; it never points at the sdv database. In the normal build this test is skipped.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class AccessRequestPostgresTest {
    private static final UUID T1 = UUID.fromString("10000000-0000-0000-0000-0000000000d1");
    private static final UUID T2 = UUID.fromString("10000000-0000-0000-0000-0000000000d2");
    private static final UUID S1 = UUID.fromString("20000000-0000-0000-0000-0000000000d1");
    private static final UUID S2 = UUID.fromString("20000000-0000-0000-0000-0000000000d2");
    private static final UUID SUB1 = UUID.fromString("30000000-0000-0000-0000-0000000000d1");
    private static final UUID SUB2 = UUID.fromString("30000000-0000-0000-0000-0000000000d2");
    /** Highly Confidential, 9 pages; H2 its twin (same day, same pages). */
    private static final UUID H = UUID.fromString("41000000-0000-0000-0000-0000000000d1");
    private static final UUID H2 = UUID.fromString("41000000-0000-0000-0000-0000000000d2");
    /** Confidential. */
    private static final UUID C = UUID.fromString("41000000-0000-0000-0000-0000000000d3");
    /** Tenant 2's Highly Confidential document. */
    private static final UUID X = UUID.fromString("41000000-0000-0000-0000-0000000000d4");
    private static final String CONTRACT = "300000000077";

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
    // Whole microseconds, as PostgreSQL stores them.
    private final ApprovalsFixture.MutableClock clock =
            new ApprovalsFixture.MutableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    private final JdbcFoundationRepository audit = new JdbcFoundationRepository(jdbc);
    private final JdbcAccessRequestRepository requests = new JdbcAccessRequestRepository(jdbc);
    private final JdbcBusinessRecordRepository records = new JdbcBusinessRecordRepository(jdbc);
    private final JdbcRecordSensitivityRepository recordLevels = new JdbcRecordSensitivityRepository(jdbc);
    private final JdbcSecureIngestionRepository intake = new JdbcSecureIngestionRepository(jdbc);
    private final SensitiveAccessGrantStore grants = new SensitiveAccessGrantStore(requests, audit, tx, clock);
    private final DocumentAccessGuard guard = new DocumentAccessGuard(records, audit, grants, clock);
    private final RecordReferences references = new RecordReferences(records);
    private final AccessRequestService approvals = new AccessRequestService(requests, intake, guard, references, records,
            recordLevels, grants, ApprovalSettings.defaults(), audit, tx, clock);
    private final SensitivityLevelService levels = new SensitivityLevelService(intake, requests, recordLevels, records,
            references, guard, approvals, audit, tx, clock);
    private final DocumentActionService actions = new DocumentActionService(intake, mock(DocumentTagRepository.class),
            mock(DocumentCommentRepository.class), mock(BusinessActivityQueryPort.class), audit, guard);

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private String one(String query) {
        return jdbc.queryForObject(query, new MapSqlParameterSource(), String.class);
    }

    private long count(String query) {
        Long value = jdbc.queryForObject(query, new MapSqlParameterSource(), Long.class);
        return value == null ? 0 : value;
    }

    private static String code(Executable call) {
        Throwable thrown = assertThrows(RuntimeException.class, call);
        assertInstanceOf(CodedRefusal.class, thrown, thrown.toString());
        return ((CodedRefusal) thrown).errorCode().code();
    }

    private void seed() {
        for (Object[] t : new Object[][]{{T1, S1, SUB1, "apr-t1"}, {T2, S2, SUB2, "apr-t2"}}) {
            sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + t[0] + "', '" + t[3] + "', 'Approvals test', 'ACTIVE')");
            sql("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values ('"
                    + t[1] + "', '" + t[0] + "', 'loans', 'Loans', 'ACTIVE', 'JWT_BEARER')");
            sql("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) values ('"
                    + t[2] + "', '" + t[0] + "', '" + t[1] + "', 'sub', 3, 'COMPLETE')");
            UUID contract = UUID.randomUUID();
            UUID name = UUID.randomUUID();
            CaseRows.field(jdbc.getJdbcTemplate(), (UUID) t[0], (UUID) t[1], "contractNumber", "Contract number", true, "DIGITS_ONLY", 0);
            CaseRows.field(jdbc.getJdbcTemplate(), (UUID) t[0], (UUID) t[1], "lastName", "Last name", false, "NONE", 0);
            CaseRows.value(jdbc.getJdbcTemplate(), (UUID) t[0], (UUID) t[1], (UUID) t[2], "contractNumber", CONTRACT);
            CaseRows.value(jdbc.getJdbcTemplate(), (UUID) t[0], (UUID) t[1], (UUID) t[2], "lastName", "Patel");
        }
        insertDocument(T1, S1, SUB1, H, "HIGHLY_CONFIDENTIAL", "2026-11-14 09:00:00+00", 9);
        insertDocument(T1, S1, SUB1, H2, "HIGHLY_CONFIDENTIAL", "2026-11-14 15:00:00+00", 9);
        insertDocument(T1, S1, SUB1, C, "CONFIDENTIAL", "2026-11-15 09:00:00+00", 3);
        insertDocument(T2, S2, SUB2, X, "HIGHLY_CONFIDENTIAL", "2026-11-14 09:00:00+00", 9);
    }

    private void insertDocument(UUID tenant, UUID source, UUID submission, UUID document, String level, String received, int pages) {
        sql("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, "
                + "content_sha256, content_size_bytes, status, ingested_at, available_at, page_count, sensitivity_level) values ('"
                + document + "', '" + tenant + "', '" + submission + "', '" + source + "', '" + document + "', 'secret-name-"
                + document + ".pdf', 'application/pdf', repeat('a', 64), 100, 'AVAILABLE', '" + received + "', now(), " + pages
                + ", '" + level + "')");
    }

    private UUID ask(UUID tenant, String actor, UUID document, String kind) {
        return approvals.requestPrintOrDownload(tenant, actor, "Rae Restricted", Clearance.ALL, document.toString(), kind,
                "Court bundle", "c").requestId();
    }

    private String status(UUID request) {
        return one("select status from access_requests where request_id='" + request + "'");
    }

    @Test
    void requestsGrantsExpiryAndLevelChangesOnPostgres() throws Exception {
        seed();

        // ---- A grant is used once, by its person, for its kind, inside its window.
        UUID print = ask(T1, "rs-1", H, "PRINT");
        assertEquals("SDV-APR-004", code(() -> ask(T1, "rs-1", H, "PRINT")), "the partial unique index");
        assertEquals("SDV-SEN-009", code(() -> actions.recordPrint(T1, H, Clearance.ALL, "rs-1", "c")));
        approvals.decide(T1, "ba-1", "Bea Admin", print.toString(), AccessRequestService.Decision.APPROVE, "c");
        assertEquals("APPROVED", status(print));
        assertEquals("SDV-SEN-009", code(() -> actions.recordPrint(T1, H, Clearance.ALL, "rs-2", "c")), "another person");
        assertEquals("SDV-SEN-009", code(() -> guard.requirePrintOrDownload(intake.findDocumentByIdForTenant(T1, H).orElseThrow(),
                Clearance.ALL, DocumentAccessGuard.Attempt.DOWNLOAD, "rs-1", "c",
                com.sdv.platform.application.access.SensitiveAccessGrants.Use.USE)), "another kind");
        actions.recordPrint(T1, H, Clearance.ALL, "rs-1", "c");
        assertEquals("USED", status(print));
        assertEquals("SDV-SEN-009", code(() -> actions.recordPrint(T1, H, Clearance.ALL, "rs-1", "c")), "used once");
        assertEquals(1, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action='SENSITIVE_ACCESS_GRANT_USED'"));

        // Task 0043: a used grant is given back only for its person, document and kind, and only while it is used; the
        // row then passes the table's checks (approved, no use time) and can be used once more.
        var printKind = DocumentAccessGuard.Attempt.PRINT;
        assertFalse(grants.giveBack(T1, H, "rs-2", printKind, print, "print not recorded", "c"), "another person");
        assertFalse(grants.giveBack(T1, H, "rs-1", DocumentAccessGuard.Attempt.DOWNLOAD, print, "print not recorded", "c"));
        assertFalse(grants.giveBack(T1, H2, "rs-1", printKind, print, "print not recorded", "c"), "another document");
        assertFalse(grants.giveBack(T2, H, "rs-1", printKind, print, "print not recorded", "c"), "another tenant");
        assertEquals("USED", status(print));
        assertTrue(grants.giveBack(T1, H, "rs-1", printKind, print, "print not recorded", "give-back"));
        assertEquals("APPROVED", status(print));
        assertEquals(0, count("select count(*) from access_requests where request_id='" + print + "' and used_at is not null"));
        assertEquals(1, count("select count(*) from audit_events where tenant_id='" + T1
                + "' and action='SENSITIVE_ACCESS_GRANT_RETURNED' and correlation_id='give-back'"));
        assertFalse(grants.giveBack(T1, H, "rs-1", printKind, print, "print not recorded", "c"), "only while used");
        actions.recordPrint(T1, H, Clearance.ALL, "rs-1", "c");
        assertEquals("USED", status(print));
        assertEquals("SDV-SEN-009", code(() -> actions.recordPrint(T1, H, Clearance.ALL, "rs-1", "c")), "used once more");

        // Two uses at the same moment: exactly one gets the grant.
        UUID race = ask(T1, "rs-1", H2, "PRINT");
        approvals.decide(T1, "ba-1", null, race.toString(), AccessRequestService.Decision.APPROVE, "c");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> tries = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                tries.add(pool.submit(() -> {
                    go.await();
                    try {
                        actions.recordPrint(T1, H2, Clearance.ALL, "rs-1", "c");
                        return true;
                    } catch (CodedRefusal.Forbidden refused) {
                        return false;
                    }
                }));
            }
            go.countDown();
            int printed = 0;
            for (Future<Boolean> t : tries) if (t.get()) printed++;
            assertEquals(1, printed, "one print per approval, also when four race");
        } finally {
            pool.shutdownNow();
        }

        // Task 0037: two Business Admins decide the same request at the same moment. One decision happens; the other is
        // refused with 409 SDV-APR-008 and audited (ACCESS_REQUEST_DECISION_REFUSED). Never a 500.
        UUID decidedTwice = ask(T1, "rs-1", H2, "DOWNLOAD");
        ExecutorService admins = Executors.newFixedThreadPool(2);
        CountDownLatch together = new CountDownLatch(1);
        List<Future<String>> decisions = new ArrayList<>();
        try {
            for (String admin : List.of("ba-1", "ba-2")) {
                decisions.add(admins.submit(() -> {
                    together.await();
                    try {
                        approvals.decide(T1, admin, null, decidedTwice.toString(),
                                AccessRequestService.Decision.DECLINE, // declined, so no grant changes the counts below
                                "corr-race-" + admin);
                        return "DECIDED";
                    } catch (RuntimeException ex) {
                        return ex instanceof CodedRefusal coded ? coded.errorCode().code() : ex.toString();
                    }
                }));
            }
            together.countDown();
            List<String> got = new ArrayList<>();
            for (Future<String> d : decisions) got.add(d.get(30, java.util.concurrent.TimeUnit.SECONDS));
            java.util.Collections.sort(got);
            assertEquals(List.of("DECIDED", "SDV-APR-008"), got);
        } finally {
            admins.shutdownNow();
        }
        assertEquals(1, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action='ACCESS_REQUEST_DECISION_REFUSED' "
                + "and outcome='DENIED' and correlation_id like 'corr-race-%' and safe_details::text like '%SDV-APR-008%'"));
        assertEquals(1, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action in ('ACCESS_REQUEST_APPROVED','ACCESS_REQUEST_DECLINED') "
                + "and correlation_id like 'corr-race-%'"));

        // An unused grant ends after 24 hours.
        UUID late = ask(T1, "rs-1", H, "DOWNLOAD");
        approvals.decide(T1, "ba-1", null, late.toString(), AccessRequestService.Decision.APPROVE, "c");
        clock.advance(Duration.ofHours(24));
        assertTrue(requests.findValidGrant(T1, H, "rs-1", AccessRequestKind.DOWNLOAD, clock.instant()).isEmpty());
        assertTrue(requests.useGrant(T1, H, "rs-1", AccessRequestKind.DOWNLOAD, clock.instant()).isEmpty());
        assertEquals(1, grants.expireDue(T1, "c"));
        assertEquals("EXPIRED", status(late));

        // ---- Pending requests expire after 7 days.
        UUID waiting = ask(T1, "rs-1", H, "PRINT");
        clock.advance(Duration.ofDays(7));
        assertEquals(0, approvals.pending(T1, null, null, "c").requestCount());
        assertEquals("EXPIRED", status(waiting));
        assertEquals("EXPIRED", approvals.mine(T1, "rs-1", "c").getFirst().status().name());
        assertEquals(2, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action='ACCESS_REQUEST_EXPIRED'"));

        // ---- The approvals list: real SQL, primary identifier, position, nothing else; tenants apart.
        UUID p1 = ask(T1, "rs-1", H, "PRINT");
        clock.advance(Duration.ofSeconds(1));
        ask(T1, "rs-1", H2, "DOWNLOAD");
        ask(T2, "rs-9", X, "PRINT");
        var list = approvals.pending(T1, null, null, "c");
        assertEquals(2, list.requestCount(), "tenant 2's request is not listed");
        var first = list.groups().getFirst().requests().getFirst();
        assertEquals(p1, first.requestId());
        assertEquals(CONTRACT, first.primaryIdentifier());
        assertEquals("Contract number", first.primaryIdentifierLabel());
        assertEquals(9, first.pageCount());
        assertEquals(new AccessRequestService.Position(1, 3), first.position());
        String json = com.sdv.platform.application.json.VeroJson.mapper().writeValueAsString(list);
        assertFalse(json.contains("secret-name"), json);
        assertFalse(json.contains("Patel"), json);
        assertEquals(1, approvals.pending(T1, "DOWNLOAD", null, "c").requestCount());
        assertEquals(2, approvals.pending(T1, null, "Rae Restricted", "c").requestCount());
        assertEquals("SDV-APR-007", code(() -> approvals.decide(T1, "ba-1", null, approvals.pending(T2, null, null, "c")
                .groups().getFirst().requests().getFirst().requestId().toString(), AccessRequestService.Decision.APPROVE, "c")),
                "another tenant's request is unknown");

        // ---- Nobody decides their own request: the service refuses, and so does the table.
        assertEquals("SDV-APR-006", code(() -> approvals.decide(T1, "rs-1", null, p1.toString(),
                AccessRequestService.Decision.APPROVE, "c")));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> sql(
                "update access_requests set status='APPROVED', decided_by=requested_by, decided_at=requested_at where request_id='" + p1 + "'"));
        assertEquals("PENDING", status(p1));

        // ---- Lowering a document and a record, applied on approval.
        UUID lower = levels.requestLowerDocument(T1, "steward-1", null, new Clearance(true, false), C.toString(), "STANDARD",
                "Mistake", "c").requestId();
        assertEquals("CONFIDENTIAL", one("select sensitivity_level from documents where document_id='" + C + "'"));
        approvals.decide(T1, "ba-1", null, lower.toString(), AccessRequestService.Decision.APPROVE, "c");
        assertEquals("STANDARD", one("select sensitivity_level from documents where document_id='" + C + "'"));

        String ref = TestRecordRefs.ref(S1, SUB1);
        levels.raiseRecord(T1, "steward-1", null, new Clearance(true, false), ref, "CONFIDENTIAL", "Litigation", "c");
        UUID key = SUB1;
        assertEquals("CONFIDENTIAL", recordLevels.lockByKey(T1, S1, key).orElseThrow().level().name());
        UUID lowerRecord = levels.requestLowerRecord(T1, "steward-1", null, new Clearance(true, false), ref, "STANDARD",
                "Closed", "c").requestId();
        var recordRow = approvals.pending(T1, "LOWER_RECORD_LEVEL", null, "c").groups().getFirst().requests().getFirst();
        assertEquals(CONTRACT, recordRow.primaryIdentifier(), "a whole-record request names the record");
        assertEquals(3, recordRow.documentCount());
        assertEquals("SDV-APR-009", approvals.decideMany(T1, "ba-1", null, "APPROVE", List.of(lowerRecord.toString()), "c")
                .results().getFirst().code());
        clock.advance(Duration.ofMinutes(5)); // the lowering comes after the raise, so the history has an order
        approvals.decide(T1, "ba-1", null, lowerRecord.toString(), AccessRequestService.Decision.APPROVE, "c");
        assertEquals(SensitivityLevel.STANDARD, recordLevels.lockByKey(T1, S1, key).orElseThrow().level());
        assertEquals("Closed", one("select reason from record_sensitivity_levels where tenant_id='" + T1 + "'"));
        // Task 0029 (0.4): the reasons are in the level-change history, never in the audit; each event names its row.
        assertEquals(0, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action like '%_SENSITIVITY_%'"
                + " and safe_details->>'reason' is not null"), "no level-change event carries a reason");
        assertEquals(1, count("select count(*) from audit_events e join sensitivity_level_changes c"
                + " on c.change_id::text = e.safe_details->>'levelChangeId' where e.tenant_id='" + T1 + "'"
                + " and e.action='RECORD_SENSITIVITY_LOWERED' and c.reason='Closed' and c.direction='LOWERED'"
                + " and c.access_request_id is not null and c.changed_by='ba-1'"));
        assertEquals(List.of("Closed", "Litigation"), levels.recordHistory(T1, ref).stream()
                .map(SensitivityLevelService.HistoryEntry::reason).toList(), "the record's history, newest first");
        assertEquals(0, count("select count(*) from sensitivity_level_changes where tenant_id='" + T2 + "'"), "tenants apart");
        assertEquals(0, count("select count(*) from audit_events where tenant_id='" + T1 + "' and action like 'ACCESS_REQUEST_%'"
                + " and safe_details::text like '%Court bundle%'"), "request reasons stay out of the audit");

        // ---- Task 0030 (0.3): the requester's own list names the record, for a whole-record request too.
        var mine = approvals.mine(T1, "steward-1", "c");
        var recordMine = mine.stream().filter(r -> r.requestId().equals(lowerRecord)).findFirst().orElseThrow();
        assertEquals(CONTRACT, recordMine.primaryIdentifier(), "a whole-record request names its record");
        assertEquals("Contract number", recordMine.primaryIdentifierLabel());
        assertEquals(S1, recordMine.sourceId());
        assertNull(recordMine.documentId());
        var documentMine = mine.stream().filter(r -> r.requestId().equals(lower)).findFirst().orElseThrow();
        assertEquals(CONTRACT, documentMine.primaryIdentifier(), "and so does a document request");
        assertEquals(S1, documentMine.sourceId());

        // ---- Task 0030 (item 6): the audit's level filter, real SQL: the level each event recorded, tenants apart.
        var auditQuery = new com.sdv.platform.persistence.operations.JdbcOperationalRepository(jdbc);
        java.util.function.BiFunction<UUID, SensitivityLevel, List<com.sdv.platform.domain.audit.AuditEvent>> byLevel =
                (tenant, level) -> auditQuery.findRows(tenant, new com.sdv.platform.application.port.AuditQueryPort.Filter(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, level), 1000)
                        .stream().map(com.sdv.platform.application.port.AuditQueryPort.Row::event).toList();
        var highly = byLevel.apply(T1, SensitivityLevel.HIGHLY_CONFIDENTIAL);
        var confidential = byLevel.apply(T1, SensitivityLevel.CONFIDENTIAL);
        var actionsFound = highly.stream().map(com.sdv.platform.domain.audit.AuditEvent::action).collect(java.util.stream.Collectors.toSet());
        assertTrue(actionsFound.containsAll(List.of("ACCESS_REQUEST_CREATED", "DOCUMENT_PRINTED", DocumentAccessGuard.REFUSED_ACTION)),
                "requests, prints and refusals of Highly Confidential documents: " + actionsFound);
        assertTrue(highly.stream().allMatch(e -> e.safeDetailsJson().contains("\"HIGHLY_CONFIDENTIAL\"")), "only that level");
        assertFalse(confidential.isEmpty(), "the Confidential lowering request");
        assertTrue(confidential.stream().allMatch(e -> e.safeDetailsJson().contains("\"CONFIDENTIAL\"")
                && !e.safeDetailsJson().contains("HIGHLY_CONFIDENTIAL")), "and nothing else");
        long recorded = count("select count(*) from audit_events where tenant_id='" + T1 + "' and safe_details->>'level' is null"
                + " and safe_details->>'sensitivityLevel' is null");
        assertTrue(recorded > 0, "events that recorded no level exist");
        int all = auditQuery.findRows(T1, new com.sdv.platform.application.port.AuditQueryPort.Filter(null, null, null, null,
                null, null, null, null, null, null, null, null), 1000).size();
        assertTrue(highly.size() + confidential.size() < all, "and are left out by the filter");
        assertTrue(byLevel.apply(T2, SensitivityLevel.HIGHLY_CONFIDENTIAL).stream().allMatch(e -> e.tenantId().equals(T2)),
                "another tenant's events are never found");

        // ---- The table's checks.
        String base = "insert into access_requests(request_id, tenant_id, kind, document_id, source_id, record_id, "
                + "requested_level, reason, requested_by, requested_at, status, expires_at, decided_by, decided_at, used_at) values (";
        String[][] bad = {
                {"ck_access_requests_kind", "'COPY', '" + H + "', null, null, null, 'Reason', 'x', now(), 'PENDING', now() + interval '1 day', null, null, null"},
                {"ck_access_requests_target", "'PRINT', null, null, null, null, 'Reason', 'x', now(), 'PENDING', now() + interval '1 day', null, null, null"},
                {"ck_access_requests_target", "'LOWER_RECORD_LEVEL', null, '" + S1 + "', null, 'STANDARD', 'Reason', 'x', now(), 'PENDING', now() + interval '1 day', null, null, null"},
                {"ck_access_requests_target", "'LOWER_RECORD_LEVEL', null, null, '" + SUB1 + "', 'STANDARD', 'Reason', 'x', now(), 'PENDING', now() + interval '1 day', null, null, null"},
                {"ck_access_requests_requested_level", "'PRINT', '" + H + "', null, null, 'STANDARD', 'Reason', 'x', now(), 'PENDING', now() + interval '1 day', null, null, null"},
                {"ck_access_requests_requested_level", "'LOWER_LEVEL', '" + H + "', null, null, null, 'Reason', 'x', now(), 'PENDING', now() + interval '1 day', null, null, null"},
                {"ck_access_requests_reason", "'PRINT', '" + H + "', null, null, null, ' ab ', 'x', now(), 'PENDING', now() + interval '1 day', null, null, null"},
                {"ck_access_requests_decided_status", "'PRINT', '" + H + "', null, null, null, 'Reason', 'x', now(), 'APPROVED', now() + interval '1 day', null, null, null"},
                {"ck_access_requests_not_own", "'PRINT', '" + H + "', null, null, null, 'Reason', 'x', now(), 'DECLINED', now() + interval '1 day', 'x', now(), null"},
                {"ck_access_requests_used", "'PRINT', '" + H + "', null, null, null, 'Reason', 'x', now(), 'USED', now() + interval '1 day', 'y', now(), null"},
                {"ck_access_requests_used_kind", "'LOWER_LEVEL', '" + H + "', null, null, 'STANDARD', 'Reason', 'x', now(), 'USED', now() + interval '1 day', 'y', now(), now()"},
                {"ck_access_requests_expires", "'PRINT', '" + H + "', null, null, null, 'Reason', 'x', now(), 'PENDING', now() - interval '1 day', null, null, null"},
                {"fk_access_requests_record", "'LOWER_RECORD_LEVEL', null, '" + S1 + "', '" + UUID.randomUUID() + "', 'STANDARD', 'Reason', 'x', now(), 'PENDING', now() + interval '1 day', null, null, null"},
                {"fk_access_requests_document", "'PRINT', '" + X + "', null, null, null, 'Reason', 'x', now(), 'PENDING', now() + interval '1 day', null, null, null"},
        };
        for (String[] row : bad) {
            Exception thrown = assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> sql(base + "'" + UUID.randomUUID() + "', '" + T1 + "', " + row[1] + ")"), row[0]);
            assertTrue(thrown.getMessage().contains(row[0]), row[0] + ": " + thrown.getMessage());
        }

        // Task 0029 (0.4): the level-change history's checks.
        String changes = "insert into sensitivity_level_changes(change_id, tenant_id, scope, document_id, source_id, record_id, direction, from_level, to_level, reason, changed_by, changed_at, access_request_id) values (";
        String[][] badChanges = {
                {"ck_level_changes_scope", "'FOLDER', '" + H + "', null, null, 'RAISED', 'STANDARD', 'CONFIDENTIAL', 'Reason', 'x', now(), null"},
                {"ck_level_changes_target", "'DOCUMENT', null, null, null, 'RAISED', 'STANDARD', 'CONFIDENTIAL', 'Reason', 'x', now(), null"},
                {"ck_level_changes_target", "'RECORD', null, '" + S1 + "', null, 'RAISED', null, 'CONFIDENTIAL', 'Reason', 'x', now(), null"},
                {"ck_level_changes_direction", "'DOCUMENT', '" + H + "', null, null, 'SIDEWAYS', 'STANDARD', 'CONFIDENTIAL', 'Reason', 'x', now(), null"},
                {"ck_level_changes_levels", "'DOCUMENT', '" + H + "', null, null, 'RAISED', 'STANDARD', 'SECRET', 'Reason', 'x', now(), null"},
                {"ck_level_changes_from", "'DOCUMENT', '" + H + "', null, null, 'RAISED', null, 'CONFIDENTIAL', 'Reason', 'x', now(), null"},
                {"ck_level_changes_request", "'DOCUMENT', '" + H + "', null, null, 'LOWERED', 'CONFIDENTIAL', 'STANDARD', 'Reason', 'x', now(), null"},
                {"ck_level_changes_reason", "'DOCUMENT', '" + H + "', null, null, 'RAISED', 'STANDARD', 'CONFIDENTIAL', ' ab ', 'x', now(), null"},
                {"ck_level_changes_changed_by", "'DOCUMENT', '" + H + "', null, null, 'RAISED', 'STANDARD', 'CONFIDENTIAL', 'Reason', '', now(), null"},
                {"fk_level_changes_document", "'DOCUMENT', '" + X + "', null, null, 'RAISED', 'STANDARD', 'CONFIDENTIAL', 'Reason', 'x', now(), null"},
        };
        for (String[] row : badChanges) {
            Exception thrown = assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> sql(changes + "'" + UUID.randomUUID() + "', '" + T1 + "', " + row[1] + ")"), row[0]);
            assertTrue(thrown.getMessage().contains(row[0]), row[0] + ": " + thrown.getMessage());
        }

        // Legal hold names (0.3): the columns exist and take a name.
        assertEquals(2, count("select count(*) from information_schema.columns where table_name='legal_holds' "
                + "and column_name in ('placed_by_display_name', 'released_by_display_name')"));
    }
}
