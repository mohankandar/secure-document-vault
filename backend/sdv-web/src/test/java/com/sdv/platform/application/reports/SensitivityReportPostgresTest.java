package com.sdv.platform.application.reports;

import com.sdv.platform.application.business.RecordKey;
import com.sdv.platform.application.port.SensitivityReportRepository;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.reports.JdbcIntakeReportRepository;
import com.sdv.platform.persistence.reports.JdbcSensitivityReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0033: the Sensitivity report's SQL against a real PostgreSQL database, with the application's own repositories
 * and service, on a seeded tenant (America/New_York) with two sending systems and a second tenant that must never be
 * counted.
 * <ul>
 *   <li>Documents by level: effective levels (a record level raises every document of the record's submissions, found
 *       as record search finds them; a submission with another identifier value is another record; a record of one
 *       submission), stored documents only, records with a level, documents on an active hold.</li>
 *   <li>Requests by kind and outcome, judged now; the period's first moment is midnight in New York.</li>
 *   <li>Level changes by who (Steward, sending system) and lowerings; sets and kept levels are not changes.</li>
 *   <li>Restricted attempts by role, with people; approval-needed refusals are not counted; old events without roles.</li>
 *   <li>The sending-system filter on every block; the CSV and its audit.</li>
 * </ul>
 * Runs only when SDV_IT_JDBC_URL is set (Test-SDV-SchemaUpgrade.ps1, scratch database sdv_schema_test); otherwise
 * skipped.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test")
