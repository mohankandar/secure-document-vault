package com.sdv.platform.web.fields;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.fields.values.RecordRef;
import com.documentplatform.fields.FieldScope;
import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.QuarantineLookup;
import com.sdv.platform.application.business.BusinessExperienceService;
import com.sdv.platform.application.business.BusinessExperienceView;
import com.sdv.platform.application.port.RecordValuesPort;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.KeyServiceDown;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.persistence.business.JdbcBusinessRecordRepository;
import com.sdv.platform.persistence.business.PrimaryIdentifierSql;
import com.sdv.platform.persistence.fields.PlatformFieldRepository;
import com.sdv.platform.persistence.fields.PlatformRecordValues;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0078, on a real PostgreSQL: records, their values, search and the key-service calls each of them makes. */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class PlatformRecordValuesPostgresTest {
    private final DriverManagerDataSource data = new DriverManagerDataSource(System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final JdbcTemplate db = new JdbcTemplate(data);
    private final NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(data);
    private final PlatformFieldRepository fields = new PlatformFieldRepository(data);
    private final JdbcFoundationRepository foundation = new JdbcFoundationRepository(named);
    private final JdbcBusinessRecordRepository records = new JdbcBusinessRecordRepository(named);
    private final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(data));
    private final CustomerId customer = CustomerId.of(UUID.randomUUID());
    private final InMemoryKeyService keys = new InMemoryKeyService().createKeysOnDemand();
    /** How many keys each batch asked the key service for. */
    private final List<Integer> batches = new ArrayList<>();
    private final KeyService seen = (KeyService) Proxy.newProxyInstance(KeyService.class.getClassLoader(), new Class<?>[]{KeyService.class}, (proxy, method, args) -> {
        if (method.getName().equals("unwrapAll")) batches.add(((List<?>) args[1]).size());
        try {
            return method.invoke(keys, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    });
    private final PlatformRecordValues values = new PlatformRecordValues(data, customer, seen);
    private final BusinessExperienceService business = new BusinessExperienceService(foundation, foundation, fields, records, foundation, QuarantineLookup.NONE, values);
    private final UUID entity = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();

    @BeforeEach
    void entity() {
        db.update("insert into tenants(tenant_id, tenant_key, display_name, status) values (?, ?, 'Records', 'ACTIVE')", entity, "records-" + entity);
        for (UUID s : List.of(a, b)) {
            db.update("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type, credential_reference) values (?, ?, ?, 'Source', 'ACTIVE', 'JWT_BEARER', 'x')", s, entity, "s-" + s);
        }
        field(a, "contractNumber", "Contract number", true, MetadataNormalization.DIGITS_ONLY, MetadataSearchMode.EXACT);
        field(a, "firstName", "First name", false, MetadataNormalization.TRIM, MetadataSearchMode.PARTIAL);
        field(a, "lastName", "Last name", false, MetadataNormalization.TRIM, MetadataSearchMode.PARTIAL);
        field(a, "loanType", "Loan type", false, MetadataNormalization.TRIM, MetadataSearchMode.PARTIAL);
        tx.executeWithoutResult(s -> fields.replaceRecordTitleFields(entity, a, List.of("firstName", "lastName")));
    }

    private void field(UUID source, String key, String label, boolean identifier, MetadataNormalization n, MetadataSearchMode mode) {
        Instant now = Instant.now();
        tx.executeWithoutResult(s -> fields.insert(new MetadataFieldDefinition(UUID.randomUUID(), entity, source, key, 1, label, MetadataFieldType.TEXT, identifier, identifier, true, n, mode,
                "{}", true, now, null, now)));
    }

    /** A delivery as intake makes it: the submission, then its values, in one transaction. Returns the submission. */
    private UUID deliver(UUID source, Map<String, String> sent) {
        UUID submission = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            db.update("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) values (?, ?, ?, ?, 1, 'COMPLETE')",
                    submission, entity, source, submission.toString());
            values.store(entity, source, submission, sent);
        });
        return submission;
    }

    private UUID recordOf(UUID submission) {
        return db.queryForObject("select record_id from submissions where submission_id = ?", UUID.class, submission);
    }

    private UUID document(UUID source, UUID submission, String level) {
        UUID id = UUID.randomUUID();
        db.update("insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type, content_sha256, content_size_bytes, status, available_at, sensitivity_level)"
                + " values (?, ?, ?, ?, ?, 'x.pdf', 'application/pdf', repeat('a', 64), 1, 'AVAILABLE', now(), ?)", id, entity, submission, source, id.toString(), level);
        return id;
    }

    private static Map<String, String> person(String contract, String first, String last) {
        return Map.of("contractNumber", contract, "firstName", first, "lastName", last, "loanType", "Mortgage " + contract.length());
    }

    private BusinessExperienceView.SearchResponse search(Map<String, String> criteria, String anywhere, Clearance clearance) {
        return business.search(entity, a, criteria, anywhere, null, null, null, null, criteria.isEmpty() && anywhere == null, 1, 50, "maya", "c", clearance);
    }

    @Test
    void theSameIdentifiersAreOneRecordAndTheLatestValuesReplaceTheEarlier() {
        UUID first = deliver(a, person("3000-0001", "Anna", "Patel"));
        UUID second = deliver(a, Map.of("contractNumber", "30000001", "lastName", "Patel-Okafor"));
        assertEquals(recordOf(first), recordOf(second), "the same contract written differently");
        assertEquals(first, recordOf(first), "the record keeps the first delivery's ID");
        assertEquals(1, db.queryForObject("select count(*) from records where tenant_id = ?", Integer.class, entity), "no stray record");
        assertEquals(Set.of("Anna", "Patel-Okafor"), Set.copyOf(values.open(entity, a, first).stream().map(RecordValuesPort.Opened::value).toList()));
        // Another contract, another system and no identifier at all are records of their own.
        assertNotEquals(first, recordOf(deliver(a, person("3000-0002", "Anna", "Patel"))));
        UUID alone = deliver(a, Map.of("lastName", "Patel")), aloneToo = deliver(a, Map.of("lastName", "Patel"));
        assertNotEquals(recordOf(alone), recordOf(aloneToo));
        field(b, "contractNumber", "Contract", true, MetadataNormalization.DIGITS_ONLY, MetadataSearchMode.EXACT);
        assertNotEquals(first, recordOf(deliver(b, Map.of("contractNumber", "30000001"))), "a record belongs to one system");
    }

    @Test
    void anIdentifierOfOneSystemCountsForEachSystemOfTheEntity() {
        field(b, "memberId", "Member", true, MetadataNormalization.TRIM, MetadataSearchMode.EXACT);
        field(a, "memberId", "Member", false, MetadataNormalization.TRIM, MetadataSearchMode.EXACT);
        UUID one = deliver(a, Map.of("contractNumber", "1", "memberId", "M-1"));
        UUID two = deliver(a, Map.of("contractNumber", "1", "memberId", "M-2"));
        UUID again = deliver(a, Map.of("contractNumber", "1", "memberId", "m-1"));
        assertNotEquals(recordOf(one), recordOf(two));
        assertEquals(recordOf(one), recordOf(again), "capitals don't matter");
        assertNotEquals(recordOf(one), recordOf(deliver(a, Map.of("contractNumber", "1"))), "a part of the identifiers is another record");
    }

    @Test
    void parallelDeliveriesOfANewRecordMakeOne() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int round = 0; round < 5; round++) {
                String contract = "777" + round;
                CountDownLatch go = new CountDownLatch(1);
                List<Future<UUID>> made = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    String name = "Name" + i;
                    made.add(pool.submit(() -> {
                        go.await();
                        return deliver(a, Map.of("contractNumber", contract, "lastName", name, "loanType", name));
                    }));
                }
                go.countDown();
                Set<UUID> found = new java.util.HashSet<>();
                for (Future<UUID> f : made) found.add(recordOf(f.get(60, TimeUnit.SECONDS)));
                assertEquals(1, found.size());
                UUID record = found.iterator().next();
                assertEquals(1, db.queryForObject("select count(*) from fld_record where entity_uuid = ? and record_id = ?", Integer.class, entity, record.toString()));
                assertEquals(1, db.queryForObject("select count(*) from records where tenant_id = ? and record_id in (select record_id from submissions where tenant_id = ? and record_id = ?)", Integer.class, entity, entity, record));
                // The readable and the sealed value are the same delivery's.
                assertEquals(values.open(entity, a, record).getFirst().value(), db.queryForObject("select v.display_value from fld_value v join fld_record r on r.record_uuid = v.record_uuid"
                        + " where r.entity_uuid = ? and r.record_id = ? and v.field_key = 'loanType'", String.class, entity, record.toString()));
            }
        }
        assertEquals(5, db.queryForObject("select count(*) from records where tenant_id = ?", Integer.class, entity), "no stray records");
    }

    @Test
    void aSensitiveValueIsNowhereReadable() {
        UUID record = recordOf(deliver(a, person("41", "Wilhelmina", "Zanzibari")));
        String all = db.queryForObject("select string_agg(t, ' ') from (select to_jsonb(v)::text t from fld_value v where v.entity_uuid = ? union all select to_jsonb(r)::text from fld_record r where r.entity_uuid = ?"
                + " union all select to_jsonb(s)::text from submissions s where s.tenant_id = ? union all select to_jsonb(e)::text from audit_events e where e.tenant_id = ?) x", String.class, entity, entity, entity, entity).toLowerCase();
        assertFalse(all.contains("wilhelmina") || all.contains("zanzibari"));
        assertFalse(values.open(entity, a, record).toString().contains("Wilhelmina"));
        assertTrue(records.findMetadataSnapshots(entity, a, List.of(record)).stream().noneMatch(v -> v.fieldKey().equals("lastName")));
    }

    @Test
    void openingARecordIsOneCallAndAResultsPageIsOneBatch() {
        for (int i = 0; i < 30; i++) document(a, deliver(a, person("5000" + i, "Anna" + i, "Patel" + i)), "STANDARD");
        int before = keys.calls();
        batches.clear();
        var page = search(Map.of(), "5000", Clearance.STANDARD_ONLY).records();
        assertEquals(0, page.size(), "an exact identifier isn't found by a part");
        page = search(Map.of(), "mortgage", Clearance.STANDARD_ONLY).records();
        assertEquals(30, page.size());
        assertTrue(page.stream().allMatch(r -> r.title().startsWith("Anna")), "the names stay in the list");
        assertEquals(List.of(30), batches, "one batch for the page, never one call per row");
        assertEquals(before + 1, keys.calls(), "one call, and nothing else is asked of the key service");

        before = keys.calls();
        var opened = business.record(entity, page.getFirst().recordRef(), "maya", "c", Clearance.STANDARD_ONLY);
        assertEquals(before + 1, keys.calls());
        assertEquals(Map.of("firstName", true, "lastName", true, "loanType", false, "contractNumber", false),
                opened.metadata().stream().collect(java.util.stream.Collectors.toMap(v -> v.fieldKey(), v -> v.sensitive())), "task 0079: the sensitive ones are marked");
        assertEquals(page.getFirst().title(), opened.title());
    }

    /** Task 0094: the key service takes {@link KeyService#MAX_BATCH} keys in a call; a longer page is refused before any call. */
    @Test
    void aPageIsNeverMoreThanOneCallTakes() {
        List<UUID> page = new ArrayList<>();
        for (int i = 0; i < KeyService.MAX_BATCH; i++) page.add(recordOf(deliver(a, person("8800" + i, "Anna", "Patel"))));
        assertEquals(KeyService.MAX_BATCH, values.openPage(entity, a, page).size());
        assertEquals(List.of(KeyService.MAX_BATCH), batches);
        page.add(recordOf(deliver(a, person("8899", "Anna", "Patel"))));
        int before = keys.calls();
        assertThrows(InternalCheckFailed.class, () -> values.openPage(entity, a, page));
        assertEquals(before, keys.calls());
        assertThrows(CodedRefusal.BadRequest.class, () -> business.search(entity, a, Map.of(), "mortgage", null, null, null, null, false, 1, KeyService.MAX_BATCH + 1, "maya", "c", Clearance.ALL));
    }

    /** Task 0094: a key that is another customer's, and a record with nothing sealed. */
    @Test
    void aRecordThatCantBeOpenedShowsItsIdentifierAndTheOthersTheirNames() {
        UUID bad = recordOf(deliver(a, person("8501", "Anna", "Patel"))), good = recordOf(deliver(a, person("8502", "Omar", "Haddad")));
        UUID plain = recordOf(deliver(a, Map.of("contractNumber", "8503", "loanType", "Mortgage")));
        for (UUID r : List.of(bad, good, plain)) document(a, r, "STANDARD");
        // The key of another customer's record in the bad one's place.
        CustomerId stranger = CustomerId.of(UUID.randomUUID());
        UUID theirs = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            db.update("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) values (?, ?, ?, ?, 1, 'COMPLETE')", theirs, entity, b, theirs.toString());
            new PlatformRecordValues(data, stranger, keys).store(entity, b, theirs, Map.of("firstName", "Eve"));
        });
        assertEquals(1, db.update("update fld_record set envelope = (select envelope from fld_record where entity_uuid = ? and record_id = ?) where entity_uuid = ? and record_id = ?",
                entity, theirs.toString(), entity, bad.toString()));
        assertEquals(Map.of("8501", "-", "8502", "Omar Haddad", "8503", "-"), titles(search(Map.of(), "mortgage", Clearance.ALL).records()));
        assertEquals(List.of(1), batches, "the bad one is refused before the key service is asked");
        batches.clear();
        assertEquals(List.of(), values.openPage(entity, a, List.of(plain, UUID.randomUUID())).getOrDefault(plain, List.of()));
        assertEquals(List.of(), batches, "nothing sealed, no call");
    }

    @Test
    void namesAreShownOnlyToWhoMayOpenTheDocuments() {
        document(a, deliver(a, person("61", "Anna", "Patel")), "STANDARD");
        document(a, deliver(a, person("62", "Omar", "Haddad")), "HIGHLY_CONFIDENTIAL");
        var page = search(Map.of(), "mortgage", Clearance.STANDARD_ONLY).records();
        assertEquals(Map.of("61", "Anna Patel", "62", "-"), titles(page), "the other falls back to its identifier");
        assertEquals(List.of(1), batches, "the other's key isn't asked for");
        String restricted = page.stream().filter(r -> r.identifiers().getFirst().value().equals("62")).findFirst().orElseThrow().recordRef();
        int before = keys.calls();
        var view = business.record(entity, restricted, "maya", "c", Clearance.STANDARD_ONLY);
        assertNull(view.title());
        assertTrue(view.metadata().stream().noneMatch(v -> v.fieldKey().equals("lastName")));
        assertEquals(before, keys.calls());
        assertEquals(Map.of("61", "Anna Patel", "62", "Omar Haddad"), titles(search(Map.of(), "mortgage", Clearance.ALL).records()));
    }

    private static Map<String, String> titles(List<BusinessExperienceView.RecordSummary> page) {
        Map<String, String> out = new HashMap<>();
        page.forEach(r -> out.put(r.identifiers().getFirst().value(), r.title() == null ? "-" : r.title()));
        return out;
    }

    @Test
    void aListWithoutTheKeyServiceGoesOnWithoutNames() {
        document(a, deliver(a, person("71", "Anna", "Patel")), "STANDARD");
        keys.down(true);
        var page = search(Map.of(), "mortgage", Clearance.ALL).records();
        assertNull(page.getFirst().title());
        assertEquals("71", page.getFirst().identifiers().getFirst().value());
        assertNull(business.record(entity, page.getFirst().recordRef(), "maya", "c", Clearance.ALL).title());
        assertThrows(KeyServiceDown.class, () -> search(Map.of("lastName", "Patel"), null, Clearance.ALL));
        assertThrows(KeyServiceDown.class, () -> deliver(a, person("72", "Anna", "Patel")));
        assertEquals(1, db.queryForObject("select count(*) from submissions where tenant_id = ?", Integer.class, entity), "nothing of the refused delivery is stored");
        keys.down(false);
    }

    @Test
    void sensitiveFieldsAreFoundByTheWholeValueOnlyAndWithTwoCalls() {
        document(a, deliver(a, person("81", "Anna", "Patel")), "STANDARD");
        document(a, deliver(a, person("82", "Anna", "Okafor")), "STANDARD");
        int before = keys.calls();
        batches.clear();
        assertEquals(List.of("81"), numbers(search(Map.of("firstName", "ANNA", "lastName", " patel "), null, Clearance.STANDARD_ONLY)));
        assertEquals(2, keys.calls() - before - batches.size(), "two calls for two sensitive fields");
        assertEquals(List.of(), numbers(search(Map.of("lastName", "Pat"), null, Clearance.STANDARD_ONLY)));
        assertEquals(List.of(), numbers(search(Map.of(), "Patel", Clearance.STANDARD_ONLY)), "free search leaves them out");
        assertEquals(Set.of("81", "82"), Set.copyOf(numbers(search(Map.of("loanType", "ortg"), null, Clearance.STANDARD_ONLY))), "contains, on a field that isn't sensitive");
        assertEquals(List.of(), numbers(search(Map.of("loanType", "o%g"), null, Clearance.STANDARD_ONLY)), "no wildcards");
        assertEquals(List.of("82"), numbers(search(Map.of("contractNumber", "8-2", "lastName", "Okafor"), null, Clearance.STANDARD_ONLY)));
        var schema = business.searchSchema(entity).sources().stream().filter(s -> s.sourceId().equals(a)).findFirst().orElseThrow().fields();
        assertTrue(schema.stream().filter(f -> f.fieldKey().equals("lastName")).allMatch(f -> f.sensitive() && f.searchMode() == MetadataSearchMode.EXACT));
        assertTrue(schema.stream().filter(f -> f.fieldKey().equals("loanType")).noneMatch(BusinessExperienceView.SearchField::sensitive));
    }

    /**
     * Task 0101 (VSEC-202): a sensitive value of a record above the person's level is "not found", exactly as a value
     * nobody has; who may open the record finds it. Above by a document, and above by the record's own level.
     */
    @Test
    void aSensitiveValueAboveThePersonsLevelIsNotFoundLikeAValueNobodyHas() {
        document(a, deliver(a, person("91", "Rae", "Lindqvist")), "HIGHLY_CONFIDENTIAL");
        UUID byRecord = deliver(a, person("92", "Rae", "Moreau"));
        document(a, byRecord, "STANDARD");
        db.update("insert into record_sensitivity_levels(record_level_id, tenant_id, source_id, record_id, sensitivity_level, reason, set_by, set_at, updated_at)"
                + " values (gen_random_uuid(), ?, ?, ?, 'HIGHLY_CONFIDENTIAL', 'Reason', 'ada', now(), now())", entity, a, recordOf(byRecord));
        document(a, deliver(a, person("93", "Rae", "Lindqvist")), "STANDARD");

        var nobody = search(Map.of("lastName", "Nobodyhere"), null, Clearance.STANDARD_ONLY);
        assertEquals(List.of(), nobody.records());
        assertEquals(nobody, search(Map.of("lastName", "Moreau"), null, Clearance.STANDARD_ONLY), "above by the record's level: the same answer");
        assertEquals(List.of("93"), numbers(search(Map.of("lastName", "Lindqvist"), null, Clearance.STANDARD_ONLY)),
                "only the record this person may open; the other is not counted or paged");
        var lindqvist = search(Map.of("lastName", "Lindqvist", "contractNumber", "91"), null, Clearance.STANDARD_ONLY);
        assertEquals(nobody, lindqvist, "above by a document: the same answer");

        assertEquals(List.of("92"), numbers(search(Map.of("lastName", "Moreau"), null, Clearance.ALL)), "who may open it finds it");
        assertEquals(Set.of("91", "93"), Set.copyOf(numbers(search(Map.of("lastName", "Lindqvist"), null, Clearance.ALL))));
        assertEquals(Set.of("91", "92", "93"), Set.copyOf(numbers(search(Map.of("loanType", "Mortgage 2"), null, Clearance.STANDARD_ONLY))),
                "a field that isn't sensitive finds them as before, each with its restricted level");
    }

    private static List<String> numbers(BusinessExperienceView.SearchResponse r) {
        return r.records().stream().map(x -> x.identifiers().getFirst().value()).toList();
    }

    @Test
    void withoutTheTrigramIndexContainsIsStartsWithAndSaysSo() {
        document(a, deliver(a, person("91", "Anna", "Patel")), "STANDARD");
        tx.executeWithoutResult(s -> {
            db.execute("alter index fld_value_partial rename to fld_value_partial_away");
            assertFalse(values.partialSearch());
            assertEquals(List.of("91"), numbers(search(Map.of("loanType", "mort"), null, Clearance.STANDARD_ONLY)));
            assertEquals(List.of(), numbers(search(Map.of("loanType", "ortg"), null, Clearance.STANDARD_ONLY)));
            var schema = business.searchSchema(entity).sources().stream().filter(x -> x.sourceId().equals(a)).findFirst().orElseThrow().fields();
            assertTrue(schema.stream().filter(f -> f.fieldKey().equals("loanType")).allMatch(BusinessExperienceView.SearchField::partialAsPrefix));
            s.setRollbackOnly();
        });
        assertTrue(values.partialSearch());
    }

    @Test
    void aRuleOfTheFieldsModuleIsTheSendersCodeAndNeverNamesTheValue() {
        var refused = assertThrows(CodedRefusal.BadRequest.class, () -> deliver(a, Map.of("contractNumber", "1", "loanType", "Secret\u0007word")));
        assertEquals("SDV-INT-009", refused.errorCode().code());
        assertFalse(refused.getMessage().contains("Secret"), refused.getMessage());
        assertEquals(0, db.queryForObject("select count(*) from submissions where tenant_id = ?", Integer.class, entity));
    }

    /** Task 0079: rank by rank gives the same order as ranking every record: best match, then newest; every page in turn; nothing twice. */
    @Test
    void searchingEverywhereIsBestMatchFirstThenNewestOnEveryPage() {
        String[][] rows = {{"901", "Big Auto loan"}, {"902", "auto"}, {"903", "Auto lease"}, {"auto", "Auto"}, {"904", "AUTO"}, {"905", "Autumn"}, {"906", "Semi auto"}, {"907", "Boat"}};
        field(b, "ref", "Ref", true, MetadataNormalization.TRIM, MetadataSearchMode.EXACT);
        field(b, "loanType", "Loan type", false, MetadataNormalization.TRIM, MetadataSearchMode.PARTIAL);
        for (int i = 0; i < rows.length; i++) {
            UUID s = deliver(b, Map.of("ref", rows[i][0], "loanType", rows[i][1]));
            db.update("update submissions set created_at = now() - ? * interval '1 hour' where submission_id = ?", rows.length - i, s);
        }
        List<String> all = List.of("auto", "904", "902", "903", "906", "901");
        for (int size : List.of(1, 2, 4, 50)) {
            List<String> got = new ArrayList<>();
            for (int page = 1; page <= 7; page++) {
                business.search(entity, b, Map.of(), "auto", null, null, null, null, false, page, size, "maya", "c", Clearance.STANDARD_ONLY).records()
                        .forEach(r -> got.add(r.identifiers().getFirst().value()));
            }
            assertEquals(all, got, "page size " + size);
        }
        // The repository's own pages, as deep as the results and beyond.
        var criterion = new com.sdv.platform.application.port.BusinessRecordRepository.AnywhereCriterion(List.of(
                new com.sdv.platform.application.port.BusinessRecordRepository.AnywhereField("ref", MetadataSearchMode.EXACT, List.of("auto"), true),
                new com.sdv.platform.application.port.BusinessRecordRepository.AnywhereField("loanType", MetadataSearchMode.PARTIAL, List.of("auto"), false)));
        for (int offset = 0; offset <= 7; offset++) {
            assertEquals(Math.max(0, Math.min(2, 6 - offset)), records.findMatchingSubmissions(entity, b, List.of(), criterion, null, null, null, null, false, offset, 2).size(), "offset " + offset);
        }
    }

    private UUID typed(String key, MetadataFieldType type) {
        Instant now = Instant.now();
        tx.executeWithoutResult(s -> fields.insert(new MetadataFieldDefinition(UUID.randomUUID(), entity, b, key, 1, key, type, false, false, true,
                MetadataNormalization.TRIM, MetadataSearchMode.EXACT, "{}", true, now, null, now)));
        return b;
    }

    private long found(String key, String value) {
        return business.search(entity, b, Map.of(key, value), null, null, null, null, null, false, 1, 50, "maya", "c", Clearance.STANDARD_ONLY).records().size();
    }

    /** Task 0079, as the contract says: types with the offset from UTC, letter case, and 512 characters. */
    @Test
    void typesOffsetsLetterCaseAndLengthAreAsTheContractSays() {
        typed("signedAt", MetadataFieldType.DATETIME);
        typed("active", MetadataFieldType.BOOLEAN);
        typed("caseId", MetadataFieldType.UUID);
        typed("closing", MetadataFieldType.DATE);
        typed("note", MetadataFieldType.TEXT);
        String long512 = "n".repeat(512);
        deliver(b, Map.of("signedAt", "2026-11-21T10:15:30+02:00", "active", "TRUE", "caseId", "5E7D0000-0000-4000-8000-0000000000A1", "closing", "2026-11-21", "note", long512));
        assertEquals(1, found("active", "true"));
        assertEquals(1, found("caseId", "5e7d0000-0000-4000-8000-0000000000a1"));
        assertEquals(1, found("closing", "2026-11-21"));
        assertEquals(1, found("signedAt", "2026-11-21T10:15:30+02:00"));
        assertEquals(1, found("note", long512.toUpperCase()));
        for (Map<String, String> bad : List.of(Map.of("signedAt", "2026-11-21T10:15:30"), Map.of("active", "maybe"), Map.of("caseId", "1-1-1-1-1"), Map.of("note", long512 + "n"))) {
            assertEquals("SDV-INT-009", assertThrows(CodedRefusal.BadRequest.class, () -> deliver(b, bad), bad.keySet().toString()).errorCode().code());
        }
    }

    @Test
    void aHoldOnAnyDocumentOfTheRecordHoldsItsValues() throws Exception {
        UUID first = deliver(a, person("101", "Anna", "Patel")), second = deliver(a, person("101", "Anna", "Patel"));
        UUID doc = document(a, second, "STANDARD");
        RecordRef ref = RecordRef.of(FieldScope.of(entity, "SDV"), "s-" + a, recordOf(first).toString());
        var held = PlatformRecordValues.class.getDeclaredMethod("held", RecordRef.class);
        held.setAccessible(true);
        assertEquals(false, held.invoke(values, ref));
        UUID hold = UUID.randomUUID();
        db.update("insert into legal_holds(hold_id, tenant_id, document_id, reason, placed_by, placed_at) values (?, ?, ?, 'Court order', 'ada', now())", hold, entity, doc);
        assertEquals(true, held.invoke(values, ref));
        db.update("update legal_holds set released_by = 'ada', released_at = now() where hold_id = ?", hold);
        assertEquals(false, held.invoke(values, ref));
    }

    @Test
    void aSensitiveIdentifierIsNotShownOnAdminPagesAndStillMakesTheRecord() {
        field(b, "taxId", "Tax ID", true, MetadataNormalization.TRIM, MetadataSearchMode.EXACT);
        field(b, "branchRef", "Branch", true, MetadataNormalization.TRIM, MetadataSearchMode.EXACT);
        tx.executeWithoutResult(s -> fields.replaceDisplayOrder(entity, b, List.of("taxId", "branchRef")));
        tx.executeWithoutResult(s -> fields.setSensitive(entity, "taxId", true, "admin"));
        UUID one = deliver(b, Map.of("taxId", "AB-123", "branchRef", "North")), two = deliver(b, Map.of("taxId", "ab-123", "branchRef", "North"));
        assertEquals(recordOf(one), recordOf(two));
        assertNotEquals(recordOf(one), recordOf(deliver(b, Map.of("taxId", "AB-124", "branchRef", "North"))));
        assertEquals("North", db.queryForObject("select pid.stored_value from submissions s " + PrimaryIdentifierSql.join("s.tenant_id", "s.submission_id") + " where s.submission_id = ?", String.class, one));
        UUID only = deliver(b, Map.of("taxId", "AB-125"));
        assertNull(db.queryForObject("select pid.stored_value from submissions s " + PrimaryIdentifierSql.join("s.tenant_id", "s.submission_id") + " where s.submission_id = ?", String.class, only));
    }

    @Test
    void theSchemaKeepsARecordWithItsSystemAndASubmissionWithARecord() {
        UUID submission = deliver(a, Map.of("contractNumber", "1"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.update("update submissions set record_id = ? where submission_id = ?", UUID.randomUUID(), submission));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.update("update submissions set record_id = null where submission_id = ?", submission));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.update("update records set source_id = ? where record_id = ?", b, submission));
    }
}
