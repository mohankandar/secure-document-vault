package com.sdv.platform.application.submission;

import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.SubmissionRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.document.BusinessMetadataValue;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.source.IngestionSourceAuthType;
import com.sdv.platform.domain.source.IngestionSourceStatus;
import com.sdv.platform.domain.source.SourceLimits;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionExpectedDocument;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantGuardrails;
import com.sdv.platform.domain.tenant.TenantStatus;
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

class SubmissionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

    @Test
    void exactRetryReturnsOriginalSubmissionEvenAfterMetadataConfigurationChanges() {
        UUID tenantId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        Tenant tenant = tenant(tenantId);
        IngestionSource source = source(tenantId, sourceId);
        MutableDefinitionRepository definitions = new MutableDefinitionRepository(
                List.of(contractDefinition(tenantId, sourceId, 1, true)));
        MemorySubmissionRepository submissions = new MemorySubmissionRepository();
        List<AuditEvent> audit = new ArrayList<>();

        SubmissionService service = new SubmissionService(
                id -> id.equals(tenantId) ? Optional.of(tenant) : Optional.empty(),
                new OneSourceRepository(source), definitions, submissions, audit::add,
                Clock.fixed(NOW, ZoneOffset.UTC));

        CreateSubmissionCommand command = command("source-submission-1", "9375-5221-2763");
        SubmissionDetails created = service.create(tenantId, sourceId, command, "producer", "corr-1");
        assertFalse(created.idempotentReplay());
        assertEquals(1, created.metadataValues().getFirst().definitionVersion());
        assertEquals("937552212763", created.metadataValues().getFirst().normalizedValue());

        // A later configuration revision must not make an exact retry revalidate/rewrite history.
        definitions.values = List.of();
        SubmissionDetails replay = service.create(tenantId, sourceId, command, "producer", "corr-2");
        assertTrue(replay.idempotentReplay());
        assertEquals(created.submission().submissionId(), replay.submission().submissionId());
        assertEquals(1, replay.metadataValues().getFirst().definitionVersion());
        assertEquals(1, audit.size(), "An idempotent replay must not append a second SUBMISSION_CREATED event.");
    }

    @Test
    void sameSourceSubmissionIdWithDifferentCreateRequestFailsClosed() {
        UUID tenantId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        MemorySubmissionRepository submissions = new MemorySubmissionRepository();
        SubmissionService service = new SubmissionService(
                id -> Optional.of(tenant(tenantId)),
                new OneSourceRepository(source(tenantId, sourceId)),
                new MutableDefinitionRepository(List.of(contractDefinition(tenantId, sourceId, 1, true))),
                submissions, event -> { }, Clock.fixed(NOW, ZoneOffset.UTC));

        service.create(tenantId, sourceId, command("same-id", "9375-5221-2763"), "producer", "corr-1");
        CreateSubmissionCommand changed = new CreateSubmissionCommand(
                "same-id", 3, List.of("doc-a"), Map.of("contractNumber", "9375-5221-2763"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.create(tenantId, sourceId, changed, "producer", "corr-2"));
        assertTrue(ex.getMessage().contains("different submission create request"));
        assertEquals(1, submissions.values.size());
    }

    @Test
    void firstCreateUsesCurrentMetadataRulesAndPinsExactDefinitionVersion() {
        UUID tenantId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        MutableDefinitionRepository definitions = new MutableDefinitionRepository(
                List.of(contractDefinition(tenantId, sourceId, 4, true)));
        MemorySubmissionRepository submissions = new MemorySubmissionRepository();
        SubmissionService service = new SubmissionService(
                id -> Optional.of(tenant(tenantId)),
                new OneSourceRepository(source(tenantId, sourceId)), definitions,
                submissions, event -> { }, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(IllegalArgumentException.class, () -> service.create(
                tenantId, sourceId,
                new CreateSubmissionCommand("bad", 1, List.of(), Map.of("contractNumber", "123")),
                "producer", "corr"));

        SubmissionDetails good = service.create(
                tenantId, sourceId, command("good", "9375-5221-2763"), "producer", "corr");
        BusinessMetadataValue value = good.metadataValues().getFirst();
        assertEquals(4, value.definitionVersion());
        assertEquals(definitions.values.getFirst().definitionId(), value.definitionId());
    }

    private static CreateSubmissionCommand command(String sourceSubmissionId, String contractNumber) {
        return new CreateSubmissionCommand(
                sourceSubmissionId, 2, List.of("doc-a"), Map.of("contractNumber", contractNumber));
    }

    private static Tenant tenant(UUID tenantId) {
        return new Tenant(tenantId, "tenant", "Tenant", TenantStatus.ACTIVE,
                TenantGuardrails.defaults(), NOW, NOW);
    }

    private static IngestionSource source(UUID tenantId, UUID sourceId) {
        return new IngestionSource(sourceId, tenantId, "source", "Source", IngestionSourceStatus.ACTIVE,
                IngestionSourceAuthType.OAUTH2_CLIENT_CREDENTIALS, "vault://source",
                new SourceLimits(null, null, 10, null, null), NOW, NOW);
    }

    private static MetadataFieldDefinition contractDefinition(UUID tenantId, UUID sourceId, int version, boolean required) {
        return new MetadataFieldDefinition(
                UUID.randomUUID(), tenantId, sourceId, "contractNumber", version, "Contract Number",
                MetadataFieldType.TEXT, required, true, true, MetadataNormalization.DIGITS_ONLY,
                MetadataSearchMode.EXACT, "{\"exactLength\":12}", true, NOW, null, NOW);
    }

    private static final class OneSourceRepository implements IngestionSourceRepository {
        private final IngestionSource source;
        private OneSourceRepository(IngestionSource source) { this.source = source; }
        @Override public List<IngestionSource> findAll(UUID tenantId) { return source.tenantId().equals(tenantId) ? List.of(source) : List.of(); }
        @Override public Optional<IngestionSource> findById(UUID tenantId, UUID sourceId) {
            return source.tenantId().equals(tenantId) && source.sourceId().equals(sourceId) ? Optional.of(source) : Optional.empty();
        }
        @Override public Optional<IngestionSource> findByKey(UUID tenantId, String sourceKey) { return Optional.empty(); }
        @Override public void insert(IngestionSource source) { throw new UnsupportedOperationException(); }
        @Override public void update(IngestionSource source) { throw new UnsupportedOperationException(); }
    }

    private static final class MutableDefinitionRepository implements MetadataFieldDefinitionRepository {
        private List<MetadataFieldDefinition> values;
        private MutableDefinitionRepository(List<MetadataFieldDefinition> values) { this.values = values; }
        @Override public List<MetadataFieldDefinition> findCurrent(UUID tenantId, UUID sourceId) {
            return values.stream().filter(MetadataFieldDefinition::active).toList();
        }
        @Override public List<MetadataFieldDefinition> findHistory(UUID tenantId, UUID sourceId, String fieldKey) { return values; }
        @Override public Optional<MetadataFieldDefinition> findCurrent(UUID tenantId, UUID sourceId, String fieldKey) {
            return values.stream().filter(MetadataFieldDefinition::active).filter(v -> v.fieldKey().equals(fieldKey)).findFirst();
        }
        @Override public void retire(UUID tenantId, UUID sourceId, String fieldKey, Instant retiredAt) { throw new UnsupportedOperationException(); }
        @Override public void insert(MetadataFieldDefinition definition) { throw new UnsupportedOperationException(); }
    }

    private static final class MemorySubmissionRepository implements SubmissionRepository {
        private final Map<String, Submission> values = new LinkedHashMap<>();
        private final Map<UUID, List<SubmissionExpectedDocument>> expected = new LinkedHashMap<>();
        private final Map<UUID, List<BusinessMetadataValue>> metadata = new LinkedHashMap<>();

        @Override public InsertResult insertIfAbsent(Submission candidate) {
            String key = key(candidate.tenantId(), candidate.sourceId(), candidate.sourceSubmissionId());
            Submission existing = values.get(key);
            if (existing != null) return new InsertResult(existing, false);
            values.put(key, candidate);
            return new InsertResult(candidate, true);
        }
        @Override public Optional<Submission> findById(UUID tenantId, UUID sourceId, UUID submissionId) {
            return values.values().stream().filter(v -> v.tenantId().equals(tenantId))
                    .filter(v -> v.sourceId().equals(sourceId)).filter(v -> v.submissionId().equals(submissionId)).findFirst();
        }
        @Override public Optional<Submission> findBySourceIdentity(UUID tenantId, UUID sourceId, String sourceSubmissionId) {
            return Optional.ofNullable(values.get(key(tenantId, sourceId, sourceSubmissionId)));
        }
        @Override public List<SubmissionExpectedDocument> findExpectedDocuments(UUID tenantId, UUID submissionId) {
            return expected.getOrDefault(submissionId, List.of());
        }
        @Override public List<BusinessMetadataValue> findBusinessMetadata(UUID tenantId, UUID sourceId, UUID submissionId) {
            return metadata.getOrDefault(submissionId, List.of());
        }
        @Override public void insertExpectedDocuments(List<SubmissionExpectedDocument> documents) {
            if (!documents.isEmpty()) expected.put(documents.getFirst().submissionId(), List.copyOf(documents));
        }
        @Override public void insertBusinessMetadata(List<BusinessMetadataValue> values) {
            if (!values.isEmpty()) metadata.put(values.getFirst().submissionId(), List.copyOf(values));
        }
        private static String key(UUID tenantId, UUID sourceId, String sourceSubmissionId) {
            return tenantId + "|" + sourceId + "|" + sourceSubmissionId;
        }
    }
}