class SensitivityReportPostgresTest {
    private static final UUID T = UUID.fromString("10000000-0000-0000-0000-0000000005e1");
    private static final UUID TX = UUID.fromString("10000000-0000-0000-0000-0000000005e2");
    private static final UUID LV = UUID.fromString("20000000-0000-0000-0000-0000000005e1");
    private static final UUID LOS = UUID.fromString("20000000-0000-0000-0000-0000000005e2");
    private static final UUID XS = UUID.fromString("20000000-0000-0000-0000-0000000005e3");
    private static final Instant NOW = Instant.parse("2026-11-21T15:00:00Z");
    private static final String C1 = "300000000001";
    private static final String C2 = "300000000002";

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final JdbcSensitivityReportRepository repository = new JdbcSensitivityReportRepository(jdbc);
    private final SensitivityReportService service = new SensitivityReportService(repository,
            new JdbcIntakeReportRepository(jdbc), new JdbcFoundationRepository(jdbc), "UTC", Clock.fixed(NOW, ZoneOffset.UTC));

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private long count(String query) {
        Long value = jdbc.queryForObject(query, new MapSqlParameterSource(), Long.class);
        return value == null ? 0 : value;
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("sens-0033-" + name).getBytes(StandardCharsets.UTF_8));
    }

    private void tenant(UUID tenant, String key) {
        sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + tenant + "', '" + key + "', '" + key + "', 'ACTIVE')");
    }

    private void source(UUID tenant, UUID source, String key, String name) {
        sql("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values ('"
                + source + "', '" + tenant + "', '" + key + "', '" + name + "', 'ACTIVE', 'JWT_BEARER')");
        field(tenant, source, "contractNumber", "Contract number");
        field(tenant, source, "customerId", "Customer ID");
    }

    private void field(UUID tenant, UUID source, String key, String label) {
        sql("insert into metadata_field_definitions(definition_id, tenant_id, source_id, field_key, version, display_label, field_type, "
                + "required, business_identifier, searchable, normalization, search_mode) values ('" + id(source + key) + "', '"
                + tenant + "', '" + source + "', '" + key + "', 1, '" + label + "', 'TEXT', false, true, true, 'TRIM', 'EXACT')");
    }

    /** A delivery with these identifier values (field, value, field, value ...), or none. */
    private UUID submission(UUID tenant, UUID source, String name, String... identifiers) {
        UUID submission = id(name);
        sql("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status, created_at, last_activity_at) values ('"
                + submission + "', '" + tenant + "', '" + source + "', '" + name + "', 1, 'OPEN', '2026-10-01T12:00:00Z', '2026-10-01T12:00:00Z')");
        for (int i = 0; i < identifiers.length; i += 2) {
            sql("insert into business_metadata_values(metadata_value_id, tenant_id, source_id, submission_id, definition_id, field_key, "
                    + "definition_version, stored_value, normalized_value) values ('" + UUID.randomUUID() + "', '" + tenant + "', '"
                    + source + "', '" + submission + "', '" + id(source + identifiers[i]) + "', '" + identifiers[i] + "', 1, '"
                    + identifiers[i + 1] + "', '" + identifiers[i + 1] + "')");
        }
        return submission;
    }

    private UUID document(UUID tenant, UUID source, UUID submission, String name, String level, String status) {
        UUID document = id(name);
        sql("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, "
                + "content_sha256, content_size_bytes, status, ingested_at, available_at, page_count, sensitivity_level, processing_error_code, "
                + "deleted_at, deletion_reason) values ('"
                + document + "', '" + tenant + "', '" + submission + "', '" + source + "', '" + name + "', 'secret-name.pdf', 'application/pdf', '"
                + "a".repeat(64) + "', 100, '" + status + "', '2026-10-01T12:00:00Z', "
                + ("AVAILABLE".equals(status) ? "'2026-10-01T12:01:00Z'" : "null") + ", 1, '" + level + "', "
                + ("PROCESSING_FAILED".equals(status) ? "'FINALIZATION_FAILED'" : "null") + ", "
                + ("EXPIRED_DELETED".equals(status) ? "'2026-11-01T00:00:00Z', 'RETENTION_EXPIRED'" : "null, null") + ")");
        return document;
    }

    private UUID recordLevel(UUID tenant, UUID source, String key, String level) {
        UUID levelId = id("level-" + source + key);
        sql("insert into record_sensitivity_levels(record_level_id, tenant_id, source_id, record_key, record_key_sha256, sensitivity_level, "
                + "set_by, set_at, updated_at) values ('" + levelId + "', '" + tenant + "', '" + source + "', '" + key.replace("'", "''")
                + "', '" + RecordKey.sha256(key) + "', '" + level + "', 'SOURCE', '2026-10-01T12:00:00Z', '2026-10-01T12:00:00Z')");
        return levelId;
    }

    private static String contract(String value) {
        return RecordKey.ofIdentifiers(List.of(new RecordKey.Part("contractNumber", value)));
    }

    private void hold(UUID tenant, UUID document, boolean active) {
        sql("insert into legal_holds(hold_id, tenant_id, document_id, reason, placed_by, placed_at, released_by, released_at) values ('"
                + UUID.randomUUID() + "', '" + tenant + "', '" + document + "', 'Litigation', 'admin', '2026-10-02T00:00:00Z', "
                + (active ? "null, null" : "'admin', '2026-10-03T00:00:00Z'") + ")");
    }

    /** status, decided (or not), used (or not), when asked, when it expires. */
    private void request(UUID tenant, String kind, UUID document, String status, boolean decided, String requestedAt,
                         String expiresAt, String who) {
        boolean used = "USED".equals(status);
        String level = kind.startsWith("LOWER") ? "'STANDARD'" : "null";
        String target = "LOWER_RECORD_LEVEL".equals(kind)
                ? "null, '" + LV + "', '" + contract(C1) + "', '" + RecordKey.sha256(contract(C1)) + "'"
                : "'" + document + "', null, null, null";
        sql("insert into access_requests(request_id, tenant_id, kind, document_id, source_id, record_key, record_key_sha256, requested_level, "
                + "reason, requested_by, requested_at, status, decided_by, decided_at, expires_at, used_at) values ('" + UUID.randomUUID()
                + "', '" + tenant + "', '" + kind + "', " + target + ", " + level + ", 'For the audit file', '" + who + "', '" + requestedAt
                + "', '" + status + "', " + (decided ? "'admin', '" + requestedAt + "'::timestamptz + interval '1 hour'" : "null, null")
                + ", '" + expiresAt + "', " + (used ? "'" + requestedAt + "'::timestamptz + interval '2 hours'" : "null") + ")");
    }

    private void event(UUID tenant, String actorType, String actor, String action, String targetType, Object target,
                       String at, String outcome, String details) {
        sql("insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, action, target_type, target_id, occurred_at, outcome, safe_details) values ('"
                + UUID.randomUUID() + "', '" + tenant + "', '" + actorType + "', '" + actor + "', '" + action + "', '" + targetType + "', '"
                + target + "', '" + at + "', '" + outcome + "', '" + details + "'::jsonb)");
    }

    private void refused(UUID tenant, String actor, UUID document, String code, String roles, String at) {
        refused(tenant, actor, document, "OPEN", code, roles, at);
    }

    private void refused(UUID tenant, String actor, UUID document, String attempt, String code, String roles, String at) {
        event(tenant, "USER", actor, "DOCUMENT_ACCESS_REFUSED", "DOCUMENT", document, at, "DENIED",
                "{\"level\":\"HIGHLY_CONFIDENTIAL\",\"attempt\":\"" + attempt + "\",\"code\":\"" + code + "\"" + (roles == null ? "" : ",\"roles\":" + roles) + "}");
    }

    @Test
    void everyBlockIsCountedOnPostgres() {
        tenant(T, "sens-0033");
        tenant(TX, "sens-0033-other");
        sql("update tenants set time_zone='America/New_York' where tenant_id='" + T + "'");
        source(T, LV, "loans", "LexaVault");
        source(T, LOS, "origination", "Loan Origination System");
        source(TX, XS, "other", "Another tenant system");

        // ---- Documents. Contract C1 at LexaVault is Highly Confidential as a record: both its deliveries count at that level.
        UUID s1 = submission(T, LV, "s1", "contractNumber", C1);
        UUID s2 = submission(T, LV, "s2", "contractNumber", C1);
        UUID s3 = submission(T, LV, "s3", "contractNumber", C2);
        UUID s4 = submission(T, LV, "s4");
        UUID s6 = submission(T, LV, "s6", "contractNumber", C1, "customerId", "CUST-9");
        UUID s5 = submission(T, LOS, "s5", "contractNumber", C1);
        UUID d1 = document(T, LV, s1, "d1", "STANDARD", "AVAILABLE");
        UUID d2 = document(T, LV, s1, "d2", "CONFIDENTIAL", "AVAILABLE");
        UUID d3 = document(T, LV, s1, "d3", "STANDARD", "EXPIRED_DELETED");
        document(T, LV, s1, "d4", "STANDARD", "PROCESSING_FAILED");
        document(T, LV, s2, "d5", "STANDARD", "RECEIVED");
        UUID d6 = document(T, LV, s3, "d6", "HIGHLY_CONFIDENTIAL", "AVAILABLE");
        document(T, LV, s4, "d7", "STANDARD", "AVAILABLE");
        // Another identifier value besides C1: another record, so the C1 level does not reach it.
        document(T, LV, s6, "d9", "STANDARD", "AVAILABLE");
        // The same contract number from the other sending system is that system's own record.
        UUID d8 = document(T, LOS, s5, "d8", "STANDARD", "AVAILABLE");
        recordLevel(T, LV, contract(C1), "HIGHLY_CONFIDENTIAL");
        recordLevel(T, LV, RecordKey.ofSubmission(s4), "CONFIDENTIAL");
        recordLevel(T, LV, contract(C2), "STANDARD");
        hold(T, d1, true);
        hold(T, d6, false);
        hold(T, d3, true);
        // The other tenant: never counted.
        UUID xs = submission(TX, XS, "x1", "contractNumber", C1);
        UUID xd = document(TX, XS, xs, "x-d1", "HIGHLY_CONFIDENTIAL", "AVAILABLE");
        recordLevel(TX, XS, contract(C1), "HIGHLY_CONFIDENTIAL");
        hold(TX, xd, true);

        // ---- Requests (November in New York starts at 04:00 UTC on 1 November; now is 21 November 15:00 UTC).
        request(T, "DOWNLOAD", d6, "APPROVED", true, "2026-11-05T10:00:00Z", "2026-11-25T10:00:00Z", "r1");
        request(T, "DOWNLOAD", d6, "USED", true, "2026-11-05T11:00:00Z", "2026-11-06T11:00:00Z", "r2");
        request(T, "DOWNLOAD", d6, "EXPIRED", true, "2026-11-05T12:00:00Z", "2026-11-06T12:00:00Z", "r3");
        request(T, "DOWNLOAD", d6, "DECLINED", true, "2026-11-05T13:00:00Z", "2026-11-12T13:00:00Z", "r4");
        request(T, "DOWNLOAD", d6, "EXPIRED", false, "2026-11-05T14:00:00Z", "2026-11-12T14:00:00Z", "r5");
        request(T, "PRINT", d6, "PENDING", false, "2026-11-19T10:00:00Z", "2026-11-26T10:00:00Z", "r6");
        // Still marked waiting, but its time ran out before now: expired.
        request(T, "PRINT", d6, "PENDING", false, "2026-11-13T10:00:00Z", "2026-11-20T10:00:00Z", "r7");
        request(T, "LOWER_LEVEL", d2, "PENDING", false, "2026-11-19T10:00:00Z", "2026-11-26T10:00:00Z", "r8");
        request(T, "LOWER_RECORD_LEVEL", null, "PENDING", false, "2026-11-19T10:00:00Z", "2026-11-26T10:00:00Z", "r9");
        // 23:59 on 31 October in New York: last month.
        request(T, "DOWNLOAD", d6, "PENDING", false, "2026-11-01T03:59:00Z", "2026-11-26T10:00:00Z", "r10");
        // 00:00 on 1 November in New York: this month.
        request(T, "DOWNLOAD", d6, "PENDING", false, "2026-11-01T04:00:00Z", "2026-11-26T10:00:00Z", "r11");
        request(T, "PRINT", d8, "DECLINED", true, "2026-11-10T10:00:00Z", "2026-11-17T10:00:00Z", "r12");
        request(TX, "DOWNLOAD", xd, "PENDING", false, "2026-11-10T10:00:00Z", "2026-11-17T10:00:00Z", "rx");

        // ---- Level changes.
        UUID c1Level = id("level-" + LV + contract(C1));
        event(T, "USER", "dee", "DOCUMENT_SENSITIVITY_RAISED", "DOCUMENT", d2, "2026-11-02T10:00:00Z", "SUCCESS", "{\"to\":\"CONFIDENTIAL\"}");
        event(T, "USER", "dee", "RECORD_SENSITIVITY_RAISED", "RECORD_SENSITIVITY", c1Level, "2026-11-02T11:00:00Z", "SUCCESS", "{\"sourceId\":\"" + LV + "\"}");
        event(T, "SOURCE", "loans", "RECORD_SENSITIVITY_RAISED", "RECORD_SENSITIVITY", c1Level, "2026-11-03T10:00:00Z", "SUCCESS", "{\"sourceId\":\"" + LV + "\",\"setBy\":\"SOURCE\"}");
        event(T, "SOURCE", "origination", "DOCUMENT_SENSITIVITY_RAISED", "DOCUMENT", d8, "2026-11-03T11:00:00Z", "SUCCESS", "{\"setBy\":\"SOURCE\"}");
        // Part 2: a contract's first level above Standard from a delivery counts as set by a sending system (the
        // migration case); a first level at Standard, or one naming no level, changes nothing and is not counted.
        event(T, "SOURCE", "loans", "RECORD_SENSITIVITY_SET", "RECORD_SENSITIVITY", c1Level, "2026-11-03T12:00:00Z", "SUCCESS", "{\"sourceId\":\"" + LV + "\",\"level\":\"HIGHLY_CONFIDENTIAL\",\"setBy\":\"SOURCE\"}");
        event(T, "SOURCE", "origination", "RECORD_SENSITIVITY_SET", "RECORD_SENSITIVITY", UUID.randomUUID(), "2026-11-03T12:30:00Z", "SUCCESS", "{\"sourceId\":\"" + LOS + "\",\"level\":\"CONFIDENTIAL\",\"setBy\":\"SOURCE\"}");
        event(T, "SOURCE", "loans", "RECORD_SENSITIVITY_SET", "RECORD_SENSITIVITY", UUID.randomUUID(), "2026-11-03T12:40:00Z", "SUCCESS", "{\"sourceId\":\"" + LV + "\",\"level\":\"STANDARD\",\"setBy\":\"SOURCE\"}");
        event(T, "SOURCE", "loans", "RECORD_SENSITIVITY_SET", "RECORD_SENSITIVITY", UUID.randomUUID(), "2026-11-03T12:50:00Z", "SUCCESS", "{\"sourceId\":\"" + LV + "\"}");
        // Before the period (23:59 on 31 October in New York) and another tenant's: not counted in November.
        event(T, "SOURCE", "loans", "RECORD_SENSITIVITY_SET", "RECORD_SENSITIVITY", UUID.randomUUID(), "2026-11-01T03:59:00Z", "SUCCESS", "{\"sourceId\":\"" + LV + "\",\"level\":\"CONFIDENTIAL\",\"setBy\":\"SOURCE\"}");
        event(TX, "SOURCE", "other", "RECORD_SENSITIVITY_SET", "RECORD_SENSITIVITY", UUID.randomUUID(), "2026-11-03T12:00:00Z", "SUCCESS", "{\"sourceId\":\"" + XS + "\",\"level\":\"HIGHLY_CONFIDENTIAL\",\"setBy\":\"SOURCE\"}");
        event(T, "SOURCE", "loans", "RECORD_SENSITIVITY_KEPT", "RECORD_SENSITIVITY", c1Level, "2026-11-03T13:00:00Z", "SUCCESS", "{\"sourceId\":\"" + LV + "\"}");
        event(T, "USER", "priya", "DOCUMENT_SENSITIVITY_LOWERED", "DOCUMENT", d2, "2026-11-04T10:00:00Z", "SUCCESS", "{}");
        event(T, "USER", "priya", "RECORD_SENSITIVITY_LOWERED", "RECORD_SENSITIVITY", UUID.randomUUID(), "2026-11-04T11:00:00Z", "SUCCESS", "{\"sourceId\":\"" + LOS + "\"}");
        event(T, "USER", "dee", "DOCUMENT_SENSITIVITY_RAISED", "DOCUMENT", d2, "2026-11-01T03:59:00Z", "SUCCESS", "{}");
        event(TX, "USER", "dee", "DOCUMENT_SENSITIVITY_RAISED", "DOCUMENT", xd, "2026-11-02T10:00:00Z", "SUCCESS", "{}");

        // ---- Attempts to open restricted documents.
        for (int i = 0; i < 3; i++) refused(T, "bea", d6, "SDV-SEN-008", "[\"BUSINESS_USER\"]", "2026-11-06T10:0" + i + ":00Z");
        refused(T, "ann", d6, "SDV-SEN-008", "[\"BUSINESS_USER\"]", "2026-11-06T11:00:00Z");
        refused(T, "dee", d6, "SDV-SEN-008", "[\"DOCUMENT_STEWARD\"]", "2026-11-06T12:00:00Z");
        refused(T, "ole", d8, "SDV-SEN-008", null, "2026-11-06T13:00:00Z");
        refused(T, "rae", d6, "SDV-SEN-009", "[\"RESTRICTED_STEWARD\"]", "2026-11-06T14:00:00Z");
        // Part 2: only the open action counts. A page, previews, text search, a level-change request and the rest are
        // refused too, but are not attempts to open: not counted, and a person with only those is not a person here.
        for (String attempt : List.of("PAGE", "PREVIEWS", "TEXT_SEARCH", "CHANGE_LEVEL", "REQUEST_ACCESS", "PRINT", "DOWNLOAD")) {
            refused(T, "bea", d6, attempt, "SDV-SEN-008", "[\"BUSINESS_USER\"]", "2026-11-06T15:00:00Z");
            refused(T, "zed", d6, attempt, "SDV-SEN-008", "[\"BUSINESS_USER\"]", "2026-11-06T15:00:00Z");
        }
        refused(T, "zed", d8, "PAGE", "SDV-SEN-008", "[\"RESTRICTED_STEWARD\"]", "2026-11-06T15:00:00Z");
        // 00:00 on 1 December in New York (05:00 UTC, winter time): after the period.
        refused(T, "bea", d6, "SDV-SEN-008", "[\"BUSINESS_USER\"]", "2026-12-01T05:00:00Z");
        refused(TX, "xan", xd, "SDV-SEN-008", "[\"BUSINESS_USER\"]", "2026-11-06T10:00:00Z");

        // ---- All sending systems, November.
        SensitivityReportService.SensitivityReport all = service.report(T, "2026-11-01", "2026-11-30", null);
        assertEquals("TENANT", all.timeZoneSource());
        assertEquals(new SensitivityReportService.Documents(2, 1, 4, 7, 3, 1), all.documents(),
                "Standard d8 and d9; Confidential d7 (its submission's record); Highly Confidential d1, d2, d5 (contract C1) and d6; "
                        + "deleted and failed ones left out; one active hold on a stored document");
        assertEquals(List.of(
                new SensitivityReportService.RequestRow("DOWNLOAD", 3, 1, 1, 1),
                new SensitivityReportService.RequestRow("PRINT", 0, 1, 1, 1),
                new SensitivityReportService.RequestRow("LOWER_LEVEL", 0, 0, 0, 1),
                new SensitivityReportService.RequestRow("LOWER_RECORD_LEVEL", 0, 0, 0, 1)), all.requests());
        assertEquals(new SensitivityReportRepository.LevelChanges(2, 4, 2), all.levelChanges(),
                "sending systems: a record raise, a duplicate raise, and two first levels above Standard (part 2)");
        assertEquals(List.of(
                new SensitivityReportService.AttemptRow(List.of("BUSINESS_USER"), 4, 2),
                new SensitivityReportService.AttemptRow(List.of(), 1, 1),
                new SensitivityReportService.AttemptRow(List.of("DOCUMENT_STEWARD"), 1, 1)), all.attempts());
        assertEquals("Contract number", all.primaryIdentifierLabel());

        // ---- One sending system at a time.
        SensitivityReportService.SensitivityReport lv = service.report(T, "2026-11-01", "2026-11-30", LV.toString());
        assertEquals(new SensitivityReportService.Documents(1, 1, 4, 6, 3, 1), lv.documents());
        assertEquals(new SensitivityReportService.RequestRow("PRINT", 0, 0, 1, 1), lv.requests().get(1));
        assertEquals(new SensitivityReportService.RequestRow("LOWER_RECORD_LEVEL", 0, 0, 0, 1), lv.requests().get(3));
        assertEquals(new SensitivityReportRepository.LevelChanges(2, 2, 1), lv.levelChanges());
        assertEquals(2, lv.attempts().size());
        SensitivityReportService.SensitivityReport los = service.report(T, "2026-11-01", "2026-11-30", LOS.toString());
        assertEquals(new SensitivityReportService.Documents(1, 0, 0, 1, 0, 0), los.documents());
        assertEquals(new SensitivityReportService.RequestRow("PRINT", 0, 1, 0, 0), los.requests().get(1));
        assertEquals(new SensitivityReportService.RequestRow("DOWNLOAD", 0, 0, 0, 0), los.requests().get(0));
        assertEquals(new SensitivityReportRepository.LevelChanges(0, 2, 1), los.levelChanges());
        assertEquals(List.of(new SensitivityReportService.AttemptRow(List.of(), 1, 1)), los.attempts());

        // ---- The period boundary: 31 October in New York holds only what happened before 04:00 UTC on 1 November.
        SensitivityReportService.SensitivityReport october = service.report(T, "2026-10-31", "2026-10-31", null);
        assertEquals(1, october.requests().get(0).waiting(), "r10 only");
        assertEquals(new SensitivityReportRepository.LevelChanges(1, 1, 0), october.levelChanges(),
                "the first level set at 23:59 on 31 October in New York is October's");
        assertTrue(october.attempts().isEmpty());
        assertEquals(all.documents(), october.documents(), "documents are counted as they are now, whatever the period");

        // ---- The CSV and its audit.
        String csv = new String(service.export(T, "priya", "corr-sens", "2026-11-01", "2026-11-30", null).csv(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"Documents by level\",\"Highly Confidential\",\"Documents stored\",4,\"2026-11-21\",\"2026-11-21\",\"America/New_York\",\"All\""), csv);
        assertTrue(csv.contains("\"Requests\",\"Download\",\"Approved\",3,\"2026-11-01\",\"2026-11-30\""), csv);
        assertFalse(csv.contains("secret-name") || csv.contains(C1) || csv.contains("bea"), "counts only: no file, contract or person");
        assertEquals(1, count("select count(*) from audit_events where tenant_id='" + T + "' and action='SENSITIVITY_REPORT_EXPORTED' "
                + "and actor_id='priya' and safe_details->>'rowCount'='30'"));
        assertEquals(0, count("select count(*) from audit_events where tenant_id='" + TX + "' and action='SENSITIVITY_REPORT_EXPORTED'"));
    }
}
