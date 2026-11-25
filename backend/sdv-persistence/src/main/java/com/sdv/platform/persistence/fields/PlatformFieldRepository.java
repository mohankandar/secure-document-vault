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
import com.documentplatform.fields.jdbc.PostgresFieldStore;
import com.sdv.platform.application.port.CaseFieldsPort;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.SetupProblem;
import com.sdv.platform.domain.identity.VeroProduct;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import org.springframework.context.annotation.Primary;
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
 * sending system adds is its own ({@link SourceField}). Vero's old rows are only a copy of these, written in the same transaction, because
 * stored values point to them until 0078; they give the definition's ID and version number, nothing else is read from them.
 */
@Repository
@Primary
public class PlatformFieldRepository implements MetadataFieldDefinitionRepository, CaseFieldsPort {
    /** The history of who changed what for whom is Vero's audit; the definition's own history says which application. */
    private static final String VERO = "vero";
    private final JdbcFoundationRepository old;
    private final JdbcTemplate db;
    private final FieldDefinitions definitions;

    public PlatformFieldRepository(JdbcFoundationRepository old, DataSource dataSource) {
        this.old = old;
        this.db = new JdbcTemplate(dataSource);
        PostgresFieldStore store = new PostgresFieldStore(new JoinedDataSource(dataSource));
        this.definitions = new FieldDefinitions(store, (scope, key) -> valuesExist(scope.entity(), key.value()));
    }

    private static FieldScope scope(UUID entity) {
        return FieldScope.of(entity, VeroProduct.CODE);
    }

    private boolean valuesExist(UUID entity, String key) {
        return Boolean.TRUE.equals(db.queryForObject("select exists (select 1 from business_metadata_values where tenant_id = ? and field_key = ?)", Boolean.class, entity, key));
    }

    // ---- reading

    /** One system's fields: the definition's type, rules and identifier flag, the system's own label and search settings. */
    private MetadataFieldDefinition merged(MetadataFieldDefinition copy, FieldDefinition definition, SourceField source) {
        FieldSpec spec = definition.spec();
        var type = FieldSpecs.type(spec);
        if (type == null || source == null) throw setup(copy);
        return new MetadataFieldDefinition(copy.definitionId(), copy.tenantId(), copy.sourceId(), copy.fieldKey(), copy.version(), source.label(), type,
                source.required(), source.identifier(), source.searchable(), source.normalization(), source.mode(), FieldSpecs.rules(spec), true,
                copy.effectiveAt(), null, copy.createdAt());
    }

    /** A copy without its definition: Vero's setup, never the sender's request. Refused, not guessed. */
    private static SetupProblem setup(MetadataFieldDefinition copy) {
        return new SetupProblem(SdvErrorCode.INT_010, "Vero's setup for field '" + copy.fieldKey() + "' is broken, so this submission can't be checked. Nothing was stored. Your IT team has been told.",
                "field=" + copy.fieldKey() + " rule: no definition for this sending system");
    }

    private SourceField sourceField(UUID entity, String key, UUID source) {
        if (definitions.find(scope(entity), FieldKey.of(key)).isEmpty()) return null;
        String json = definitions.extensions(scope(entity), FieldKey.of(key)).get(SourceField.name(source));
        return json == null ? null : SourceField.parse(json);
    }

    @Override
    public List<MetadataFieldDefinition> findCurrent(UUID tenantId, UUID sourceId) {
        Map<String, FieldDefinition> byKey = new TreeMap<>();
        for (FieldDefinition d : definitions.active(scope(tenantId))) byKey.put(d.key().value(), d);
        List<MetadataFieldDefinition> out = new ArrayList<>();
        for (MetadataFieldDefinition copy : old.findCurrent(tenantId, sourceId)) {
            FieldDefinition d = byKey.get(copy.fieldKey());
            if (d == null) throw setup(copy);
            out.add(merged(copy, d, sourceField(tenantId, copy.fieldKey(), sourceId)));
        }
        return out;
    }

    @Override
    public Optional<MetadataFieldDefinition> findCurrent(UUID tenantId, UUID sourceId, String fieldKey) {
        return old.findCurrent(tenantId, sourceId, fieldKey).map(copy -> merged(copy,
                definitions.find(scope(tenantId), FieldKey.of(fieldKey)).filter(FieldDefinition::active).orElseThrow(() -> setup(copy)), sourceField(tenantId, fieldKey, sourceId)));
    }

