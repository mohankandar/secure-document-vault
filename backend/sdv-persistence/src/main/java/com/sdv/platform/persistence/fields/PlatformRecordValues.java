package com.sdv.platform.persistence.fields;

import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldValidator;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.jdbc.PostgresFieldStore;
import com.documentplatform.fields.jdbc.PostgresValueStore;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.fields.values.FieldValuesError;
import com.documentplatform.fields.values.FieldValuesException;
import com.documentplatform.fields.values.LegalHolds;
import com.documentplatform.fields.values.RecordRef;
import com.documentplatform.fields.values.RecordValues;
import com.documentplatform.fields.values.SensitiveRead;
import com.documentplatform.fields.values.ValueStore;
import com.sdv.platform.application.port.RecordValuesPort;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.KeyServiceDown;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.identity.VeroProduct;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Task 0078: the values of Vero's records, in platform-fields. The record's identity is all of its primary values together (a field that is
 * an identifier for one sending system of the entity counts for each). Sensitive values stay sealed under the record's own key.
 */
public class PlatformRecordValues implements RecordValuesPort {
    private static final Log log = LogFactory.getLog(PlatformRecordValues.class);
    /** Records opened in one call (design chat, 2026-11-25). */
    public static final int PAGE_MAX = KeyService.MAX_BATCH;

    private final JdbcTemplate db;
    private final CustomerId customer;
    private final PostgresFieldStore fields;
    private final PostgresValueStore store;
    private final LegalHolds holds;
    private final FieldValues values;
    private final Fingerprinter fingerprints;

    public PlatformRecordValues(DataSource dataSource, CustomerId customer, KeyService keys) {
        this.db = new JdbcTemplate(dataSource);
        this.customer = customer;
        DataSource joined = new JoinedDataSource(dataSource);
        this.fields = new PostgresFieldStore(joined);
        this.store = new PostgresValueStore(joined);
        this.holds = this::held;
        this.values = new FieldValues(customer, fields, store, keys, Clock.systemUTC(), holds);
        this.fingerprints = new Fingerprinter(keys);
    }

    private static FieldScope scope(UUID entity) {
        return FieldScope.of(entity, VeroProduct.CODE);
    }

    private static RecordRef ref(UUID entity, UUID source, UUID record) {
        return RecordRef.of(scope(entity), CaseFieldSql.recordType(source), record.toString());
    }

    /** Task 0080: for the erase log's handler of case fields, which is the only one that may erase values. */
    public FieldValues values() {
        return values;
    }

    public ValueStore store() {
        return store;
    }

    public LegalHolds holds() {
        return holds;
    }

    /**
     * A record with a document on hold or quarantined keeps its values; a document already erased keeps nothing (a restored database can
     * still have its hold). A hold that can't be read is an exception, and the erase is refused. */
    boolean held(RecordRef record) {
        return Boolean.TRUE.equals(db.queryForObject("""
                select exists (select 1 from legal_holds h
                                 join documents d on d.tenant_id = h.tenant_id and d.document_id = h.document_id
                                 join submissions s on s.tenant_id = d.tenant_id and s.submission_id = d.submission_id
                                where h.tenant_id = ? and s.record_id = ? and h.released_at is null and d.status <> 'EXPIRED_DELETED'
                                union all
                                select 1 from quarantined_documents q
                                 join documents d on d.tenant_id = q.tenant_id and d.document_id = q.document_id
                                 join submissions s on s.tenant_id = d.tenant_id and s.submission_id = d.submission_id
                                where q.tenant_id = ? and s.record_id = ? and q.released_at is null and d.status <> 'EXPIRED_DELETED')
                """, Boolean.class, record.scope().entity(), UUID.fromString(record.recordId()), record.scope().entity(), UUID.fromString(record.recordId())));
    }

    private Map<String, FieldSpec> active(UUID entity) {
        Map<String, FieldSpec> out = new LinkedHashMap<>();
        for (FieldDefinition d : fields.list(scope(entity))) if (d.active()) out.put(d.key().value(), d.spec());
        return out;
    }

    // ---- intake

