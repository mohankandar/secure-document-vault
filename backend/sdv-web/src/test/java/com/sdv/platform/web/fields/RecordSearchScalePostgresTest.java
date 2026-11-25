package com.sdv.platform.web.fields;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.QuarantineLookup;
import com.sdv.platform.application.business.BusinessExperienceService;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.persistence.business.JdbcBusinessRecordRepository;
import com.sdv.platform.persistence.fields.PlatformFieldRepository;
import com.sdv.platform.persistence.fields.PlatformRecordValues;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0078: record search with encrypted names, SDV_IT_RECORDS records (100,000). Writes target/record-search-times.txt. */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class RecordSearchScalePostgresTest {
    private static final int N = Integer.parseInt(System.getenv().getOrDefault("SDV_IT_RECORDS", "100000"));
    /** A search page, the Business Users' first screen. */
    private static final long LIMIT_MS = 3_000;
    private final DriverManagerDataSource data = new DriverManagerDataSource(System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final JdbcTemplate db = new JdbcTemplate(data);
    private final NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(data);
    private final PlatformFieldRepository fields = new PlatformFieldRepository(data);
    private final JdbcFoundationRepository foundation = new JdbcFoundationRepository(named);
    private final InMemoryKeyService keys = new InMemoryKeyService().createKeysOnDemand();
    private final CustomerId customer = CustomerId.of(UUID.randomUUID());
    private final PlatformRecordValues values = new PlatformRecordValues(data, customer, keys, (c, wrapped) -> {
        Map<String, byte[]> out = new HashMap<>();
        for (String w : wrapped) out.put(w, keys.unwrap(c, w));
        return out;
    });
    private final BusinessExperienceService business = new BusinessExperienceService(foundation, foundation, fields, new JdbcBusinessRecordRepository(named), foundation, QuarantineLookup.NONE, values);
    private final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(data));
    private final UUID entity = UUID.randomUUID(), source = UUID.randomUUID();
    private final List<String> lines = new ArrayList<>();

    private void field(String key, String label, boolean identifier, MetadataNormalization n, MetadataSearchMode mode) {
        Instant now = Instant.now();
        tx.executeWithoutResult(s -> fields.insert(new MetadataFieldDefinition(UUID.randomUUID(), entity, source, key, 1, label, MetadataFieldType.TEXT, identifier, identifier, true, n, mode,
                "{}", true, now, null, now)));
    }

    /** Median of three after one warm-up, within the limit. */
    private int timed(String what, Map<String, String> criteria, String anywhere) {
        long[] t = new long[3];
        int found = 0;
        for (int i = -1; i < 3; i++) {
            long start = System.nanoTime();
            found = business.search(entity, source, criteria, anywhere, null, null, null, null, false, 1, 25, "maya", "c", Clearance.STANDARD_ONLY).records().size();
            if (i >= 0) t[i] = (System.nanoTime() - start) / 1_000_000;
        }
        Arrays.sort(t);
        lines.add("%-62s %6d ms  %d rows".formatted(what, t[1], found));
        assertTrue(t[1] < LIMIT_MS, what + ": " + t[1] + " ms");
        return found;
    }

    @Test
    void searchStaysQuickWithEncryptedNames() throws Exception {
        db.update("insert into tenants(tenant_id, tenant_key, display_name, status) values (?, ?, 'Scale', 'ACTIVE')", entity, "scale-" + entity);
        db.update("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) values (?, ?, 'loans', 'Loans', 'ACTIVE', 'JWT_BEARER')", source, entity);
        field("contractNumber", "Contract number", true, MetadataNormalization.DIGITS_ONLY, MetadataSearchMode.EXACT);
        field("lastName", "Last name", false, MetadataNormalization.TRIM, MetadataSearchMode.PARTIAL);
        field("loanType", "Loan type", false, MetadataNormalization.TRIM, MetadataSearchMode.PARTIAL);
        tx.executeWithoutResult(s -> fields.replaceRecordTitleFields(entity, source, List.of("lastName")));
        String patel = values.fingerprints(entity, Map.of("lastName", "Patel")).get("lastName").getFirst();
        String rows = "(select n, md5('" + entity + "' || n)::uuid as id from generate_series(1, " + N + ") n)";
        db.execute("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status, created_at, last_activity_at) select id, '" + entity + "', '" + source
                + "', 's-' || n, 1, 'COMPLETE', now() - n * interval '1 second', now() from " + rows + " s");
        db.execute("insert into fld_record(record_uuid, customer_id, entity_uuid, product_code, record_type, record_id, version, created_at, updated_at) select id, '" + customer.value() + "', '" + entity
                + "', 'SDV', 's-" + source + "', id::text, 1, now(), now() from " + rows + " s");
        String head = "insert into fld_value(record_uuid, customer_id, entity_uuid, product_code, record_type, field_key, position, sensitive, display_value, normalised_value, search_value, sealed_value, fingerprint) select id, '"
                + customer.value() + "', '" + entity + "', 'SDV', 's-" + source + "', ";
        db.execute(head + "'contractNumber', 0, false, v, v, v, null, null from (select id, (700000000000 + n)::text v from " + rows + " s) x");
        db.execute(head + "'loanType', 0, false, v, v, lower(v), null, null from (select id, case when n % 1000 = 7 then 'Bridge loan ' else 'Mortgage ' end || n v from " + rows + " s) x");
        db.execute(head + "'lastName', 0, true, null, null, null, 'pci1.' || md5(id::text), case when n % 1000 = 0 then '" + patel + "' else 'f1.' || left(md5(id::text) || md5(n::text), 43) end from " + rows + " s");
        // The newest 25 are real deliveries, so a page opens real names.
        for (int i = 0; i < 25; i++) {
            UUID submission = UUID.randomUUID();
            String number = "80000000000" + i;
            tx.executeWithoutResult(s -> {
                db.update("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) values (?, ?, ?, ?, 1, 'COMPLETE')", submission, entity, source, number);
                values.store(entity, source, submission, Map.of("contractNumber", number, "lastName", "Okafor", "loanType", "Mortgage"));
            });
        }
        db.execute("analyze");

        lines.add("Record search (task 0078): " + N + " records with an encrypted name. A page of 25, the median of 3 after one warm-up.");
        assertEquals(1, timed("exact, identifier", Map.of("contractNumber", "7000-0005-0000"), null));
        assertEquals(25, timed("exact, sensitive name (1 in 1,000)", Map.of("lastName", "patel"), null));
        assertEquals(0, timed("exact, sensitive name, none has it", Map.of("lastName", "Nobody"), null));
        assertEquals(25, timed("contains, rare (1 in 1,000)", Map.of("loanType", "ridge"), null));
        assertEquals(25, timed("contains, nearly every record; names of the page opened", Map.of("loanType", "ortgage"), null));
        assertEquals(0, timed("contains, none has it", Map.of("loanType", "zeppelin"), null));
        assertEquals(1, timed("sensitive name and identifier", Map.of("lastName", "Patel", "contractNumber", "700000005000"), null));
        timed("everywhere, one record", Map.of(), "700000050000");
        timed("everywhere, nearly every record", Map.of(), "mortgage");
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target", "record-search-times.txt"), lines);
        lines.forEach(System.out::println);
    }
}
