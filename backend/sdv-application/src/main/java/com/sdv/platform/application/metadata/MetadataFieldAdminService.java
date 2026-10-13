package com.sdv.platform.application.metadata;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class MetadataFieldAdminService {
    private static final Pattern FIELD_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,99}");

    private final TenantRepository tenants;
    private final IngestionSourceRepository sources;
    private final MetadataFieldDefinitionRepository definitions;
    private final AuditEventPort audit;
    private final Clock clock;

    @Autowired
    public MetadataFieldAdminService(TenantRepository tenants,
                                     IngestionSourceRepository sources,
                                     MetadataFieldDefinitionRepository definitions,
                                     AuditEventPort audit) {
        this(tenants, sources, definitions, audit, Clock.systemUTC());
    }

    MetadataFieldAdminService(TenantRepository tenants,
                              IngestionSourceRepository sources,
                              MetadataFieldDefinitionRepository definitions,
                              AuditEventPort audit,
                              Clock clock) {
        this.tenants = tenants;
        this.sources = sources;
        this.definitions = definitions;
        this.audit = audit;
        this.clock = clock;
    }

    public List<MetadataFieldDefinition> listCurrent(UUID tenantId, UUID sourceId) {
        requireSource(tenantId, sourceId);
        return definitions.findCurrent(tenantId, sourceId);
    }

    public List<MetadataFieldDefinition> history(UUID tenantId, UUID sourceId, String fieldKey) {
        requireSource(tenantId, sourceId);
        validateFieldKey(fieldKey);
        return definitions.findHistory(tenantId, sourceId, fieldKey);
    }

    @Transactional
    public MetadataFieldDefinition createOrRevise(UUID tenantId,
                                                   UUID sourceId,
                                                   String fieldKey,
                                                   UpsertMetadataFieldCommand command,
                                                   String actorId,
                                                   String correlationId) {
        requireSource(tenantId, sourceId);
        validateFieldKey(fieldKey);
        validate(command);

        var previous = definitions.findCurrent(tenantId, sourceId, fieldKey);
        int nextVersion = previous.map(d -> d.version() + 1).orElse(1);
        Instant now = clock.instant();
        previous.ifPresent(ignore -> definitions.retire(tenantId, sourceId, fieldKey, now));

        MetadataFieldDefinition definition = new MetadataFieldDefinition(
                UUID.randomUUID(), tenantId, sourceId, fieldKey, nextVersion,
                command.displayLabel().trim(), command.fieldType(), command.required(), command.businessIdentifier(),
                command.searchable(), command.normalization(), command.searchMode(), normalizedRules(command.validationRulesJson()),
                true, now, null, now);
        definitions.insert(definition);
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId,
                previous.isPresent() ? "METADATA_FIELD_REVISED" : "METADATA_FIELD_CREATED",
                "METADATA_FIELD", sourceId + ":" + fieldKey, now, correlationId,
                AuditOutcome.SUCCESS, "{\"version\":" + nextVersion + "}"));
        return definition;
    }


    @Transactional
    public MetadataFieldDefinition reviseBusinessBehavior(UUID tenantId,
                                                          UUID sourceId,
                                                          String fieldKey,
                                                          BusinessMetadataBehaviorCommand command,
                                                          String actorId,
                                                          String correlationId) {
        requireSource(tenantId, sourceId);
        validateFieldKey(fieldKey);
        if (command == null) throw new IllegalArgumentException("metadata behavior is required.");
        if (command.displayLabel() == null || command.displayLabel().isBlank()) {
            throw new IllegalArgumentException("displayLabel is required.");
        }
        if (command.normalization() == null) throw new IllegalArgumentException("normalization is required.");
        if (command.searchMode() == null) throw new IllegalArgumentException("searchMode is required.");
        if (!command.searchable() && command.searchMode() != MetadataSearchMode.EXACT) {
            throw new IllegalArgumentException("Non-searchable fields must use EXACT searchMode as their inert stored setting.");
        }

        MetadataFieldDefinition previous = definitions.findCurrent(tenantId, sourceId, fieldKey)
                .orElseThrow(() -> new NoSuchElementException("Source field is not defined. Tenant IT must define it before Business Administration can configure search behavior."));
        Instant now = clock.instant();
        definitions.retire(tenantId, sourceId, fieldKey, now);
        MetadataFieldDefinition revised = new MetadataFieldDefinition(
                UUID.randomUUID(), tenantId, sourceId, fieldKey, previous.version() + 1,
                command.displayLabel().trim(), previous.fieldType(), previous.required(), command.businessIdentifier(),
                command.searchable(), command.normalization(), command.searchMode(), previous.validationRulesJson(),
                true, now, null, now);
        definitions.insert(revised);
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId,
                "METADATA_BEHAVIOR_REVISED", "METADATA_FIELD", sourceId + ":" + fieldKey, now, correlationId,
                AuditOutcome.SUCCESS, "{\"version\":" + revised.version() + "}"));
        return revised;
    }

    @Transactional
    public MetadataFieldDefinition createOrReviseSourceField(UUID tenantId,
                                                              UUID sourceId,
                                                              String fieldKey,
                                                              SourceFieldContractCommand command,
                                                              String actorId,
                                                              String correlationId) {
        requireSource(tenantId, sourceId);
        validateFieldKey(fieldKey);
        if (command == null) throw new IllegalArgumentException("source field contract is required.");
        if (command.displayLabel() == null || command.displayLabel().isBlank()) throw new IllegalArgumentException("displayLabel is required.");
        if (command.fieldType() == null) throw new IllegalArgumentException("fieldType is required.");
        if (command.normalization() == null) throw new IllegalArgumentException("normalization is required.");

        var previous = definitions.findCurrent(tenantId, sourceId, fieldKey);
        int nextVersion = previous.map(d -> d.version() + 1).orElse(1);
        Instant now = clock.instant();
        previous.ifPresent(ignore -> definitions.retire(tenantId, sourceId, fieldKey, now));
        boolean searchable = previous.map(MetadataFieldDefinition::searchable).orElse(false);
        boolean businessIdentifier = previous.map(MetadataFieldDefinition::businessIdentifier).orElse(false);
        MetadataSearchMode searchMode = previous.map(MetadataFieldDefinition::searchMode).orElse(MetadataSearchMode.EXACT);

        MetadataFieldDefinition definition = new MetadataFieldDefinition(
                UUID.randomUUID(), tenantId, sourceId, fieldKey, nextVersion,
                command.displayLabel().trim(), command.fieldType(), command.required(), businessIdentifier,
                searchable, command.normalization(), searchMode, normalizedRules(command.validationRulesJson()),
                true, now, null, now);
        definitions.insert(definition);
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId,
                previous.isPresent() ? "SOURCE_FIELD_REVISED" : "SOURCE_FIELD_DEFINED",
                "METADATA_FIELD", sourceId + ":" + fieldKey, now, correlationId,
                AuditOutcome.SUCCESS, "{\"version\":" + nextVersion + "}"));
        return definition;
    }

    private void requireSource(UUID tenantId, UUID sourceId) {
        var tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found."));
        if (tenant.status() != TenantStatus.ACTIVE) throw new IllegalStateException("Tenant is not active.");
        sources.findById(tenantId, sourceId)
                .orElseThrow(() -> new NoSuchElementException("Ingestion source not found."));
    }

    private static void validateFieldKey(String fieldKey) {
        if (fieldKey == null || !FIELD_KEY.matcher(fieldKey).matches()) {
            throw new IllegalArgumentException("fieldKey must start with a letter and use at most 100 letters, numbers, '.', '_' or '-'.");
        }
    }

    private static void validate(UpsertMetadataFieldCommand command) {
        if (command == null) throw new IllegalArgumentException("metadata field configuration is required.");
        if (command.displayLabel() == null || command.displayLabel().isBlank()) throw new IllegalArgumentException("displayLabel is required.");
        if (command.fieldType() == null) throw new IllegalArgumentException("fieldType is required.");
        if (command.normalization() == null) throw new IllegalArgumentException("normalization is required.");
        if (command.searchMode() == null) throw new IllegalArgumentException("searchMode is required.");
        if (!command.searchable() && command.searchMode() != MetadataSearchMode.EXACT) {
            throw new IllegalArgumentException("Non-searchable fields must use EXACT searchMode as their inert stored setting.");
        }
        if (command.fieldType().name().equals("BOOLEAN") && command.normalization() == MetadataNormalization.DIGITS_ONLY) {
            throw new IllegalArgumentException("DIGITS_ONLY normalization is not valid for BOOLEAN fields.");
        }
    }

    private static String normalizedRules(String rules) {
        return rules == null || rules.isBlank() ? "{}" : rules;
    }
}