    @Override
    public UUID store(UUID tenantId, UUID sourceId, UUID submissionId, Map<String, String> sent) {
        Map<String, FieldSpec> specs = active(tenantId);
        Map<String, FieldValue> primary = new LinkedHashMap<>();
        Map<String, List<FieldValue>> rest = new LinkedHashMap<>();
        sent.forEach((key, value) -> {
            if (value == null || value.isBlank()) return;
            FieldSpec spec = specs.get(key);
            if (spec == null) throw new InternalCheckFailed("A field of the delivery has no definition.");
            if (spec.primary()) primary.put(key, FieldValue.text(value));
            else rest.put(key, List.of(FieldValue.text(value)));
        });
        return coded(() -> {
            if (primary.isEmpty()) {
                if (!rest.isEmpty()) values.claim(ref(tenantId, sourceId, submissionId), rest);
                return submissionId;
            }
            RecordRef found = values.findByPrimary(scope(tenantId), CaseFieldSql.recordType(sourceId), primary).orElse(null);
            if (found == null) {
                // Only the identifiers: a record made meanwhile by another delivery is joined, not refused for its other values.
                found = values.claim(ref(tenantId, sourceId, submissionId), identity(primary)).record();
            }
            UUID record = UUID.fromString(found.recordId());
            // Deliveries to one record go one after the other. Before the submission joins it: its foreign key then waits for nobody.
            lockRecord(tenantId, sourceId, record);
            if (store.find(found).isEmpty()) {
                // Task 0080: its values were erased while this delivery waited (its last document was gone). The identifiers are free again.
                found = values.claim(ref(tenantId, sourceId, submissionId), identity(primary)).record();
                record = UUID.fromString(found.recordId());
                lockRecord(tenantId, sourceId, record);
            }
            if (!record.equals(submissionId)) {
                int joined = db.update("update submissions set record_id = ? where tenant_id = ? and source_id = ? and submission_id = ?", record, tenantId, sourceId, submissionId);
                if (joined != 1) throw new InternalCheckFailed("The submission could not be added to its record.");
                db.update("delete from records where tenant_id = ? and source_id = ? and record_id = ?", tenantId, sourceId, submissionId);
            }
            if (!rest.isEmpty()) values.update(found, rest);
            return record;
        });
    }

    private static Map<String, List<FieldValue>> identity(Map<String, FieldValue> primary) {
        Map<String, List<FieldValue>> out = new LinkedHashMap<>();
        primary.forEach((key, value) -> out.put(key, List.of(value)));
        return out;
    }

    private void lockRecord(UUID tenantId, UUID sourceId, UUID record) {
        db.queryForList("select record_id from records where tenant_id = ? and source_id = ? and record_id = ? for no key update", UUID.class, tenantId, sourceId, record);
    }

    // ---- opening

    private Map<String, SourceField> sourceFields(UUID entity, UUID source) {
        Map<String, SourceField> out = new HashMap<>();
        db.query("select f.field_key, x.value from fld_field_definition f join fld_field_extension x on x.definition_id = f.definition_id and x.name = ? where f.entity_uuid = ? and f.product_code = ?",
                rs -> { out.put(rs.getString(1), SourceField.parse(rs.getString(2))); }, SourceField.name(source), entity, VeroProduct.CODE);
        return out;
    }

    private static List<Opened> opened(RecordValues record, Map<String, SourceField> source) {
        List<Opened> out = new ArrayList<>();
        record.fields().forEach((key, field) -> {
            if (!field.sensitive() || field.values().isEmpty()) return;
            SourceField s = source.get(key.value());
            if (s == null || !(field.values().getFirst().display() instanceof FieldValue.Text text)) return;
            out.add(new Opened(key.value(), s.label(), text.value(), s.identifier()));
        });
        return out;
    }

    @Override
    public List<Opened> open(UUID tenantId, UUID sourceId, UUID recordId) {
        RecordRef ref = ref(tenantId, sourceId, recordId);
        if (store.find(ref).map(r -> r.envelope() == null).orElse(true)) return List.of();
        return coded(() -> opened(values.readSensitive(ref), sourceFields(tenantId, sourceId)));
    }

