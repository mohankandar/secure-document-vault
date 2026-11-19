package com.sdv.platform.application.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.AuditQueryPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.AvailableDocumentSnapshot;
import com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionStatus;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantStatus;
import com.sdv.platform.persistence.operations.JdbcOperationalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0020 item 1: a record open names the record's submission (an internal reference, never a readable value)
 * in its safe details, and the audit list looks up the record number from it within the tenant.
 */
class RecordOpenAuditTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SUB = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final String NUMBER = "300000000076";

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
    private final MetadataFieldDefinitionRepository definitions = mock(MetadataFieldDefinitionRepository.class);
    private final BusinessRecordRepository records = mock(BusinessRecordRepository.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final BusinessExperienceService service = new BusinessExperienceService(
            tenants, sources, definitions, records, audit, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        IngestionSource source = mock(IngestionSource.class);
        when(source.sourceId()).thenReturn(SOURCE);
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(source));
        when(records.findMetadataSnapshots(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new MetadataSnapshot(SUB, "contractNumber", "Contract Number", NUMBER, NUMBER, true),
                new MetadataSnapshot(SUB, "lastName", "Last Name", "Patel", "PATEL", false)));
        when(records.findSubmissionIdsByRecordIdentity(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(SUB));
        when(records.findSubmissionsByIds(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new Submission(SUB, TENANT, SOURCE, "src-1", 1, "sha", SubmissionStatus.COMPLETE, NOW.minusSeconds(60), NOW, null)));
        when(records.findAvailableDocuments(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new AvailableDocumentSnapshot(UUID.randomUUID(), SUB, "d1", "a.pdf", NOW, NOW, null, null, false)));
    }

    private static String recordRef() {
        return new BusinessRecordRefCodec().encode(new BusinessRecordRefCodec.RecordIdentity(
                SOURCE, List.of(new BusinessRecordRefCodec.IdentifierPart("contractNumber", NUMBER)), null));
    }

    @Test
    void aRecordOpenNamesItsSubmissionAndNoReadableValue() throws Exception {
        String ref = recordRef();
        service.record(TENANT, ref, "maya", "corr");

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        AuditEvent opened = event.getValue();
        assertEquals("BUSINESS_RECORD_OPENED", opened.action());
        assertEquals("BUSINESS_RECORD", opened.targetType());
        assertEquals(TENANT, opened.tenantId());
        assertNotEquals(ref, opened.targetId(), "the target stays a one-way hash of the reference");
        assertFalse(opened.targetId().contains(NUMBER));

        JsonNode details = new ObjectMapper().readTree(opened.safeDetailsJson());
        assertEquals(SUB.toString(), details.get("submissionId").asText());
        assertEquals(SOURCE.toString(), details.get("sourceId").asText());
        assertEquals(1, details.get("submissionCount").asInt());
        assertFalse(opened.safeDetailsJson().contains(NUMBER), "no record number in the safe details");
        assertFalse(opened.safeDetailsJson().contains("Patel"), "no readable value in the safe details");
        assertFalse(opened.safeDetailsJson().contains(ref), "not the readable record reference either");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAuditListLooksUpARecordOpensNumberFromItsSubmissionWithinTheTenant() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        new JdbcOperationalRepository(jdbc).findRows(TENANT, new AuditQueryPort.Filter(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null), 51);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        String s = sql.getValue().replaceAll("\\s+", " ");

        // Only record opens offer a submission, read from the safe details.
        assertTrue(s.contains("case when target_type='BUSINESS_RECORD' then safe_details->>'submissionId' end as record_submission_id"), s);
        // The lookup stays in the event's own tenant, and only a UUID-shaped value is ever cast.
        assertTrue(s.contains("where v.tenant_id=a.tenant_id"), s);
        assertTrue(s.contains("v.submission_id = coalesce(d.submission_id, case when a.record_submission_id ~ :uuidPattern then a.record_submission_id::uuid end)"), s);
        assertTrue(s.contains("where tenant_id=:tenantId"), s);
        assertEquals(TENANT, params.getValue().getValue("tenantId"));
    }
}
