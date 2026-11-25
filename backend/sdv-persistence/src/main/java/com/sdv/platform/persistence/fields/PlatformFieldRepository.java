package com.sdv.platform.persistence.fields;

import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldDefinitionException;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.SearchMode;
import com.documentplatform.fields.file.FieldFileException;
import com.documentplatform.fields.file.ImportReport;
import com.documentplatform.fields.FieldStore;
import com.documentplatform.fields.FieldValidator;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.jdbc.PostgresFieldStore;
import com.documentplatform.fields.jdbc.PostgresValueStore;
import com.sdv.platform.application.port.CaseFieldsPort;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.SetupProblem;
import com.sdv.platform.domain.identity.VeroProduct;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Task 0077: the field definitions, in platform-fields. One definition per entity and key: type, rules, identifier, sensitivity. What a
 * sending system adds is its own ({@link SourceField}). Task 0078: nothing else holds them.
 */
@Repository
public class PlatformFieldRepository implements MetadataFieldDefinitionRepository, CaseFieldsPort {
    /** The history of who changed what for whom is Vero's audit; the definition's own history says which application. */
    private static final String VERO = "vero";
    private final JdbcTemplate db;
    private final PostgresValueStore values;
    private final FieldDefinitions definitions;

    public PlatformFieldRepository(DataSource dataSource) {
        this.db = new JdbcTemplate(dataSource);
        JoinedDataSource joined = new JoinedDataSource(dataSource);
        this.values = new PostgresValueStore(joined);
        this.definitions = new FieldDefinitions(new PostgresFieldStore(joined), values);
    }

    private static FieldScope scope(UUID entity) {
        return FieldScope.of(entity, VeroProduct.CODE);
    }

    private boolean valuesExist(UUID entity, String key) {
        return values.valuesExist(scope(entity), FieldKey.of(key));
    }

    // ---- reading

    /** One system's fields: the definition's type, rules and identifier flag, the system's own label and search settings. */
    private static MetadataFieldDefinition merged(UUID entity, UUID sourceId, FieldDefinition definition, SourceField source) {
        FieldSpec spec = definition.spec();
        var type = FieldSpecs.type(spec);
        if (type == null) throw setup(definition.key().value());
        Instant since = Instant.ofEpochMilli(source.since());
        return new MetadataFieldDefinition(definition.id(), entity, sourceId, definition.key().value(), source.version(), source.label(), type,
                source.required(), source.identifier(), source.searchable(), source.normalization(), source.mode(), FieldSpecs.rules(spec), true,
                since, null, definition.createdAt());
    }

    /** A type a delivery can't carry as one text: Vero's setup, never the sender's request. Refused, not guessed. */
    private static SetupProblem setup(String key) {
        return new SetupProblem(SdvErrorCode.INT_010, "Vero's setup for field '" + key + "' is broken, so this submission can't be checked. Nothing was stored. Your IT team has been told.",
                "field=" + key + " rule: a type a delivery can't carry");
    }

    /** Every field the system sends, in one read. */
    private Map<String, SourceField> sourceFields(UUID entity, UUID source) {
        Map<String, SourceField> out = new TreeMap<>();
        db.query("select f.field_key, x.value from fld_field_definition f join fld_field_extension x on x.definition_id = f.definition_id and x.name = ? where f.entity_uuid = ? and f.product_code = ?",
                rs -> { out.put(rs.getString(1), SourceField.parse(rs.getString(2))); }, SourceField.name(source), entity, VeroProduct.CODE);
        return out;
    }

    private SourceField sourceField(UUID entity, String key, UUID source) {
        if (definitions.find(scope(entity), FieldKey.of(key)).isEmpty()) return null;
        String json = definitions.extensions(scope(entity), FieldKey.of(key)).get(SourceField.name(source));
        return json == null ? null : SourceField.parse(json);
    }

    @Override
    public List<MetadataFieldDefinition> findCurrent(UUID tenantId, UUID sourceId) {
        Map<String, SourceField> sent = sourceFields(tenantId, sourceId);
        if (sent.isEmpty()) return List.of();
        return definitions.active(scope(tenantId)).stream().filter(d -> sent.containsKey(d.key().value())).sorted(Comparator.comparing(d -> d.key().value()))
                .map(d -> merged(tenantId, sourceId, d, sent.get(d.key().value()))).toList();
    }

    @Override
    public Optional<MetadataFieldDefinition> findCurrent(UUID tenantId, UUID sourceId, String fieldKey) {
        SourceField source = sourceFields(tenantId, sourceId).get(fieldKey);
        if (source == null) return Optional.empty();
        return definitions.find(scope(tenantId), FieldKey.of(fieldKey)).filter(FieldDefinition::active).map(d -> merged(tenantId, sourceId, d, source));
    }