    /** What was, as it was recorded then. */
    @Override
    public List<MetadataFieldDefinition> findHistory(UUID tenantId, UUID sourceId, String fieldKey) {
        return old.findHistory(tenantId, sourceId, fieldKey);
    }

    // ---- writing

    @Override
    public void retire(UUID tenantId, UUID sourceId, String fieldKey, Instant retiredAt) {
        old.retire(tenantId, sourceId, fieldKey, retiredAt);
    }

    @Override
    @Transactional
    public void insert(MetadataFieldDefinition sent) {
        MetadataFieldDefinition d = coded(sent.fieldKey(), () -> withKnownRules(sent));
        FieldSpec stored = coded(d.fieldKey(), () -> {
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
        old.insert(withRules(d, FieldSpecs.rules(stored)));
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

    // ---- order and record title: the system's own settings, and their copies

    private List<String> places(UUID tenantId, UUID sourceId, java.util.function.ToIntFunction<SourceField> place) {
        Map<String, Integer> found = new TreeMap<>();
        for (FieldDefinition d : definitions.list(scope(tenantId))) {
            SourceField s = sourceField(tenantId, d.key().value(), sourceId);
            if (s != null && place.applyAsInt(s) > 0) found.put(d.key().value(), place.applyAsInt(s));
        }
        return found.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey())).map(Map.Entry::getKey).toList();
    }

    @FunctionalInterface
    private interface Placed { SourceField at(SourceField field, int place); }

    private void place(UUID tenantId, UUID sourceId, List<String> keys, Placed placed) {
        for (FieldDefinition d : definitions.list(scope(tenantId))) {
            SourceField s = sourceField(tenantId, d.key().value(), sourceId);
            if (s == null) continue;
            SourceField next = placed.at(s, keys.indexOf(d.key().value()) + 1);
            if (!next.equals(s)) definitions.putExtension(scope(tenantId), d.key(), SourceField.name(sourceId), next.json());
        }
    }

    @Override
    public List<String> findDisplayOrder(UUID tenantId, UUID sourceId) {
        return places(tenantId, sourceId, SourceField::order);
    }

    @Override
    @Transactional
    public void replaceDisplayOrder(UUID tenantId, UUID sourceId, List<String> fieldKeys) {
        place(tenantId, sourceId, fieldKeys, SourceField::withOrder);
        old.replaceDisplayOrder(tenantId, sourceId, fieldKeys);
    }

    @Override
    public List<String> findRecordTitleFields(UUID tenantId, UUID sourceId) {
        return places(tenantId, sourceId, SourceField::title);
    }

    @Override
    @Transactional
    public void replaceRecordTitleFields(UUID tenantId, UUID sourceId, List<String> fieldKeys) {
        place(tenantId, sourceId, fieldKeys, SourceField::withTitle);
        old.replaceRecordTitleFields(tenantId, sourceId, fieldKeys);
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

    /** Every entity and sending system with a field of before this task; entities that are switched off too. */
    public List<UUID[]> systems() {
        return db.query("select distinct tenant_id, source_id from metadata_field_definitions where active order by 1, 2",
                (rs, n) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)});
    }

    /**
     * A database of before this task: every current row becomes a definition, the same way a new field does. Safe to run again: only a
     * row without its definition is taken. Returns how many were.
     */
    @Transactional
    public int adopt(UUID tenantId, UUID sourceId) {
        int taken = 0;
        for (MetadataFieldDefinition d : old.findCurrent(tenantId, sourceId)) {
            FieldScope scope = scope(tenantId);
            FieldKey key = FieldKey.of(d.fieldKey());
            FieldDefinition before = definitions.find(scope, key).orElse(null);
            if (before != null && sourceField(tenantId, d.fieldKey(), sourceId) != null) continue;
            if (before == null) definitions.create(scope, FieldSpecs.spec(d, null, d.businessIdentifier()), VERO);
            else if (d.businessIdentifier() && !before.spec().primary()) {
                definitions.change(scope, before.spec().toBuilder().primary(true).build(), before.version(), VERO);
            }
            definitions.putExtension(scope, key, SourceField.name(sourceId), SourceField.of(d, null).json());
            taken++;
        }
        if (taken > 0) {
            place(tenantId, sourceId, old.findDisplayOrder(tenantId, sourceId), SourceField::withOrder);
            place(tenantId, sourceId, old.findRecordTitleFields(tenantId, sourceId), SourceField::withTitle);
        }
        return taken;
    }
}