    @Override
    public Map<UUID, List<Opened>> openPage(UUID tenantId, UUID sourceId, Collection<UUID> recordIds) {
        Map<UUID, List<Opened>> out = new LinkedHashMap<>();
        if (recordIds.size() > PAGE_MAX) throw new InternalCheckFailed("A page of records is too long to open.");
        List<RecordRef> refs = new LinkedHashSet<>(recordIds).stream().map(id -> ref(tenantId, sourceId, id)).toList();
        if (refs.isEmpty()) return out;
        try {
            List<SensitiveRead> page = values.readSensitive(refs);
            Map<String, SourceField> source = sourceFields(tenantId, sourceId);
            for (SensitiveRead read : page) {
                if (read.ok()) out.put(UUID.fromString(read.ref().recordId()), opened(read.values(), source));
                else if (!noValues(read.failure())) {
                    log.warn("A record of a results page can't be opened: " + read.code().map(c -> c.code()).orElse(read.failure().getClass().getSimpleName()));
                }
            }
            return out;
        } catch (RuntimeException ex) {
            // The list goes on without names; a key-service problem shows on the health page.
            log.warn("The names of a results page can't be opened: " + (ex instanceof CryptoException c ? c.code().code() : ex.getClass().getSimpleName()));
            return Map.of();
        }
    }

    /** A record whose deliveries had no values: nothing to open, no failure. */
    private static boolean noValues(RuntimeException ex) {
        return ex instanceof FieldValuesException f && f.code() == FieldValuesError.VALUES_RECORD_UNKNOWN;
    }

    // ---- search

    @Override
    public Set<String> sensitiveKeys(UUID tenantId) {
        Set<String> out = new LinkedHashSet<>();
        active(tenantId).forEach((key, spec) -> { if (spec.sensitive()) out.add(key); });
        return out;
    }

    @Override
    public Map<String, List<String>> fingerprints(UUID tenantId, Map<String, String> texts) {
        if (texts.isEmpty()) return Map.of();
        Map<String, FieldSpec> specs = active(tenantId);
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        List<Fingerprinter.Input> inputs = new ArrayList<>();
        texts.forEach((key, text) -> {
            FieldSpec spec = specs.get(key);
            if (spec == null || !spec.sensitive()) throw new InternalCheckFailed("A fingerprint needs a sensitive field.");
            out.put(key, new ArrayList<>());
            FieldValidator.Checked checked = FieldValidator.check(spec, FieldValue.text(text));
            if (!checked.ok()) return; // a value the field can't have: nothing equals it
            order.add(key);
            inputs.add(new Fingerprinter.Input("fld:" + tenantId + ":" + VeroProduct.CODE + ":" + spec.key().folded(), checked.valid().normalisedText()));
        });
        if (inputs.isEmpty()) return out;
        return coded(() -> {
            for (int version : fingerprints.usableVersions(customer)) {
                List<Fingerprinter.Fingerprint> made = fingerprints.ofAll(customer, version, inputs);
                for (int i = 0; i < made.size(); i++) out.get(order.get(i)).add(made.get(i).text());
            }
            return out;
        });
    }

    @Override
    public boolean partialSearch() {
        return store.partialSearchAvailable();
    }

    /** platform-fields' and the key service's refusals as Vero's; their sentences name keys, labels and rules, never a value. */
    private static <T> T coded(Supplier<T> work) {
        try {
            return work.get();
        } catch (CryptoException ex) {
            throw new KeyServiceDown(ex.code().code());
        } catch (FieldValuesException ex) {
            if (ex.code() == FieldValuesError.VALUES_INVALID && !ex.problems().isEmpty()) {
                var p = ex.problems().getFirst();
                throw new CodedRefusal.BadRequest(SdvErrorCode.INT_009, "Metadata field '" + p.key().value() + "': " + p.message(), "field=" + p.key().value() + " code=" + p.code());
            }
            throw new InternalCheckFailed("The case field values could not be stored or read: " + ex.code());
        }
    }
}
