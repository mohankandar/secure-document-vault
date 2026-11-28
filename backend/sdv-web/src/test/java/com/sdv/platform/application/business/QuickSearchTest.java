package com.sdv.platform.application.business;

import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.AnywhereCriterion;
import com.sdv.platform.application.port.BusinessRecordRepository.AnywhereField;
import com.sdv.platform.application.port.BusinessRecordRepository.AvailableDocumentSnapshot;
import com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionStatus;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0022: quick search across all fields ("anywhere"). Which fields are tried and how, matchedFields, best match
 * first, the 2 to 100 character rule, dates and other criteria alongside it, the audit entry and the tenant.
 */
class QuickSearchTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final UUID SUB_OMAR = UUID.fromString("00000000-0000-0000-0000-000000000041");
    private static final UUID SUB_LENA = UUID.fromString("00000000-0000-0000-0000-000000000092");

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
    private final MetadataFieldDefinitionRepository definitions = mock(MetadataFieldDefinitionRepository.class);
    private final BusinessRecordRepository records = mock(BusinessRecordRepository.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final BusinessExperienceService service = new BusinessExperienceService(
            tenants, sources, definitions, records, audit, Clock.fixed(NOW, ZoneOffset.UTC));

    private static MetadataFieldDefinition field(String key, String label, MetadataFieldType type, boolean identifier,
                                                 boolean searchable, MetadataNormalization normalization, MetadataSearchMode mode) {
        return new MetadataFieldDefinition(UUID.randomUUID(), TENANT, SOURCE, key, 1, label, type, false, identifier, searchable,
                normalization, mode, "{}", true, NOW, null, NOW);
    }

    /** The canvas's loan system, plus a date field and a field the Business admin made unsearchable. */
    private static final List<MetadataFieldDefinition> FIELDS = List.of(
            field("contractNumber", "Contract number", MetadataFieldType.TEXT, true, true, MetadataNormalization.DIGITS_ONLY, MetadataSearchMode.EXACT),
            field("borrowerName", "Borrower", MetadataFieldType.TEXT, false, true, MetadataNormalization.TRIM, MetadataSearchMode.PARTIAL),
            field("coBorrower", "Co-borrower", MetadataFieldType.TEXT, false, true, MetadataNormalization.TRIM, MetadataSearchMode.PARTIAL),
            field("loanType", "Loan type", MetadataFieldType.TEXT, false, true, MetadataNormalization.TRIM, MetadataSearchMode.EXACT),
            field("closingAgent", "Closing agent", MetadataFieldType.TEXT, false, true, MetadataNormalization.TRIM, MetadataSearchMode.PREFIX),
            field("closingDate", "Closing date", MetadataFieldType.DATE, false, true, MetadataNormalization.NONE, MetadataSearchMode.EXACT),
            field("internalNote", "Internal note", MetadataFieldType.TEXT, false, false, MetadataNormalization.TRIM, MetadataSearchMode.PARTIAL));

    @BeforeEach
    void setUp() {
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        IngestionSource source = mock(IngestionSource.class);
        when(source.sourceId()).thenReturn(SOURCE);
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(source));
        when(definitions.findCurrent(TENANT, SOURCE)).thenReturn(FIELDS);
        when(definitions.findDisplayOrder(TENANT, SOURCE)).thenReturn(FIELDS.stream().map(MetadataFieldDefinition::fieldKey).toList());

        // Omar Haddad: "Patel" only in his co-borrower, closing agent and (unsearchable) note.
        // Lena Patel: "Patel" in the borrower's name.
        when(records.findMetadataSnapshots(eq(TENANT), eq(SOURCE), anyList())).thenAnswer(inv -> {
            List<UUID> ids = inv.getArgument(2);
            return ids.stream().flatMap(id -> (id.equals(SUB_OMAR) ? omar() : lena()).stream()).toList();
        });
        TestRecordRefs.group(records);
        when(records.findSubmissionIdsOfRecord(eq(TENANT), eq(SOURCE), any())).thenAnswer(inv -> {
            return List.of(TestRecordRefs.id("contractNumber", "300000000041").equals(inv.getArgument(2)) ? SUB_OMAR : SUB_LENA);
        });
        when(records.findSubmissionsByIds(eq(TENANT), eq(SOURCE), anyList())).thenAnswer(inv -> {
            List<UUID> ids = inv.getArgument(2);
            return ids.stream().map(QuickSearchTest::submission).toList();
        });
        when(records.findAvailableDocuments(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                new AvailableDocumentSnapshot(UUID.randomUUID(), SUB_OMAR, "d1", "loan-agreement.pdf", NOW, NOW, null, 9, false, SensitivityLevel.STANDARD)));
    }

    private static Submission submission(UUID id) {
        return new Submission(id, TENANT, SOURCE, "src-" + id, 1, "sha", SubmissionStatus.COMPLETE, NOW.minusSeconds(60), NOW, null);
    }

    private static List<MetadataSnapshot> omar() {
        return List.of(
                new MetadataSnapshot(SUB_OMAR, "contractNumber", "Contract number", "300000000041", "300000000041", true),
                new MetadataSnapshot(SUB_OMAR, "borrowerName", "Borrower", "Omar Haddad", "Omar Haddad", false),
                new MetadataSnapshot(SUB_OMAR, "coBorrower", "Co-borrower", "Priya Patel", "Priya Patel", false),
                new MetadataSnapshot(SUB_OMAR, "loanType", "Loan type", "Auto", "Auto", false),
                new MetadataSnapshot(SUB_OMAR, "closingAgent", "Closing agent", "Patel & Sons Title", "Patel & Sons Title", false),
                new MetadataSnapshot(SUB_OMAR, "closingDate", "Closing date", "2026-12-02", "2026-12-02", false),
                new MetadataSnapshot(SUB_OMAR, "internalNote", "Internal note", "Ask Patel", "Ask Patel", false));
    }

    private static List<MetadataSnapshot> lena() {
        return List.of(
                new MetadataSnapshot(SUB_LENA, "contractNumber", "Contract number", "300000000092", "300000000092", true),
                new MetadataSnapshot(SUB_LENA, "borrowerName", "Borrower", "Lena Patel", "Lena Patel", false),
                new MetadataSnapshot(SUB_LENA, "loanType", "Loan type", "Mortgage", "Mortgage", false));
    }

    private void found(UUID... submissions) {
        when(records.findMatchingSubmissions(eq(TENANT), eq(SOURCE), anyList(), any(AnywhereCriterion.class),
                any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenAnswer(inv -> inv.<Integer>getArgument(9) == 0 ? List.of(submissions).stream().map(QuickSearchTest::submission).toList() : List.of());
    }

    private BusinessExperienceView.SearchResponse anywhere(String text) {
        return service.search(TENANT, SOURCE, Map.of(), text, null, null, null, null, false, 1, 25, "maya", "corr");
    }

    private AnywhereCriterion sentCriterion() {
        ArgumentCaptor<AnywhereCriterion> sent = ArgumentCaptor.forClass(AnywhereCriterion.class);
        verify(records, atLeastOnce()).findMatchingSubmissions(eq(TENANT), eq(SOURCE), anyList(), sent.capture(),
                any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
        return sent.getValue();
    }

    // ---- which fields are tried, each with its own match ----

    @Test
    void lettersTryEverySearchableTextFieldWithItsOwnMatch_notDigitsOnlyDatesOrUnsearchableFields() {
        found();
        anywhere("patel");
        AnywhereCriterion sent = sentCriterion();
        assertEquals(List.of("borrowerName", "coBorrower", "loanType", "closingAgent"),
                sent.fields().stream().map(AnywhereField::fieldKey).toList());
        assertEquals(List.of(MetadataSearchMode.PARTIAL, MetadataSearchMode.PARTIAL, MetadataSearchMode.EXACT, MetadataSearchMode.PREFIX),
                sent.fields().stream().map(AnywhereField::searchMode).toList());
    }

    @Test
    void textWithDigitsAlsoTriesDigitsOnlyFields_normalizedAsThoseFieldsStoreThem() {
        found();
        anywhere(" 3000-0000-0041 ");
        AnywhereField contract = sentCriterion().fields().getFirst();
        assertEquals("contractNumber", contract.fieldKey());
        assertEquals(List.of("300000000041"), contract.normalizedVariants());
        assertTrue(contract.businessIdentifier());
    }

    @Test
    void whenNoFieldCanMatchNothingIsSearchedAndNothingIsFound() {
        when(definitions.findCurrent(TENANT, SOURCE)).thenReturn(List.of(FIELDS.getFirst(), FIELDS.get(5)));
        var result = anywhere("patel");
        assertTrue(result.records().isEmpty());
        verify(records, never()).findMatchingSubmissions(any(), any(), anyList(), any(AnywhereCriterion.class),
                any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
        verify(audit).append(argThat(e -> e.action().equals("BUSINESS_SEARCH") && e.safeDetailsJson().contains("\"anywhere\":true")));
    }

    // ---- each match kind, and the rank ----

    @Test
    void eachMatchKindAndItsRank() {
        var exactId = new AnywhereField("contractNumber", MetadataSearchMode.EXACT, List.of("300000000041"), true);
        var exact = new AnywhereField("loanType", MetadataSearchMode.EXACT, List.of("Auto"), false);
        var startsWith = new AnywhereField("closingAgent", MetadataSearchMode.PREFIX, List.of("patel"), false);
        var contains = new AnywhereField("borrowerName", MetadataSearchMode.PARTIAL, List.of("patel"), false);

        assertEquals(0, BusinessExperienceService.matchRank(exactId, "300000000041"));
        assertNull(BusinessExperienceService.matchRank(exactId, "3000000000410"), "exact means the whole value");
        assertEquals(1, BusinessExperienceService.matchRank(exact, "Auto"));
        assertNull(BusinessExperienceService.matchRank(exact, "auto"), "exact keeps the field's configured normalization");
        assertEquals(2, BusinessExperienceService.matchRank(startsWith, "Patel & Sons Title"));
        assertNull(BusinessExperienceService.matchRank(startsWith, "Ask Patel"), "starts with does not match inside the value");
        assertEquals(1, BusinessExperienceService.matchRank(startsWith, "PATEL"), "the whole value, ignoring case, ranks as exact");
        assertEquals(3, BusinessExperienceService.matchRank(contains, "Lena Patel"));
        assertEquals(2, BusinessExperienceService.matchRank(contains, "Patel, Lena"));
        assertNull(BusinessExperienceService.matchRank(contains, "Omar Haddad"));
    }

    // ---- matchedFields ----

    @Test
    void matchedFieldsNameEveryFieldThatMatched_bestFirst_withTheRecordsOwnValues() {
        found(SUB_OMAR);
        var omar = anywhere("patel").records().getFirst();
        assertEquals(List.of(
                new BusinessExperienceView.MatchedField("closingAgent", "Closing agent", "Patel & Sons Title"),
                new BusinessExperienceView.MatchedField("coBorrower", "Co-borrower", "Priya Patel")), omar.matchedFields());
    }

    @Test
    void unsearchableAndDateFieldsAreNeverMatched() {
        found(SUB_OMAR);
        var omar = anywhere("Ask Patel").records().getFirst();
        assertTrue(omar.matchedFields().stream().noneMatch(m -> m.fieldKey().equals("internalNote")), omar.matchedFields().toString());
        found(SUB_OMAR);
        var byDate = anywhere("2026-12-02");
        verify(records, atLeastOnce()).findMatchingSubmissions(any(), any(), anyList(),
                argThat((AnywhereCriterion c) -> c.fields().stream().noneMatch(f -> f.fieldKey().equals("closingDate"))),
                any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
        assertTrue(byDate.records().getFirst().matchedFields().stream().noneMatch(m -> m.fieldKey().equals("closingDate")));
    }

    @Test
    void aFieldSearchCarriesNoMatchedFields() {
        when(records.findMatchingSubmissions(eq(TENANT), eq(SOURCE), anyList(), any(), any(), any(), any(), anyBoolean(), eq(0), anyInt()))
                .thenReturn(List.of(submission(SUB_LENA)));
        var lena = service.search(TENANT, SOURCE, Map.of("borrowerName", "patel"), null, null, null, null,
                false, 1, 25, "maya", "corr").records().getFirst();
        assertEquals(List.of(), lena.matchedFields());
    }

    // ---- order ----

    @Test
    void resultsKeepTheBestMatchFirstOrderTheSearchReturns() {
        found(SUB_LENA, SUB_OMAR);
        var result = anywhere("patel").records();
        assertEquals(List.of("300000000092", "300000000041"), result.stream().map(r -> r.identifiers().getFirst().value()).toList());
        found(SUB_OMAR, SUB_LENA);
        result = anywhere("patel").records();
        assertEquals(List.of("300000000041", "300000000092"), result.stream().map(r -> r.identifiers().getFirst().value()).toList());
    }

    // ---- 2 to 100 characters, and alongside other criteria ----

    @Test
    void tooShortOrTooLongIsRefused() {
        for (String text : List.of("", " ", "p", "  p  ", "x".repeat(101), " " + "x".repeat(101) + " ")) {
            var ex = assertThrows(IllegalArgumentException.class, () -> anywhere(text), "'" + text + "'");
            assertEquals("Search text must be 2 to 100 characters.", ex.getMessage());
            assertEquals("SDV-SCH-001", ((com.sdv.platform.domain.error.CodedRefusal) ex).errorCode().code(), "task 0043");
        }
        verifyNoInteractions(audit);
        found();
        assertDoesNotThrow(() -> anywhere("pa"));
        assertDoesNotThrow(() -> anywhere(" " + "x".repeat(100) + " "));
    }

    @Test
    void anywhereAloneIsEnough_andCombinesWithADateRangeAndAField() {
        found(SUB_OMAR);
        Instant from = Instant.parse("2026-11-01T00:00:00Z"), to = Instant.parse("2026-11-19T23:59:59Z");
        service.search(TENANT, SOURCE, Map.of("loanType", "Auto"), "patel", from, to, null, null, false, 1, 25, "maya", "corr");
        verify(records).findMatchingSubmissions(eq(TENANT), eq(SOURCE),
                argThat(list -> list.size() == 1 && list.getFirst().fieldKey().equals("loanType")),
                any(AnywhereCriterion.class), eq(from), eq(to), isNull(), isNull(), eq(false), eq(0), anyInt());
    }

    // ---- audit and tenant ----

    @Test
    void theAuditEntrySaysAnywhereAndTheCount_neverTheText() {
        found(SUB_OMAR, SUB_LENA);
        anywhere("patel");
        verify(audit).append(argThat(e -> e.action().equals("BUSINESS_SEARCH")
                && e.safeDetailsJson().equals("{\"criteriaCount\":0,\"resultCount\":2,\"anywhere\":true}")));
    }

    @Test
    void aFieldSearchAuditIsUnchanged() {
        when(records.findMatchingSubmissions(eq(TENANT), eq(SOURCE), anyList(), any(), any(), any(), any(), anyBoolean(), eq(0), anyInt()))
                .thenReturn(List.of(submission(SUB_LENA)));
        service.search(TENANT, SOURCE, Map.of("borrowerName", "patel"), null, null, null, null, false, 1, 25, "maya", "corr");
        verify(audit).append(argThat(e -> e.safeDetailsJson().equals("{\"criteriaCount\":1,\"resultCount\":1}")));
    }

    @Test
    void onlyTheCallersTenantIsSearched() {
        found(SUB_OMAR);
        anywhere("patel");
        verify(records, never()).findMatchingSubmissions(eq(OTHER_TENANT), any(), anyList(), any(AnywhereCriterion.class),
                any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
        verify(definitions, never()).findCurrent(eq(OTHER_TENANT), any());
        // Another tenant's caller can't use this tenant's source.
        Tenant other = mock(Tenant.class);
        when(other.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(OTHER_TENANT)).thenReturn(Optional.of(other));
        assertThrows(java.util.NoSuchElementException.class, () ->
                service.search(OTHER_TENANT, SOURCE, Map.of(), "patel", null, null, null, null, false, 1, 25, "x", "corr"));
    }

    // ---- follow-up: exact text fields ignore capitals; identifiers and digits-only fields don't ----

    @Test
    void whichExactFieldsIgnoreCase() {
        assertTrue(BusinessExperienceService.exactIgnoresCase(FIELDS.get(3)), "Loan type: exact text");
        assertFalse(BusinessExperienceService.exactIgnoresCase(FIELDS.getFirst()), "Contract number: identifier, digits-only");
        assertFalse(BusinessExperienceService.exactIgnoresCase(field("memberId", "Member ID", MetadataFieldType.TEXT, true, true,
                MetadataNormalization.TRIM, MetadataSearchMode.EXACT)), "an identifier keeps capitals");
        assertFalse(BusinessExperienceService.exactIgnoresCase(field("branchCode", "Branch code", MetadataFieldType.TEXT, false, true,
                MetadataNormalization.DIGITS_ONLY, MetadataSearchMode.EXACT)), "a digits-only field is unchanged");
        assertFalse(BusinessExperienceService.exactIgnoresCase(field("term", "Term", MetadataFieldType.INTEGER, false, true,
                MetadataNormalization.TRIM, MetadataSearchMode.EXACT)), "not a text field");
        assertTrue(BusinessExperienceService.exactIgnoresCase(field("email", "Email", MetadataFieldType.EMAIL, false, true,
                MetadataNormalization.TRIM, MetadataSearchMode.EXACT)), "an email address is text");
        assertFalse(BusinessExperienceService.exactIgnoresCase(FIELDS.get(1)), "contains already ignores case by its own rule");
    }

    @Test
    void quickSearchSendsExactTextFieldsToIgnoreCase_andFindsMortgageFromLowerCase() {
        found(SUB_LENA);
        var lena = anywhere("mortgage").records().getFirst();
        var loanType = sentCriterion().fields().stream().filter(f -> f.fieldKey().equals("loanType")).findFirst().orElseThrow();
        assertTrue(loanType.ignoreCase());
        assertEquals(List.of("mortgage"), loanType.normalizedVariants(), "the text itself is sent; the comparison ignores case");
        assertEquals(List.of(new BusinessExperienceView.MatchedField("loanType", "Loan type", "Mortgage")), lena.matchedFields());
    }

    @Test
    void quickSearchKeepsDigitsOnlyIdentifiersExact() {
        found();
        anywhere("3000-0000-0041");
        AnywhereField contract = sentCriterion().fields().getFirst();
        assertEquals("contractNumber", contract.fieldKey());
        assertFalse(contract.ignoreCase());
    }

    @Test
    void singleFieldSearchOnAnExactTextFieldIgnoresCase_identifierDoesNot() {
        when(records.findMatchingSubmissions(eq(TENANT), eq(SOURCE), anyList(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(List.of());
        service.search(TENANT, SOURCE, Map.of("loanType", "mortgage", "contractNumber", "300000000041"), null, null, null, null,
                false, 1, 25, "maya", "corr");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BusinessRecordRepository.SearchCriterion>> sent = ArgumentCaptor.forClass(List.class);
        verify(records).findMatchingSubmissions(eq(TENANT), eq(SOURCE), sent.capture(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
        var byKey = sent.getValue().stream().collect(java.util.stream.Collectors.toMap(
                BusinessRecordRepository.SearchCriterion::fieldKey, BusinessRecordRepository.SearchCriterion::ignoreCase));
        assertEquals(Map.of("loanType", true, "contractNumber", false), byKey);
    }

    @Test
    void aSensitiveFieldIsSearchedOnlyInTheLevelsThePersonMayOpen() {
        // Task 0101 (VSEC-202): the person's levels go with the criterion into the one search query.
        com.sdv.platform.application.port.RecordValuesPort values = new com.sdv.platform.application.port.RecordValuesPort() {
            @Override public UUID store(UUID t, UUID s, UUID sub, Map<String, String> v) { return sub; }
            @Override public List<com.sdv.platform.application.port.RecordValuesPort.Opened> open(UUID t, UUID s, UUID r) { return List.of(); }
            @Override public Map<UUID, List<com.sdv.platform.application.port.RecordValuesPort.Opened>> openPage(UUID t, UUID s, java.util.Collection<UUID> r) { return Map.of(); }
            @Override public java.util.Set<String> sensitiveKeys(UUID t) { return java.util.Set.of("loanType"); }
            @Override public Map<String, List<String>> fingerprints(UUID t, Map<String, String> v) {
                Map<String, List<String>> out = new java.util.HashMap<>();
                v.keySet().forEach(k -> out.put(k, List.of("f1.x")));
                return out;
            }
            @Override public boolean partialSearch() { return true; }
        };
        var withValues = new BusinessExperienceService(tenants, sources, definitions, records, audit,
                com.sdv.platform.application.access.QuarantineLookup.NONE, values, Clock.fixed(NOW, ZoneOffset.UTC));
        when(records.findMatchingSubmissions(eq(TENANT), eq(SOURCE), anyList(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(List.of());
        Map<com.sdv.platform.application.access.Clearance, List<String>> expected = Map.of(
                com.sdv.platform.application.access.Clearance.STANDARD_ONLY, List.of("STANDARD"),
                com.sdv.platform.application.access.Clearance.ALL, List.of("CONFIDENTIAL", "HIGHLY_CONFIDENTIAL", "STANDARD"));
        for (var e : expected.entrySet()) {
            org.mockito.Mockito.clearInvocations(records);
            withValues.search(TENANT, SOURCE, Map.of("loanType", "mortgage"), null, null, null, null, null, false, 1, 25, "maya", "corr", e.getKey());
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<BusinessRecordRepository.SearchCriterion>> sent = ArgumentCaptor.forClass(List.class);
            verify(records).findMatchingSubmissions(eq(TENANT), eq(SOURCE), sent.capture(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt());
            var criterion = sent.getValue().getFirst();
            assertEquals(List.of("f1.x"), criterion.fingerprints());
            assertEquals(e.getValue(), criterion.openableLevels());
        }
    }

    @Test
    void matchRankIgnoresCaseOnlyWhenTheFieldSaysSo() {
        var loanType = new AnywhereField("loanType", MetadataSearchMode.EXACT, List.of("mortgage"), false, true);
        var memberId = new AnywhereField("memberId", MetadataSearchMode.EXACT, List.of("ab-12"), true, false);
        assertEquals(1, BusinessExperienceService.matchRank(loanType, "Mortgage"));
        assertEquals(1, BusinessExperienceService.matchRank(loanType, "MORTGAGE"));
        assertNull(BusinessExperienceService.matchRank(loanType, "Mortgage refinance"), "still the whole value");
        assertNull(BusinessExperienceService.matchRank(memberId, "AB-12"), "an identifier keeps capitals");
        assertEquals(0, BusinessExperienceService.matchRank(memberId, "ab-12"));
    }

    // ---- task 0048: quarantine ----

    @Test
    void aQuarantinedDocumentIsCountedAsUnavailableInTheSearchRowAndItsLevelIsNotShown() {
        UUID doc = UUID.randomUUID();
        when(records.findAvailableDocuments(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(new AvailableDocumentSnapshot(
                doc, SUB_OMAR, "d1", "loan-agreement.pdf", NOW, NOW, null, 9, false, SensitivityLevel.HIGHLY_CONFIDENTIAL)));
        found(SUB_OMAR);
        var before = anywhere("patel").records().getFirst();
        assertEquals("HIGHLY_CONFIDENTIAL", before.restrictedLevel(), "not quarantined: its level is shown as restricted");
        assertEquals(0, before.unavailableDocumentCount());

        com.sdv.platform.application.access.QuarantineLookup lookup = new com.sdv.platform.application.access.QuarantineLookup() {
            @Override public boolean isQuarantined(UUID tenantId, UUID documentId) { return documentId.equals(doc); }
            @Override public java.util.Set<UUID> quarantined(UUID tenantId, java.util.Collection<UUID> ids) {
                return ids.contains(doc) ? java.util.Set.of(doc) : java.util.Set.of();
            }
        };
        var withQuarantine = new BusinessExperienceService(tenants, sources, definitions, records, audit, lookup,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var row = withQuarantine.search(TENANT, SOURCE, Map.of(), "patel", null, null, null, null, false, 1, 25, "maya", "corr")
                .records().getFirst();
        assertEquals(1, row.unavailableDocumentCount(), "Temporarily unavailable");
        assertNull(row.restrictedLevel(), "never the level of a quarantined document");
        assertEquals(1, row.availableDocumentCount(), "counts unchanged");
    }
}
