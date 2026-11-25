package com.sdv.platform.persistence.business;

import com.sdv.platform.application.port.BusinessRecordRepository.AnywhereCriterion;
import com.sdv.platform.application.port.BusinessRecordRepository.AnywhereField;
import com.sdv.platform.application.port.BusinessRecordRepository.SearchCriterion;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 0022: the SQL an "anywhere" search sends. Each field is tried with its own match, the best match comes first,
 * and only the caller's tenant and source are read. (Lives in sdv-web, like JdbcBusinessRecordRepositorySearchSqlTest.)
 */
class AnywhereSearchSqlTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcBusinessRecordRepository repository = new JdbcBusinessRecordRepository(jdbc);

    private static final AnywhereCriterion PATEL = new AnywhereCriterion(List.of(
            new AnywhereField("contractNumber", MetadataSearchMode.EXACT, List.of("300000000041"), true),
            new AnywhereField("loanType", MetadataSearchMode.EXACT, List.of("patel"), false),
            new AnywhereField("closingAgent", MetadataSearchMode.PREFIX, List.of("patel"), false),
            new AnywhereField("borrowerName", MetadataSearchMode.PARTIAL, List.of("patel"), false)));

    private record Sent(String sql, SqlParameterSource params) { }

    private List<String> ranks = List.of();

    /** Every rank has one record and no submission, so every rank is asked; ranks: what finds each one's records. */
    @SuppressWarnings("unchecked")
    private Sent send(List<SearchCriterion> criteria, AnywhereCriterion anywhere, Instant from, Instant to) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.eq(String.class))).thenAnswer(call -> List.of(UUID.randomUUID().toString()));
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.eq(Integer.class))).thenReturn(0);
        repository.findMatchingSubmissions(TENANT, SOURCE, criteria, anywhere, from, to, null, null, false, 0, 26);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), params.capture(), any(RowMapper.class));
        ArgumentCaptor<String> probes = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeast(0)).queryForList(probes.capture(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.eq(String.class));
        ranks = probes.getAllValues();
        return new Sent(sql.getValue().replaceAll("\\s+", " "), params.getValue());
    }

    @Test
    void exactFieldsMatchTheNormalizedValueAsConfigured_identifierRanksFirst() {
        String sql = send(List.of(), PATEL, null, null).sql();
        assertTrue(ranks.get(0).contains("and ((v.field_key=:a0Field and (v.search_value=:a0Value0)))"), ranks.get(0));
        assertTrue(ranks.get(1).contains("(v.field_key=:a1Field and (v.search_value=:a1Value0)) or "), ranks.get(1));
        assertFalse(ranks.get(1).contains("a0Field"), "an identifier's exact match is the first rank only");
    }

    @Test
    void startsWithFieldsMatchOnlyFromTheStart_ignoringCase() {
        String sql = send(List.of(), PATEL, null, null).sql();
        assertTrue(ranks.get(1).contains("(v.field_key=:a2Field and (v.search_value=:a2Value0))"), ranks.get(1));
        assertTrue(ranks.get(2).contains("(v.field_key=:a2Field and (v.search_value like :a2Starts0))"), ranks.get(2));
        assertFalse(ranks.get(3).contains("a2Field"), ranks.get(3));
    }

    @Test
    void containsFieldsMatchAnyPart_ignoringCase_andRankExactThenStartsWithThenContains() {
        String sql = send(List.of(), PATEL, null, null).sql();
        assertTrue(ranks.get(1).contains("(v.field_key=:a3Field and (v.search_value=:a3Value0))"), ranks.get(1));
        assertTrue(ranks.get(2).contains("(v.field_key=:a3Field and (v.search_value like :a3Starts0))"), ranks.get(2));
        assertTrue(ranks.get(3).contains("((v.field_key=:a3Field and (v.search_value like :a3Contains0)))"), ranks.get(3));
    }

    @Test
    void bestMatchFirstThenNewest_andOnlySubmissionsWithAMatch() {
        String sql = send(List.of(), PATEL, null, null).sql();
        assertEquals(4, ranks.size(), "one rank after the other");
        assertTrue(sql.contains("and s.record_id in (select fr.record_id::uuid from fld_record fr where fr.record_uuid=any(cast(:records as uuid[])))"), sql);
        assertTrue(sql.contains("order by s.created_at desc, s.submission_id"), sql);
    }

    @Test
    void onlyTheFieldsTriedAreRead_inTheCallersTenantAndSource() {
        Sent sent = send(List.of(), PATEL, null, null);
        String sql = sent.sql();
        assertTrue(sql.contains("where s.tenant_id=:tenantId and s.source_id=:sourceId"), sql);
        for (String rank : ranks) assertTrue(rank.contains("v.entity_uuid=:tenantId and v.product_code='SDV' and v.record_type=:recordType and not v.sensitive and"), rank);
        assertEquals(TENANT, sent.params().getValue("tenantId"));
        assertEquals(SOURCE, sent.params().getValue("sourceId"));
        assertEquals("s-" + SOURCE, sent.params().getValue("recordType"));
        assertEquals("borrowerName", sent.params().getValue("a3Field"));
        assertEquals("%patel%", sent.params().getValue("a3Contains0"));
        assertEquals("patel", sent.params().getValue("a3Value0"));
    }

    @Test
    void combinesWithFieldsAndADateRangeByAnd() {
        String sql = send(List.of(new SearchCriterion("loanType", MetadataSearchMode.EXACT, List.of("Auto"))), PATEL,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-15T23:59:59Z")).sql();
        assertTrue(sql.contains("s.created_at>=:createdFrom") && sql.contains("s.created_at<=:createdTo"), sql);
        assertTrue(sql.contains("v.field_key=:c0Field and (v.search_value=:c0Value0)"), sql);
        assertTrue(sql.contains("cast(:records as uuid[])"), sql);
    }

    // ---- follow-up: exact text fields ignore capitals ----

    @Test
    void anExactFieldIsComparedInTheStoredSearchForm_inQuickSearch() {
        AnywhereCriterion mortgage = new AnywhereCriterion(List.of(
                new AnywhereField("contractNumber", MetadataSearchMode.EXACT, List.of("300000000041"), true, false),
                new AnywhereField("loanType", MetadataSearchMode.EXACT, List.of("mortgage"), false, true)));
        String sql = send(List.of(), mortgage, null, null).sql();
        assertEquals(2, ranks.size(), sql);
        assertTrue(ranks.get(0).contains("(v.field_key=:a0Field and (v.search_value=:a0Value0))"), ranks.get(0));
        assertTrue(ranks.get(1).contains("(v.field_key=:a1Field and (v.search_value=:a1Value0))"), ranks.get(1));
    }

    @Test
    void anExactFieldIsComparedInTheStoredSearchForm_inSingleFieldSearch() {
        String sql = send(List.of(
                new SearchCriterion("contractNumber", MetadataSearchMode.EXACT, List.of("300000000041"), false),
                new SearchCriterion("loanType", MetadataSearchMode.EXACT, List.of("mortgage"), true)), null, null, null).sql();
        assertTrue(sql.contains("v.field_key=:c0Field and (v.search_value=:c0Value0)"), sql);
        assertTrue(sql.contains("v.field_key=:c1Field and (v.search_value=:c1Value0)"), sql);
    }

    @Test
    void withoutAnywhereTheSearchIsUnchanged() {
        String sql = send(List.of(new SearchCriterion("loanType", MetadataSearchMode.EXACT, List.of("Auto"))), null, null, null).sql();
        assertEquals(List.of(), ranks);
        assertTrue(sql.contains("order by s.created_at desc, s.submission_id"), sql);
    }
}