    /** The system's settings now, then what the definition was before, newest first. */
    @Override
    public List<MetadataFieldDefinition> findHistory(UUID tenantId, UUID sourceId, String fieldKey) {
        MetadataFieldDefinition now = findCurrent(tenantId, sourceId, fieldKey).orElse(null);
        if (now == null) return List.of();
        List<MetadataFieldDefinition> out = new ArrayList<>(List.of(now));
        List<FieldStore.HistoryEntry> entries = definitions.history(scope(tenantId), FieldKey.of(fieldKey)).stream()
                .sorted(Comparator.comparingInt(FieldStore.HistoryEntry::version).reversed()).toList();
        Instant until = null;
        for (FieldStore.HistoryEntry e : entries) {
            var type = FieldSpecs.type(e.spec());
            if (until != null && type != null) {
                out.add(new MetadataFieldDefinition(now.definitionId(), tenantId, sourceId, fieldKey, e.version(), e.spec().label(), type, now.required(), e.spec().primary(),
                        e.spec().searchable(), FieldSpecs.normalization(e.spec()), com.sdv.platform.domain.metadata.MetadataSearchMode.valueOf(e.spec().searchMode().name()),
                        FieldSpecs.rules(e.spec()), false, e.at(), until, now.createdAt()));
            }
            until = e.at();
        }
        return out;
    }

    /** A typed search text as the field's values are stored (a date in another form, for example); nothing when it isn't such a value. */
    @Override
    public List<String> storedForms(UUID tenantId, String fieldKey, String text) {
        FieldDefinition d = definitions.find(scope(tenantId), FieldKey.of(fieldKey)).orElse(null);
        if (d == null || d.type() == com.documentplatform.fields.FieldType.ADDRESS) return List.of();
        FieldValidator.Checked checked = FieldValidator.check(d.spec(), FieldValue.text(text));
        return checked.ok() ? List.of(checked.valid().normalisedText()) : List.of();
    }

    // ---- writing

    @Override
    @Transactional
    public void insert(MetadataFieldDefinition sent) {
        MetadataFieldDefinition d = coded(sent.fieldKey(), () -> withKnownRules(sent));
        coded(d.fieldKey(), () -> {
            FieldScope scope = scope(d.tenantId());
            FieldKey key = FieldKey.of(d.fieldKey());
            FieldDefinition before = definitions.find(scope, key).orElse(null);
            boolean primary = d.businessIdentifier() || usedAsIdentifierElsewhere(d);
            FieldSpec spec = FieldSpecs.spec(d, before == null ? null : before.spec(), primary);
            if (before == null) definitions.create(scope, spec, VERO);
            else {
                if (FieldSpecs.type(before.spec()) != d.fieldType() && sentByAnother(d)) {
                    throw new CodedRefusal.Conflict(SdvErrorCode.FLD_003, "Field '" + d.fieldKey() + "' is sent by another sending system with another type. A key has one type; use another key.");
                }
                if (!before.active()) definitions.switchOn(scope, key, VERO);
                FieldDefinition now = definitions.find(scope, key).orElseThrow();
                definitions.change(scope, spec, now.version(), VERO);
            }
            definitions.putExtension(scope, key, SourceField.name(d.sourceId()), SourceField.of(d, sourceField(d.tenantId(), d.fieldKey(), d.sourceId())).json());
            return spec;
        });
    }

    /** A system that starts to send a field without rules of its own takes the field's rules: a key has one set of them. */
    private MetadataFieldDefinition withKnownRules(MetadataFieldDefinition d) {
        boolean noRules = d.validationRulesJson() == null || d.validationRulesJson().isBlank() || d.validationRulesJson().trim().equals("{}");
        if (!noRules || sourceField(d.tenantId(), d.fieldKey(), d.sourceId()) != null) return d;
        return definitions.find(scope(d.tenantId()), FieldKey.of(d.fieldKey())).filter(f -> FieldSpecs.type(f.spec()) == d.fieldType())
                .map(f -> withRules(d, FieldSpecs.rules(f.spec()))).orElse(d);
    }

    private static MetadataFieldDefinition withRules(MetadataFieldDefinition d, String rules) {
        return new MetadataFieldDefinition(d.definitionId(), d.tenantId(), d.sourceId(), d.fieldKey(), d.version(), d.displayLabel(), d.fieldType(), d.required(),
                d.businessIdentifier(), d.searchable(), d.normalization(), d.searchMode(), rules, d.active(), d.effectiveAt(), d.retiredAt(), d.createdAt());
    }

    private boolean sentByAnother(MetadataFieldDefinition d) {
        return definitions.extensions(scope(d.tenantId()), FieldKey.of(d.fieldKey())).keySet().stream()
                .anyMatch(name -> name.startsWith("source.") && !name.equals(SourceField.name(d.sourceId())));
    }

    private boolean usedAsIdentifierElsewhere(MetadataFieldDefinition d) {
        if (definitions.find(scope(d.tenantId()), FieldKey.of(d.fieldKey())).isEmpty()) return false;
        return definitions.extensions(scope(d.tenantId()), FieldKey.of(d.fieldKey())).entrySet().stream()
                .anyMatch(e -> e.getKey().startsWith("source.") && !e.getKey().equals(SourceField.name(d.sourceId())) && SourceField.parse(e.getValue()).identifier());
    }

    // ---- order and record title: the system's own settings

