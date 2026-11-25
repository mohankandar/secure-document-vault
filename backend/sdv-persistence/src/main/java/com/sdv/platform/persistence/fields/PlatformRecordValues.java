package com.sdv.platform.persistence.fields;

import com.documentplatform.crypto.CryptoError;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    public static final int PAGE_MAX = 100;

    private final JdbcTemplate db;
    private final CustomerId customer;
    private final PostgresFieldStore fields;
    private final PostgresValueStore store;
    private final LegalHolds holds;
    private final FieldValues values;
    private final Fingerprinter fingerprints;
    private final KeyService keys;
    private final BatchUnwrap batch;

    public PlatformRecordValues(DataSource dataSource, CustomerId customer, KeyService keys, BatchUnwrap batch) {
        this.db = new JdbcTemplate(dataSource);
        this.customer = customer;
        this.keys = keys;
        this.batch = batch;
        DataSource joined = new JoinedDataSource(dataSource);
        this.fields = new PostgresFieldStore(joined);
        this.store = new PostgresValueStore(joined);
        this.holds = this::held;
        this.values = values(keys);
        this.fingerprints = new Fingerprinter(keys);
    }

    private FieldValues values(KeyService service) {
        return new FieldValues(customer, fields, store, service, Clock.systemUTC(), holds);
    }

    private static FieldScope scope(UUID entity) {
        return FieldScope.of(entity, VeroProduct.CODE);
    }

    private static RecordRef ref(UUID entity, UUID source, UUID record) {
        return RecordRef.of(scope(entity), CaseFieldSql.recordType(source), record.toString());
    }

    /** A record with a document on hold keeps its values. A hold that can't be read is an exception, and the erase is refused. */
    boolean held(RecordRef record) {
        return Boolean.TRUE.equals(db.queryForObject("""
                select exists (select 1 from legal_holds h
                                 join documents d on d.tenant_id = h.tenant_id and d.document_id = h.document_id
                                 join submissions s on s.tenant_id = d.tenant_id and s.submission_id = d.submission_id
                                where h.tenant_id = ? and s.record_id = ? and h.released_at is null)
                """, Boolean.class, record.scope().entity(), UUID.fromString(record.recordId())));
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
                Map<String, List<FieldValue>> identity = new LinkedHashMap<>();
                primary.forEach((key, value) -> identity.put(key, List.of(value)));
                found = values.claim(ref(tenantId, sourceId, submissionId), identity).record();
            }
            UUID record = UUID.fromString(found.recordId());
            // Deliveries to one record go one after the other. Before the submission joins it: its foreign key then waits for nobody.
            db.queryForList("select record_id from records where tenant_id = ? and source_id = ? and record_id = ? for no key update", UUID.class, tenantId, sourceId, record);
            if (!record.equals(submissionId)) {
                int joined = db.update("update submissions set record_id = ? where tenant_id = ? and source_id = ? and submission_id = ?", record, tenantId, sourceId, submissionId);
                if (joined != 1) throw new InternalCheckFailed("The submission could not be added to its record.");
                db.update("delete from records where tenant_id = ? and source_id = ? and record_id = ?", tenantId, sourceId, submissionId);
            }
            if (!rest.isEmpty()) values.update(found, rest);
            return record;
        });
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
        Map<UUID, String> wrapped = new LinkedHashMap<>();
        for (UUID id : new LinkedHashSet<>(recordIds)) wrapped(ref(tenantId, sourceId, id)).ifPresent(w -> wrapped.put(id, w));
        if (wrapped.isEmpty()) return out;
        Map<String, byte[]> opened = Map.of();
        try {
            opened = batch.unwrap(customer, new LinkedHashSet<>(wrapped.values()));
            Map<String, byte[]> page = opened;
            // No further call: a key the batch didn't give is missing, and its record is left out.
            FieldValues reader = values(only((c, w) -> {
                byte[] key = customer.equals(c) ? page.get(w) : null;
                if (key == null) throw new CryptoException(CryptoError.CRYPTO_KEY_MISSING, "not in the page's batch");
                return key.clone();
            }));
            Map<String, SourceField> source = sourceFields(tenantId, sourceId);
            for (UUID id : wrapped.keySet()) {
                try {
                    out.put(id, opened(reader.readSensitive(ref(tenantId, sourceId, id)), source));
                } catch (CryptoException | FieldValuesException ex) {
                    log.warn("A record of a results page can't be opened: " + ex.getClass().getSimpleName());
                }
            }
            return out;
        } catch (RuntimeException ex) {
            // The list goes on without names; a key-service problem shows on the health page.
            log.warn("The names of a results page can't be opened: " + (ex instanceof CryptoException c ? c.code().code() : ex.getClass().getSimpleName()));
            return Map.of();
        } finally {
            opened.values().forEach(k -> Arrays.fill(k, (byte) 0));
        }
    }

    /** The record's wrapped data key as the key service takes it; asked of platform-fields itself, so its envelope format stays its own. */
    private Optional<String> wrapped(RecordRef ref) {
        if (store.find(ref).map(r -> r.envelope() == null).orElse(true)) return Optional.empty();
        String[] seen = new String[1];
        try {
            values(only((c, w) -> {
                if (customer.equals(c)) seen[0] = w;
                throw new CryptoException(CryptoError.CRYPTO_KEY_MISSING, "only looked at");
            })).readSensitive(ref);
        } catch (CryptoException | FieldValuesException ex) {
            // as made to fail
        }
        return Optional.ofNullable(seen[0]);
    }

    @FunctionalInterface
    private interface Unwrap { byte[] unwrap(CustomerId customer, String wrapped); }

    /** The key service with nothing but unwrap, and that from memory; what doesn't call it out (its ID, a version) stays. */
    private KeyService only(Unwrap unwrap) {
        return (KeyService) Proxy.newProxyInstance(KeyService.class.getClassLoader(), new Class<?>[]{KeyService.class}, (proxy, method, args) -> switch (method.getName()) {
            case "unwrap" -> unwrap.unwrap((CustomerId) args[0], (String) args[1]);
            case "id", "versionOf", "toString", "hashCode" -> {
                try {
                    yield method.invoke(keys, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getCause();
                }
            }
            default -> throw new CryptoException(CryptoError.CRYPTO_PERMISSION_DENIED, "not for a results page");
        });
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
