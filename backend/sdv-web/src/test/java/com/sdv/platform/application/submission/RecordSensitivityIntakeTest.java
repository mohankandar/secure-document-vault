package com.sdv.platform.application.submission;

import com.sdv.platform.application.business.RecordKey;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.RecordSensitivityRepository;
import com.sdv.platform.application.port.SubmissionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.document.BusinessMetadataValue;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.source.IngestionSourceAuthType;
import com.sdv.platform.domain.source.IngestionSourceStatus;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionExpectedDocument;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantGuardrails;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0024, part 2: the submission sets its record's level. A delivery may set the first level or raise it, never
 * lower it; a lower or equal value is ignored and audited. The record is the same grouping record detail uses.
 */
class RecordSensitivityIntakeTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final String CONTRACT_KEY = RecordKey.ofIdentifiers(List.of(new RecordKey.Part("contractNumber", "937552212763")));

    private final MetadataFieldDefinitionRepository definitions = mock(MetadataFieldDefinitionRepository.class);
    private final MemorySubmissions submissions = new MemorySubmissions();
    private final MemoryRecordLevels levels = new MemoryRecordLevels();
    private final List<AuditEvent> audit = new ArrayList<>();
    private SubmissionService service;

    @BeforeEach
    void setUp() {
        TenantRepository tenants = mock(TenantRepository.class);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(
                new Tenant(TENANT, "acme", "Acme", TenantStatus.ACTIVE, TenantGuardrails.defaults(), NOW, NOW)));
        IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(new IngestionSource(SOURCE, TENANT, "loans", "Loans",
                IngestionSourceStatus.ACTIVE, IngestionSourceAuthType.JWT_BEARER, null, null, NOW, NOW)));
        when(definitions.findCurrent(TENANT, SOURCE)).thenReturn(List.of(
                field("contractNumber", true, false, MetadataNormalization.DIGITS_ONLY),
                field("lastName", false, false, MetadataNormalization.TRIM),
                field("memberId", true, false, MetadataNormalization.TRIM)));
        service = new SubmissionService(tenants, sources, definitions, submissions,
                mock(DocumentIntakeRepository.class), levels, audit::add, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MetadataFieldDefinition field(String key, boolean identifier, boolean required, MetadataNormalization normalization) {
        return new MetadataFieldDefinition(UUID.randomUUID(), TENANT, SOURCE, key, 1, key, MetadataFieldType.TEXT,
                required, identifier, true, normalization, MetadataSearchMode.EXACT, "{}", true, NOW, null, NOW);
    }

    private SubmissionDetails deliver(String id, Map<String, String> metadata, String recordLevel) {
        return service.create(TENANT, SOURCE, new CreateSubmissionCommand(id, 1, List.of(), metadata, recordLevel),
                "producer", "corr-" + id);
    }

    private List<String> recordAudit() {
        return audit.stream().map(AuditEvent::action).filter(action -> action.startsWith("RECORD_SENSITIVITY")).toList();
    }

    @Test
    void theFirstLevelIsSetAndAuditedWithoutCopyingAnythingOntoDocuments() {
        SubmissionDetails created = deliver("s1", Map.of("contractNumber", "9375-5221-2763"), "CONFIDENTIAL");

        assertEquals(SensitivityLevel.CONFIDENTIAL, levels.levelOf(CONTRACT_KEY));
        MemoryRecordLevels.Row row = levels.rows.get(CONTRACT_KEY);
        assertEquals("SOURCE", row.setBy);
        assertNull(row.reason);
        AuditEvent set = audit.stream().filter(e -> e.action().equals("RECORD_SENSITIVITY_SET")).findFirst().orElseThrow();
        assertEquals("RECORD_SENSITIVITY", set.targetType());
        assertEquals(row.id.toString(), set.targetId());
        assertEquals("SOURCE", set.actorType());
        assertTrue(set.safeDetailsJson().contains("\"level\":\"CONFIDENTIAL\""));
        assertTrue(set.safeDetailsJson().contains("\"submissionId\":\"" + created.submission().submissionId() + "\""));
        assertFalse(set.safeDetailsJson().contains("9375"), "no identifier value goes into the audit trail");
    }

    @Test
    void aLaterDeliveryForTheSameRecordRaisesTheLevel() {
        deliver("s1", Map.of("contractNumber", "9375-5221-2763"), "CONFIDENTIAL");
        // Same contract written differently: the same record after normalization.
        deliver("s2", Map.of("contractNumber", "937552212763", "lastName", "Patel"), "HIGHLY_CONFIDENTIAL");

        assertEquals(1, levels.rows.size());
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, levels.levelOf(CONTRACT_KEY));
        assertEquals(List.of("RECORD_SENSITIVITY_SET", "RECORD_SENSITIVITY_RAISED"), recordAudit());
        AuditEvent raised = audit.stream().filter(e -> e.action().equals("RECORD_SENSITIVITY_RAISED")).findFirst().orElseThrow();
        assertTrue(raised.safeDetailsJson().contains("\"from\":\"CONFIDENTIAL\",\"to\":\"HIGHLY_CONFIDENTIAL\""));
        assertEquals(1, levels.locks, "the raise was decided under the row lock");
    }

    @Test
    void aLowerOrEqualLevelIsIgnoredAndAuditedNotRefused() {
        deliver("s1", Map.of("contractNumber", "937552212763"), "HIGHLY_CONFIDENTIAL");
        SubmissionDetails lower = deliver("s2", Map.of("contractNumber", "937552212763"), "STANDARD");
        SubmissionDetails equal = deliver("s3", Map.of("contractNumber", "937552212763"), "HIGHLY_CONFIDENTIAL");

        assertFalse(lower.idempotentReplay());
        assertFalse(equal.idempotentReplay());
        assertEquals(SensitivityLevel.HIGHLY_CONFIDENTIAL, levels.levelOf(CONTRACT_KEY));
        assertEquals(List.of("RECORD_SENSITIVITY_SET", "RECORD_SENSITIVITY_KEPT", "RECORD_SENSITIVITY_KEPT"), recordAudit());
        List<AuditEvent> kept = audit.stream().filter(e -> e.action().equals("RECORD_SENSITIVITY_KEPT")).toList();
        assertTrue(kept.get(0).safeDetailsJson().contains("\"requested\":\"STANDARD\""));
        assertTrue(kept.get(1).safeDetailsJson().contains("\"requested\":\"HIGHLY_CONFIDENTIAL\""));
        kept.forEach(e -> assertTrue(e.safeDetailsJson().contains("\"note\":\"record level kept at HIGHLY_CONFIDENTIAL\"")));
        assertEquals(0, levels.raises);
    }

    @Test
    void aDeliveryWithoutALevelLeavesTheRecordAlone() {
        deliver("s1", Map.of("contractNumber", "937552212763"), "CONFIDENTIAL");
        deliver("s2", Map.of("contractNumber", "937552212763"), null);
        assertEquals(SensitivityLevel.CONFIDENTIAL, levels.levelOf(CONTRACT_KEY));
        assertEquals(List.of("RECORD_SENSITIVITY_SET"), recordAudit());
    }

    @Test
    void aSubmissionWithoutIdentifierValuesIsARecordOnItsOwn() {
        SubmissionDetails first = deliver("s1", Map.of("lastName", "Patel"), "CONFIDENTIAL");
        SubmissionDetails second = deliver("s2", Map.of("lastName", "Patel"), "STANDARD");

        String firstKey = RecordKey.ofSubmission(first.submission().submissionId());
        String secondKey = RecordKey.ofSubmission(second.submission().submissionId());
        assertEquals(SensitivityLevel.CONFIDENTIAL, levels.levelOf(firstKey));
        assertEquals(SensitivityLevel.STANDARD, levels.levelOf(secondKey), "a separate record: not kept, but set");
        assertEquals(List.of("RECORD_SENSITIVITY_SET", "RECORD_SENSITIVITY_SET"), recordAudit());
    }

    @Test
    void allIdentifierValuesTogetherMakeTheRecord() {
        deliver("s1", Map.of("contractNumber", "937552212763", "memberId", "M-1"), "CONFIDENTIAL");
        deliver("s2", Map.of("contractNumber", "937552212763"), "STANDARD");
        assertEquals(SensitivityLevel.CONFIDENTIAL, levels.levelOf(RecordKey.ofIdentifiers(List.of(
                new RecordKey.Part("contractNumber", "937552212763"), new RecordKey.Part("memberId", "M-1")))));
        assertEquals(SensitivityLevel.STANDARD, levels.levelOf(CONTRACT_KEY),
                "contract alone is a different record, as in findSubmissionIdsByRecordIdentity");
    }

    @Test
    void unknownOrWronglyCasedValuesAreRefusedBeforeAnythingIsStored() {
        for (String bad : List.of("confidential", "Confidential", "SECRET", "", " CONFIDENTIAL", "1", "null")) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> deliver("s-" + bad, Map.of("contractNumber", "937552212763"), bad), bad);
            assertTrue(ex.getMessage().startsWith("recordSensitivityLevel must be one of"), ex.getMessage());
        }
        assertTrue(submissions.values.isEmpty());
        assertTrue(levels.rows.isEmpty());
        assertTrue(audit.isEmpty());
        verifyNoInteractions(definitions);
    }

    @Test
    void aRetryMustRepeatTheRecordLevelOrItIsADifferentRequest() {
        SubmissionDetails first = deliver("s1", Map.of("contractNumber", "937552212763"), "CONFIDENTIAL");
        SubmissionDetails replay = deliver("s1", Map.of("contractNumber", "937552212763"), "CONFIDENTIAL");
        assertTrue(replay.idempotentReplay());
        assertEquals(first.submission().submissionId(), replay.submission().submissionId());
        var higher = assertThrows(com.sdv.platform.domain.error.CodedRefusal.Conflict.class,
                () -> deliver("s1", Map.of("contractNumber", "937552212763"), "HIGHLY_CONFIDENTIAL"));
        var none = assertThrows(com.sdv.platform.domain.error.CodedRefusal.Conflict.class,
                () -> deliver("s1", Map.of("contractNumber", "937552212763"), null));
        for (var refusal : List.of(higher, none)) {
            assertEquals("SDV-SEN-004", refusal.errorCode().code());
            assertEquals("sourceSubmissionId already exists with recordSensitivityLevel CONFIDENTIAL; send the same "
                    + "value when retrying.", refusal.getMessage());
        }
        assertEquals(SensitivityLevel.CONFIDENTIAL, levels.levelOf(CONTRACT_KEY));
        assertEquals(List.of("RECORD_SENSITIVITY_SET"), recordAudit());
    }

    @Test
    void aRetryThatAddsALevelOrChangesSomethingElseIsTold() {
        deliver("s1", Map.of("contractNumber", "937552212763"), null);
        var added = assertThrows(com.sdv.platform.domain.error.CodedRefusal.Conflict.class,
                () -> deliver("s1", Map.of("contractNumber", "937552212763"), "CONFIDENTIAL"));
        assertEquals("SDV-SEN-004", added.errorCode().code());
        assertEquals("sourceSubmissionId already exists with no recordSensitivityLevel; leave it out when retrying.",
                added.getMessage());
        // Another difference as well: not a sensitivity code. Task 0039: it is SDV-INT-011 now (it was the generic
        // conflict, so this assertion changed).
        com.sdv.platform.domain.error.CodedRefusal.Conflict other = assertThrows(com.sdv.platform.domain.error.CodedRefusal.Conflict.class,
                () -> deliver("s1", Map.of("contractNumber", "111122223333"), "CONFIDENTIAL"));
        assertEquals("SDV-INT-011", other.errorCode().code());
        assertEquals("sourceSubmissionId already exists with a different submission create request.", other.getMessage());
        assertTrue(levels.rows.isEmpty());
    }

    @Test
    void aSubmissionWithoutALevelKeepsTheFingerprintItHadBeforeTask0024() {
        // The same request created without a level, then retried without one, is still an exact replay.
        deliver("old", Map.of("contractNumber", "937552212763"), null);
        assertTrue(deliver("old", Map.of("contractNumber", "937552212763"), null).idempotentReplay());
        assertTrue(levels.rows.isEmpty());
    }

    @Test
    void overHttpAnUnknownRecordLevelIs400WithTheMessage() throws Exception {
        com.sdv.platform.security.auth.SecurityContextFacade security = mock(com.sdv.platform.security.auth.SecurityContextFacade.class);
        when(security.requireIngestionSourcePrincipal())
                .thenReturn(new com.sdv.platform.security.auth.IngestionSourcePrincipal(TENANT, SOURCE, "producer"));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new com.sdv.platform.web.ingestion.SubmissionController(security, service))
                .setControllerAdvice(new com.sdv.platform.web.foundation.FoundationExceptionHandler())
                .build();
        String base = "{\"sourceSubmissionId\":\"h1\",\"expectedDocumentCount\":1,\"metadata\":{\"contractNumber\":\"937552212763\"}";
        for (String bad : List.of("\"confidential\"", "\"Confidential\"", "\"SECRET\"", "\"\"", "1", "null", "{}", "[\"CONFIDENTIAL\"]")) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ingestion/submissions")
                            .contentType("application/json").content(base + ",\"recordSensitivityLevel\":" + bad + "}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("SDV-SEN-002"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value(
                            "recordSensitivityLevel must be one of STANDARD, CONFIDENTIAL, HIGHLY_CONFIDENTIAL "
                                    + "(exactly, in capitals); leave it out for no record level."));
        }
        assertTrue(submissions.values.isEmpty());
        assertTrue(levels.rows.isEmpty());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ingestion/submissions")
                        .contentType("application/json").content(base + ",\"recordSensitivityLevel\":\"CONFIDENTIAL\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        assertEquals(SensitivityLevel.CONFIDENTIAL, levels.levelOf(CONTRACT_KEY));
    }

    /** Behaves like the SQL: insert on conflict do nothing, then select ... for update. */
    private static final class MemoryRecordLevels implements RecordSensitivityRepository {
        static final class Row {
            UUID id; SensitivityLevel level; String reason; String setBy;
        }
        final Map<String, Row> rows = new LinkedHashMap<>();
        int locks;
        int raises;

        SensitivityLevel levelOf(String key) {
            Row row = rows.get(key);
            return row == null ? null : row.level;
        }

        @Override public boolean insertIfAbsent(NewRecordLevel level) {
            assertEquals(TENANT, level.tenantId());
            assertEquals(SOURCE, level.sourceId());
            if (rows.containsKey(level.recordKey())) return false;
            Row row = new Row();
            row.id = level.recordLevelId(); row.level = level.level(); row.reason = level.reason(); row.setBy = level.setBy();
            rows.put(level.recordKey(), row);
            return true;
        }
        @Override public Optional<StoredRecordLevel> lockByKey(UUID tenantId, UUID sourceId, String recordKey) {
            locks++;
            Row row = rows.get(recordKey);
            return row == null ? Optional.empty() : Optional.of(new StoredRecordLevel(row.id, row.level));
        }
        @Override public void raise(UUID tenantId, UUID recordLevelId, SensitivityLevel level, String setBy, Instant updatedAt) {
            raises++;
            Row row = rows.values().stream().filter(r -> r.id.equals(recordLevelId)).findFirst().orElseThrow();
            assertTrue(level.compareTo(row.level) > 0, "only ever raised");
            row.level = level; row.setBy = setBy;
        }
        @Override public long countForSource(UUID tenantId, UUID sourceId) { return rows.size(); }
    }

    private static final class MemorySubmissions implements SubmissionRepository {
        final Map<String, Submission> values = new LinkedHashMap<>();
        final Map<UUID, List<BusinessMetadataValue>> metadata = new LinkedHashMap<>();

        @Override public InsertResult insertIfAbsent(Submission candidate) {
            Submission existing = values.putIfAbsent(candidate.sourceSubmissionId(), candidate);
            return existing == null ? new InsertResult(candidate, true) : new InsertResult(existing, false);
        }
        @Override public Optional<Submission> findById(UUID tenantId, UUID sourceId, UUID submissionId) {
            return values.values().stream().filter(v -> v.submissionId().equals(submissionId)).findFirst();
        }
        @Override public Optional<Submission> findBySourceIdentity(UUID tenantId, UUID sourceId, String sourceSubmissionId) {
            return Optional.ofNullable(values.get(sourceSubmissionId));
        }
        @Override public List<SubmissionExpectedDocument> findExpectedDocuments(UUID tenantId, UUID submissionId) { return List.of(); }
        @Override public List<BusinessMetadataValue> findBusinessMetadata(UUID tenantId, UUID sourceId, UUID submissionId) {
            return metadata.getOrDefault(submissionId, List.of());
        }
        @Override public void insertExpectedDocuments(List<SubmissionExpectedDocument> expectedDocuments) { }
        @Override public void insertBusinessMetadata(List<BusinessMetadataValue> values) {
            if (!values.isEmpty()) metadata.put(values.getFirst().submissionId(), List.copyOf(values));
        }
    }
}
