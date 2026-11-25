package com.sdv.platform.web.fields;

import com.sdv.platform.application.port.CaseFieldsPort;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.SetupProblem;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.persistence.fields.PlatformFieldRepository;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0077, on a real PostgreSQL: the definitions are the one truth, the old rows only their copy, and both are stored together. */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class PlatformFieldsPostgresTest {
    private final DriverManagerDataSource data = new DriverManagerDataSource(System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final JdbcTemplate db = new JdbcTemplate(data);
    private final JdbcFoundationRepository old = new JdbcFoundationRepository(new NamedParameterJdbcTemplate(data));
    private final PlatformFieldRepository fields = new PlatformFieldRepository(old, data);
    private final TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(data));
    private final UUID entity = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();

    @BeforeEach
    void entity() {
        db.update("insert into tenants(tenant_id, tenant_key, display_name, status) values (?, ?, 'Fields', 'ACTIVE')", entity, "fields-" + entity);
        for (UUID s : List.of(a, b)) {
            db.update("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type, credential_reference) values (?, ?, ?, 'Source', 'ACTIVE', 'JWT_BEARER', 'x')", s, entity, "s-" + s);
        }
    }

    private MetadataFieldDefinition field(UUID source, String key, String label, MetadataFieldType type, boolean identifier, String rules) {
        Instant now = Instant.now();
        int version = old.findCurrent(entity, source, key).map(d -> d.version() + 1).orElse(1);
        return new MetadataFieldDefinition(UUID.randomUUID(), entity, source, key, version, label, type, identifier, identifier, true,
                MetadataNormalization.TRIM, MetadataSearchMode.EXACT, rules, true, now, null, now);
    }

    private void save(MetadataFieldDefinition d) {
        tx.executeWithoutResult(s -> {
            fields.retire(entity, d.sourceId(), d.fieldKey(), d.effectiveAt());
            fields.insert(d);
        });
    }

    /** A stored value, as a delivery leaves it. */
    private void value(MetadataFieldDefinition d) {
        UUID submission = UUID.randomUUID();
        MetadataFieldDefinition current = old.findCurrent(entity, d.sourceId(), d.fieldKey()).orElseThrow();
        db.update("insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status) values (?, ?, ?, ?, 1, 'COMPLETE')",
                submission, entity, d.sourceId(), submission.toString());
        db.update("insert into business_metadata_values(metadata_value_id, tenant_id, source_id, submission_id, definition_id, field_key, definition_version, stored_value, normalized_value) values (?, ?, ?, ?, ?, ?, ?, 'x', 'x')",
                UUID.randomUUID(), entity, d.sourceId(), submission, current.definitionId(), d.fieldKey(), current.version());
    }

    private static SdvErrorCode code(org.junit.jupiter.api.function.Executable work) {
        return assertInstanceOf(CodedRefusal.class, assertThrows(RuntimeException.class, work)).errorCode();
    }

    @Test
    void aFieldIsReadBackAsItWasSavedAndEachSystemKeepsItsOwnSettings() {
        save(field(a, "contractNumber", "Contract Number", MetadataFieldType.TEXT, true, "{\"maxLength\":20}"));
        save(field(b, "contractNumber", "Contract number", MetadataFieldType.TEXT, true, "{}"));
        MetadataFieldDefinition first = fields.findCurrent(entity, a, "contractNumber").orElseThrow(), second = fields.findCurrent(entity, b, "contractNumber").orElseThrow();
        assertEquals("Contract Number", first.displayLabel());
        assertEquals("Contract number", second.displayLabel());
        assertTrue(first.businessIdentifier() && first.required() && first.searchable());
        assertEquals("{\"maxLength\":20}", second.validationRulesJson(), "a system that starts to send a field takes the field's rules");
        assertEquals(MetadataNormalization.TRIM, first.normalization());
        assertEquals(1, fields.fields(entity).size());
        assertTrue(fields.fields(entity).getFirst().primary());

        // Every type survives, and what intake reads is the definition, not the copy.
        for (MetadataFieldType t : MetadataFieldType.values()) {
            save(field(a, "t" + t, "Type " + t, t, false, "{}"));
            assertEquals(t, fields.findCurrent(entity, a, "t" + t).orElseThrow().fieldType());
        }
        db.update("update metadata_field_definitions set field_type = 'DATE', business_identifier = false, display_label = 'Changed' where tenant_id = ? and source_id = ? and field_key = 'contractNumber'", entity, a);
        MetadataFieldDefinition again = fields.findCurrent(entity, a, "contractNumber").orElseThrow();
        assertEquals(MetadataFieldType.TEXT, again.fieldType());
        assertTrue(again.businessIdentifier());
        assertEquals("Contract Number", again.displayLabel());
    }

    @Test
    void theOrderAndTheTitleAreTheSystemsOwn() {
        for (String k : List.of("one", "two", "three")) save(field(a, k, k, MetadataFieldType.TEXT, false, "{}"));
        tx.executeWithoutResult(s -> fields.replaceDisplayOrder(entity, a, List.of("three", "one", "two")));
        tx.executeWithoutResult(s -> fields.replaceRecordTitleFields(entity, a, List.of("two")));
        assertEquals(List.of("three", "one", "two"), fields.findDisplayOrder(entity, a));
        assertEquals(List.of("two"), fields.findRecordTitleFields(entity, a));
        assertEquals(List.of(), fields.findDisplayOrder(entity, b));
        assertEquals(fields.findDisplayOrder(entity, a), old.findDisplayOrder(entity, a), "the copy for the old reads");
        save(field(a, "one", "One more", MetadataFieldType.TEXT, false, "{}"));
        assertEquals(List.of("three", "one", "two"), fields.findDisplayOrder(entity, a), "a saved label keeps the order");
    }

    @Test
    void whatValuesLockStaysLockedAndNothingIsStoredOfARefusedChange() {
        MetadataFieldDefinition d = field(a, "member", "Member", MetadataFieldType.TEXT, true, "{}");
        save(d);
        assertFalse(fields.fields(entity).getFirst().locked());
        value(d);
        assertTrue(fields.fields(entity).getFirst().locked());
        assertEquals(SdvErrorCode.FLD_002, code(() -> save(field(a, "member", "Member", MetadataFieldType.DATE, true, "{}"))));
        assertEquals(SdvErrorCode.FLD_002, code(() -> save(field(a, "member", "Member", MetadataFieldType.TEXT, false, "{}"))));
        assertEquals(1, fields.findCurrent(entity, a, "member").orElseThrow().version());
        save(field(a, "member", "Member ID", MetadataFieldType.TEXT, true, "{}"));
        assertEquals("Member ID", fields.findCurrent(entity, a, "member").orElseThrow().displayLabel());
        assertEquals(SdvErrorCode.FLD_003, code(() -> save(field(b, "Member", "Other capitals", MetadataFieldType.TEXT, false, "{}"))));
        assertEquals(SdvErrorCode.FLD_001, code(() -> save(field(a, "due", "Due", MetadataFieldType.DATE, false, "{\"pattern\":\"x\"}"))));
        assertTrue(old.findCurrent(entity, a, "due").isEmpty());
        // A key has one type, whichever system sends it.
        save(field(a, "due", "Due", MetadataFieldType.DATE, false, "{}"));
        assertEquals(SdvErrorCode.FLD_003, code(() -> save(field(b, "due", "Due", MetadataFieldType.TEXT, false, "{}"))));
    }

    @Test
    void namesAreSensitiveUntilSwitchedOffBeforeDataArrives() {
        MetadataFieldDefinition name = field(a, "firstName", "First Name", MetadataFieldType.TEXT, false, "{}"), loan = field(a, "loanType", "Loan type", MetadataFieldType.TEXT, false, "{}");
        save(name);
        save(loan);
        assertTrue(state("firstName").sensitive());
        assertFalse(state("loanType").sensitive());
        tx.executeWithoutResult(s -> fields.setSensitive(entity, "firstName", false, "admin"));
        assertFalse(state("firstName").sensitive());
        tx.executeWithoutResult(s -> fields.setSensitive(entity, "firstName", true, "admin"));
        save(field(a, "firstName", "Given name", MetadataFieldType.TEXT, false, "{}"));
        assertTrue(state("firstName").sensitive(), "a saved label keeps it");
        value(name);
        assertEquals(SdvErrorCode.FLD_002, code(() -> tx.executeWithoutResult(s -> fields.setSensitive(entity, "firstName", false, "admin"))));
    }

    private CaseFieldsPort.FieldState state(String key) {
        return fields.fields(entity).stream().filter(f -> f.fieldKey().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void aFailedTransactionLeavesNeitherTheDefinitionNorTheCopy() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            fields.insert(field(a, "gone", "Gone", MetadataFieldType.TEXT, false, "{}"));
            assertEquals(1, fields.fields(entity).size(), "seen inside");
            throw new IllegalStateException("after the write");
        }));
        assertEquals(List.of(), fields.fields(entity));
        assertTrue(old.findCurrent(entity, a, "gone").isEmpty());
    }

    @Test
    void anImportIsReportedFirstAndTheFileIsTheExportedOne() {
        save(field(a, "contractNumber", "Contract Number", MetadataFieldType.TEXT, true, "{}"));
        String file = fields.exportFile(entity);
        assertTrue(file.contains("platform-fields/1"), file);
        assertEquals(List.of("UNCHANGED"), fields.previewImport(entity, file).stream().map(CaseFieldsPort.ImportLine::outcome).toList());
        UUID other = UUID.randomUUID();
        db.update("insert into tenants(tenant_id, tenant_key, display_name, status) values (?, ?, 'Fields 2', 'ACTIVE')", other, "fields-" + other);
        assertEquals(List.of("CREATE"), fields.previewImport(other, file).stream().map(CaseFieldsPort.ImportLine::outcome).toList());
        assertEquals(List.of(), fields.fields(other), "a preview changes nothing");
        tx.executeWithoutResult(s -> fields.importFile(other, file, "admin"));
        assertEquals("contractNumber", fields.fields(other).getFirst().fieldKey());
        assertEquals(SdvErrorCode.FLD_004, code(() -> fields.previewImport(entity, "{\"no\":1}")));
    }

    @Test
    void aDatabaseOfBeforeIsTakenOverOnceAndUntilThenRefused() {
        for (UUID s : List.of(a, b)) old.insert(field(s, "contractNumber", "Contract " + s, MetadataFieldType.TEXT, s == b, "{\"minLength\":2}"));
        old.insert(field(a, "firstName", "First", MetadataFieldType.TEXT, false, "{}"));
        old.replaceDisplayOrder(entity, a, List.of("firstName", "contractNumber"));
        old.replaceRecordTitleFields(entity, a, List.of("firstName"));
        assertThrows(SetupProblem.class, () -> fields.findCurrent(entity, a));
        assertTrue(fields.systems().stream().anyMatch(s -> s[0].equals(entity) && s[1].equals(a)));
        assertEquals(2, (int) tx.<Integer>execute(s -> fields.adopt(entity, a)));
        assertEquals(1, (int) tx.<Integer>execute(s -> fields.adopt(entity, b)));
        assertEquals(0, (int) tx.<Integer>execute(s -> fields.adopt(entity, a)));
        assertEquals(List.of("firstName", "contractNumber"), fields.findDisplayOrder(entity, a));
        assertEquals(List.of("firstName"), fields.findRecordTitleFields(entity, a));
        assertTrue(state("contractNumber").primary(), "the identifier of one system");
        assertTrue(state("firstName").sensitive());
        assertEquals("{\"minLength\":2}", fields.findCurrent(entity, b, "contractNumber").orElseThrow().validationRulesJson());
    }
}