    private List<String> places(UUID tenantId, UUID sourceId, java.util.function.ToIntFunction<SourceField> place) {
        Map<String, Integer> found = new TreeMap<>();
        sourceFields(tenantId, sourceId).forEach((key, s) -> { if (place.applyAsInt(s) > 0) found.put(key, place.applyAsInt(s)); });
        return found.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey())).map(Map.Entry::getKey).toList();
    }

    @FunctionalInterface
    private interface Placed { SourceField at(SourceField field, int place); }

    private void place(UUID tenantId, UUID sourceId, List<String> keys, Placed placed) {
        sourceFields(tenantId, sourceId).forEach((key, s) -> {
            SourceField next = placed.at(s, keys.indexOf(key) + 1);
            if (!next.equals(s)) definitions.putExtension(scope(tenantId), FieldKey.of(key), SourceField.name(sourceId), next.json());
        });
    }

    @Override
    public List<String> findDisplayOrder(UUID tenantId, UUID sourceId) {
        return places(tenantId, sourceId, SourceField::order);
    }

    @Override
    @Transactional
    public void replaceDisplayOrder(UUID tenantId, UUID sourceId, List<String> fieldKeys) {
        place(tenantId, sourceId, fieldKeys, SourceField::withOrder);
    }

    @Override
    public List<String> findRecordTitleFields(UUID tenantId, UUID sourceId) {
        return places(tenantId, sourceId, SourceField::title);
    }

    @Override
    @Transactional
    public void replaceRecordTitleFields(UUID tenantId, UUID sourceId, List<String> fieldKeys) {
        place(tenantId, sourceId, fieldKeys, SourceField::withTitle);
    }

    // ---- the entity's list

    private FieldState state(UUID entity, FieldDefinition d) {
        return new FieldState(d.key().value(), d.spec().label(), d.type().name(), d.spec().primary(), d.spec().sensitive(), valuesExist(entity, d.key().value()), d.active());
    }

    @Override
    public List<FieldState> fields(UUID entity) {
        return definitions.list(scope(entity)).stream().sorted(Comparator.comparing(FieldDefinition::key)).map(d -> state(entity, d)).toList();
    }

    @Override
    @Transactional
    public FieldState setSensitive(UUID entity, String fieldKey, boolean sensitive, String actor) {
        return coded(fieldKey, () -> {
            FieldDefinition d = definitions.find(scope(entity), FieldKey.of(fieldKey)).orElseThrow(() -> new java.util.NoSuchElementException("Field not found."));
            FieldSpec.Builder b = d.spec().toBuilder().sensitive(sensitive);
            if (sensitive && d.spec().searchable()) b.searchable(SearchMode.EXACT);
            return state(entity, definitions.change(scope(entity), b.build(), d.version(), actor));
        });
    }

    @Override
    public String exportFile(UUID entity) {
        return definitions.exportFile(scope(entity));
    }

    @Override
    public List<ImportLine> previewImport(UUID entity, String file) {
        return coded("file", () -> lines(definitions.previewImport(scope(entity), file)));
    }

    @Override
    @Transactional
    public List<ImportLine> importFile(UUID entity, String file, String actor) {
        return coded("file", () -> lines(definitions.importFile(scope(entity), file, actor)));
    }

    private static List<ImportLine> lines(ImportReport report) {
        List<ImportLine> out = new ArrayList<>();
        report.created().forEach(l -> out.add(new ImportLine(l.key().value(), l.spec().label(), "CREATE", null, l.message())));
        report.unchanged().forEach(l -> out.add(new ImportLine(l.key().value(), l.spec().label(), "UNCHANGED", null, l.message())));
        report.conflicts().forEach(l -> out.add(new ImportLine(l.key().value(), l.spec().label(), "CONFLICT", l.conflict().name(), l.message())));
        return out;
    }

    /** platform-fields' refusals as Vero's. Its sentences name keys, labels and rules, never a value. */
    private static <T> T coded(String key, Supplier<T> work) {
        try {
            return work.get();
        } catch (FieldFileException ex) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.FLD_004, "The field list can't be read: " + ex.getMessage(), "code=" + ex.code());
        } catch (FieldDefinitionException ex) {
            String detail = "field=" + key + " code=" + ex.code();
            switch (ex.code()) {
                case FIELD_TYPE_LOCKED, FIELD_PRIMARY_LOCKED, FIELD_SENSITIVE_LOCKED, FIELD_KEY_LOCKED ->
                        throw new CodedRefusal.Conflict(SdvErrorCode.FLD_002, "Field '" + key + "' has values, so " + switch (ex.code()) {
                            case FIELD_TYPE_LOCKED -> "its type can't change. Add a new field instead.";
                            case FIELD_PRIMARY_LOCKED -> "whether it identifies a record can't change.";
                            default -> "whether it is sensitive can't change.";
                        });
                case FIELD_KEY_TAKEN, FIELD_CHANGED_MEANWHILE -> throw new CodedRefusal.Conflict(SdvErrorCode.FLD_003, ex.getMessage());
                default -> throw new CodedRefusal.BadRequest(SdvErrorCode.FLD_001, ex.getMessage(), detail);
            }
        }
    }
}
