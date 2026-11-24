package com.sdv.platform.application.business;

import com.sdv.platform.application.operations.ProblemRecordResolver;
import com.sdv.platform.application.port.AuditQueryPort;
import com.sdv.platform.application.reports.StorageReportService;
import com.sdv.platform.application.restrictions.RestrictionsService;
import com.sdv.platform.persistence.approvals.JdbcAccessRequestRepository;
import com.sdv.platform.persistence.business.JdbcBusinessRecordRepository;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.persistence.legalhold.JdbcLegalHoldRepository;
import com.sdv.platform.persistence.operations.JdbcOperationalRepository;
import com.sdv.platform.persistence.quarantine.JdbcQuarantineRepository;
import com.sdv.platform.persistence.reports.JdbcIntakeReportRepository;
import com.sdv.platform.persistence.reports.JdbcSensitivityReportRepository;
import com.sdv.platform.persistence.restrictions.JdbcRestrictionsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Task 0056: every admin read names a record by the same primary identifier (the one rule, PrimaryIdentifierRule),
 * for a sending system whose identifier field is not first in display order: a non-identifier field (branch) comes
 * first, and of its two identifiers policyNumber is shown before accountRef, the reverse of their field-key order.
 * A blank first identifier gives way to the next one, on every page.
 * <p>
 * Runs only when SDV_IT_JDBC_URL is set to the scratch database sdv_schema_test; never against the sdv database.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class PrimaryIdentifierPostgresTest {
    private final UUID t = UUID.randomUUID();
    private final UUID s = UUID.randomUUID();
    private final UUID sub1 = UUID.randomUUID();
    private final UUID sub2 = UUID.randomUUID();
    private final UUID doc1 = UUID.randomUUID();
    private final UUID doc2 = UUID.randomUUID();

    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD")));

    private void sql(String statement) {
        jdbc.getJdbcTemplate().execute(statement);
    }

    private UUID field(String key, String label, boolean identifier, int position) {
        UUID definition = UUID.randomUUID();
        sql("insert into metadata_field_definitions(definition_id, tenant_id, source_id, field_key, version, display_label, field_type, "
                + "required, business_identifier, searchable, normalization, search_mode) values ('" + definition + "', '" + t + "', '" + s
                + "', '" + key + "', 1, '" + label + "', 'TEXT', false, " + identifier + ", true, 'NONE', 'EXACT')");
        sql("insert into metadata_field_display_order(tenant_id, source_id, field_key, display_position) values ('" + t + "', '" + s
                + "', '" + key + "', " + position + ")");
        return definition;
    }

    private void value(UUID submission, UUID definition, String key, String value) {
        sql("insert into business_metadata_values(metadata_value_id, tenant_id, source_id, submission_id, definition_id, field_key, "
                + "definition_version, stored_value, normalized_value) values ('" + UUID.randomUUID() + "', '" + t + "', '" + s + "', '"
                + submission + "', '" + definition + "', '" + key + "', 1, '" + value + "', '" + value.strip() + "')");
    }

    private void document(UUID submission, UUID document) {
        sql("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, "
                + "content_sha256, content_size_bytes, status, available_at, page_count, retention_days_snapshot, expires_at) values ('"
                + document + "', '" + t + "', '" + submission + "', '" + s + "', '" + document + "', 'secret.pdf', 'application/pdf', "
                + "repeat('a', 64), 100, 'AVAILABLE', now(), 2, 30, now() + interval '30 days')");
        sql("insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, action, target_type, target_id, occurred_at, outcome, "
                + "safe_details) values ('" + UUID.randomUUID() + "', '" + t + "', 'USER', 'ada', 'DOCUMENT_VIEWED', 'DOCUMENT', '" + document
                + "', now(), 'SUCCESS', '{}'::jsonb)");
    }

    @Test
    void everyAdminReadNamesTheRecordByTheSamePrimaryIdentifier() {
        sql("insert into tenants(tenant_id, tenant_key, display_name, status) values ('" + t + "', 'pid-" + t + "', 'Primary identifier 0056', 'ACTIVE')");
        sql("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values ('" + s + "', '" + t
                + "', 'policies', 'Policies', 'ACTIVE', 'JWT_BEARER')");
        UUID branch = field("branch", "Branch", false, 0);
        UUID policy = field("policyNumber", "Policy number", true, 1);
        UUID account = field("accountRef", "Account reference", true, 2);
        for (UUID sub : List.of(sub1, sub2)) {
            sql("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) values ('"
                    + sub + "', '" + t + "', '" + s + "', '" + sub + "', 1, 'COMPLETE')");
        }
        value(sub1, branch, "branch", "North");
        value(sub1, policy, "policyNumber", "P-100");
        value(sub1, account, "accountRef", "A-900");
        value(sub2, policy, "policyNumber", "  ");
        value(sub2, account, "accountRef", "A-901");
        document(sub1, doc1);
        document(sub2, doc2);
        String key = RecordKey.ofIdentifiers(List.of(new RecordKey.Part("accountRef", "A-900"), new RecordKey.Part("policyNumber", "P-100")));
        sql("insert into record_sensitivity_levels(record_level_id, tenant_id, source_id, record_key, record_key_sha256, sensitivity_level, "
                + "reason, set_by, set_at, updated_at) values (gen_random_uuid(), '" + t + "', '" + s + "', '" + key
                + "', encode(sha256(convert_to('" + key + "', 'UTF8')), 'hex'), 'CONFIDENTIAL', 'Reason', 'ada', now(), now())");

        everyPageNames("P-100", "A-900", "Policy number");

        // The Business admin puts accountRef before policyNumber: every page follows.
        sql("update metadata_field_display_order set display_position=9 where tenant_id='" + t + "' and field_key='policyNumber'");
        sql("update metadata_field_display_order set display_position=1 where tenant_id='" + t + "' and field_key='accountRef'");
        sql("update metadata_field_display_order set display_position=2 where tenant_id='" + t + "' and field_key='policyNumber'");
        everyPageNames("A-900", "P-100", "Account reference");
    }

    /** sub1's record is named {@code primary} (label {@code label}) on every admin read; {@code other} names it nowhere. */
    private void everyPageNames(String primary, String other, String label) {
        // Approvals
        JdbcAccessRequestRepository approvals = new JdbcAccessRequestRepository(jdbc);
        assertEquals(primary, approvals.findPrimaryIdentifier(t, sub1).orElseThrow().value());
        assertEquals(label, approvals.findPrimaryIdentifier(t, sub1).orElseThrow().label());
        Map<UUID, String> facts = approvals.findDocumentFacts(t, List.of(doc1, doc2)).stream()
                .collect(Collectors.toMap(f -> f.documentId(), f -> f.primaryIdentifier()));
        assertEquals(Map.of(doc1, primary, doc2, "A-901"), facts, "a blank policy number gives way to the next identifier");
        // Legal holds: found by the primary identifier only, never by the other one
        JdbcLegalHoldRepository holds = new JdbcLegalHoldRepository(jdbc);
        assertEquals(primary, holds.lockDocument(t, doc1).orElseThrow().primaryIdentifier());
        assertEquals(label, holds.lockDocument(t, doc1).orElseThrow().primaryIdentifierLabel());
        assertEquals(List.of(doc1), holds.findByPrimaryIdentifier(t, primary, 10).stream().map(f -> f.documentId()).toList());
        assertTrue(holds.findByPrimaryIdentifier(t, other, 10).isEmpty());
        // Quarantine
        JdbcQuarantineRepository quarantine = new JdbcQuarantineRepository(jdbc);
        assertEquals(primary, quarantine.lockDocument(t, doc1).orElseThrow().primaryIdentifier());
        assertEquals("A-901", quarantine.lockDocument(t, doc2).orElseThrow().primaryIdentifier());
        // Reports and steward notices
        var intake = new JdbcIntakeReportRepository(jdbc).findPrimaryIdentifiers(t, List.of(sub1, sub2));
        assertEquals(primary, intake.get(sub1).value());
        assertEquals("A-901", intake.get(sub2).value());
        assertEquals(label, new JdbcSensitivityReportRepository(jdbc).findPrimaryIdentifierLabel(t, s).orElseThrow());
        // Audit list record number, a blank value skipped as everywhere else
        Map<String, String> audit = new JdbcOperationalRepository(jdbc).findRows(t, new AuditQueryPort.Filter(
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null), 51).stream()
                .collect(Collectors.toMap(r -> r.event().targetId(), r -> r.recordNumber()));
        assertEquals(Map.of(doc1.toString(), primary, doc2.toString(), "A-901"), audit);
        // Problems
        JdbcFoundationRepository foundation = new JdbcFoundationRepository(jdbc);
        var problems = new ProblemRecordResolver(new JdbcBusinessRecordRepository(jdbc), foundation, foundation)
                .resolve(t, Map.of(sub1, s, sub2, s));
        assertEquals(primary, problems.get(sub1).recordNumber());
        assertEquals(label, problems.get(sub1).recordNumberLabel());
        assertEquals("A-901", problems.get(sub2).recordNumber());
        // Restrictions: the record key holds both identifiers; the primary one names it
        var restrictions = new RestrictionsService(new JdbcRestrictionsRepository(jdbc), mock(StorageReportService.class));
        RestrictionsService.RestrictedRecord record = restrictions.records(t, null).items().getFirst();
        assertEquals(primary, record.identifier());
        assertEquals(label, record.identifierLabel());
        assertEquals(primary, new JdbcRestrictionsRepository(jdbc).findSubmissionIdentifiers(t, List.of(sub1)).get(sub1).value());
        assertEquals("A-901", new JdbcRestrictionsRepository(jdbc).findSubmissionIdentifiers(t, List.of(sub2)).get(sub2).value());
    }
}
