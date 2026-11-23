package com.sdv.platform.application.metadata;

import com.sdv.platform.application.access.ActiveTenant;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.RecordSensitivityRepository;
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
    private final RecordSensitivityRepository recordLevels;
    private final AuditEventPort audit;
    private final Clock clock;

    @Autowired
    public MetadataFieldAdminService(TenantRepository tenants,
                                     IngestionSourceRepository sources,
                                     MetadataFieldDefinitionRepository definitions,
                                     RecordSensitivityRepository recordLevels,
                                     AuditEventPort audit) {
        this(tenants, sources, definitions, recordLevels, audit, Clock.systemUTC());
    }

    MetadataFieldAdminService(TenantRepository tenants,
                              IngestionSourceRepository sources,
                              MetadataFieldDefinitionRepository definitions,
                              RecordSensitivityRepository recordLevels,
                              AuditEventPort audit,
                              Clock clock) {
        this.tenants = tenants;
        this.sources = sources;
        this.definitions = definitions;
        this.recordLevels = recordLevels;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Task 0024, part 2: a record level is stored under the record's identifier values, normalized. Changing which
     * fields are identifiers, or how an identifier is normalized, would put later deliveries in a different record
     * without that level: a way to lower it. So while any record of the sending system has a level, such a change is
     * refused. Labels, search settings, required, type and validation rules don't change the grouping and stay open.
     */
    private void refuseIfRecordIdentityChanges(UUID tenantId, UUID sourceId, String fieldKey,
                                               java.util.Optional<MetadataFieldDefinition> previous,
                                               boolean nextIdentifier, MetadataNormalization nextNormalization) {
        boolean wasIdentifier = previous.map(MetadataFieldDefinition::businessIdentifier).orElse(false);
        boolean changes = wasIdentifier != nextIdentifier
                || (nextIdentifier && previous.get().normalization() != nextNormalization);
        if (!changes) return;
        long withLevel = recordLevels.countForSource(tenantId, sourceId);
        if (withLevel > 0) {
            throw new com.sdv.platform.domain.error.CodedRefusal.Conflict(
                    com.sdv.platform.domain.error.SdvErrorCode.SEN_007,
                    "Field '" + fieldKey + "' can't change: " + withLevel
                    + (withLevel == 1 ? " record" : " records") + " of this sending system "
                    + (withLevel == 1 ? "has" : "have") + " a sensitivity level, and changing which fields identify "
                    + "a record, or how an identifier is normalized, would separate later documents from that level.");
        }
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
    public List<String> updateDisplayOrder(UUID tenantId, UUID sourceId, List<String> keys,
                                           String actorId, String correlationId) {
        requireSource(tenantId, sourceId);
        if (keys == null) throw new IllegalArgumentException("fieldKeys is required.");
        List<String> existing = definitions.findCurrent(tenantId, sourceId).stream()
                .map(MetadataFieldDefinition::fieldKey).toList();
        if (keys.size() != existing.size() || keys.stream().distinct().count() != keys.size()
                || !new java.util.HashSet<>(keys).equals(new java.util.HashSet<>(existing))) {
            throw new IllegalArgumentException("fieldKeys must contain each current source field exactly once.");
        }
        definitions.replaceDisplayOrder(tenantId, sourceId, keys);
        Instant now=clock.instant();
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId,
                "METADATA_DISPLAY_ORDER_UPDATED", "INGESTION_SOURCE", sourceId.toString(), now,
                correlationId, AuditOutcome.SUCCESS, "{\"fieldCount\":" + keys.size() + "}"));
        return definitions.findDisplayOrder(tenantId, sourceId);
    }

    public List<String> displayOrder(UUID tenantId, UUID sourceId) {
        requireSource(tenantId, sourceId);
        return definitions.findDisplayOrder(tenantId, sourceId);
    }

    /** The most fields a record title may combine (e.g. First name + Last name). */
    public static final int MAX_RECORD_TITLE_FIELDS = 2;

    /** The fields that name a record for this source, in title order; empty means the default title. */
    public List<String> recordTitleFields(UUID tenantId, UUID sourceId) {
        requireSource(tenantId, sourceId);
        return definitions.findRecordTitleFields(tenantId, sourceId);
    }

    /**
     * Sets the one or two fields that name a record; an empty list goes back to the default title. Each key must be
     * a current field of the source and must not be an identifier (the identifier is shown beside the title anyway).
     */
    @Transactional
    public List<String> updateRecordTitleFields(UUID tenantId, UUID sourceId, List<String> keys,
                                                String actorId, String correlationId) {
        requireSource(tenantId, sourceId);
        if (keys == null) throw new IllegalArgumentException("fieldKeys is required.");
        if (keys.size() > MAX_RECORD_TITLE_FIELDS) {
            throw new IllegalArgumentException("A record title uses at most " + MAX_RECORD_TITLE_FIELDS + " fields.");
        }
        if (keys.stream().distinct().count() != keys.size()) {
            throw new IllegalArgumentException("Each record title field may be chosen once.");
        }
        java.util.Map<String, MetadataFieldDefinition> current = new java.util.HashMap<>();
        definitions.findCurrent(tenantId, sourceId).forEach(field -> current.put(field.fieldKey(), field));
        for (String key : keys) {
            MetadataFieldDefinition field = key == null ? null : current.get(key);
            if (field == null) throw new IllegalArgumentException("Record title fields must be current source fields.");
            if (field.businessIdentifier()) {
                throw new IllegalArgumentException("An identifier cannot be a record title field.");
            }
        }
        definitions.replaceRecordTitleFields(tenantId, sourceId, keys);
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId,
                "RECORD_TITLE_FIELDS_UPDATED", "INGESTION_SOURCE", sourceId.toString(), clock.instant(),
                correlationId, AuditOutcome.SUCCESS, "{\"fieldCount\":" + keys.size() + "}"));
        return definitions.findRecordTitleFields(tenantId, sourceId);
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
        refuseIfRecordIdentityChanges(tenantId, sourceId, fieldKey, previous,
                command.businessIdentifier(), command.normalization());
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
                .orElseThrow(() -> new NoSuchElementException("Source field is not defined. IT must define it before Business Administration can configure search behavior."));
        refuseIfRecordIdentityChanges(tenantId, sourceId, fieldKey, java.util.Optional.of(previous),
                command.businessIdentifier(), command.normalization());
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
        // This path keeps the identifier flag as it was (a new field is not an identifier) but may change normalization.
        refuseIfRecordIdentityChanges(tenantId, sourceId, fieldKey, previous,
                previous.map(MetadataFieldDefinition::businessIdentifier).orElse(false), command.normalization());
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
        var tenant = ActiveTenant.require(tenants, tenantId);
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
