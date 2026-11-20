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

    @SuppressWarnings("unchecked")
    private Sent send(List<SearchCriterion> criteria, AnywhereCriterion anywhere, Instant from, Instant to) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        repository.findMatchingSubmissions(TENANT, SOURCE, criteria, anywhere, from, to, null, null, false, 0, 26);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        return new Sent(sql.getValue().replaceAll("\\s+", " "), params.getValue());
    }

    @Test
    void exactFieldsMatchTheNormalizedValueAsConfigured_identifierRanksFirst() {
        String sql = send(List.of(), PATEL, null, null).sql();
        assertTrue(sql.contains("when v.field_key=:a0Field and (v.normalized_value=:a0Value0) then 0"), sql);
        assertTrue(sql.contains("when v.field_key=:a1Field and (v.normalized_value=:a1Value0) then 1"), sql);
    }

    @Test
    void startsWithFieldsMatchOnlyFromTheStart_ignoringCase() {
        String sql = send(List.of(), PATEL, null, null).sql();
        assertTrue(sql.contains("when v.field_key=:a2Field and (position(lower(:a2Value0) in lower(v.normalized_value))=1) then"
                + " case when lower(v.normalized_value)=lower(:a2Value0) then 1"
                + " when position(lower(:a2Value0) in lower(v.normalized_value))=1 then 2 else 3 end"), sql);
    }

    @Test
    void containsFieldsMatchAnyPart_ignoringCase_andRankExactThenStartsWithThenContains() {
        String sql = send(List.of(), PATEL, null, null).sql();
        assertTrue(sql.contains("when v.field_key=:a3Field and (position(lower(:a3Value0) in lower(v.normalized_value))>0) then"
                + " case when lower(v.normalized_value)=lower(:a3Value0) then 1"
                + " when position(lower(:a3Value0) in lower(v.normalized_value))=1 then 2 else 3 end"), sql);
    }

    @Test
    void bestMatchFirstThenNewest_andOnlySubmissionsWithAMatch() {
        String sql = send(List.of(), PATEL, null, null).sql();
        assertTrue(sql.contains("and m.match_rank is not null"), sql);
        assertTrue(sql.contains("order by m.match_rank, s.created_at desc, s.submission_id"), sql);
    }

    @Test
    void onlyTheFieldsTriedAreRead_inTheCallersTenantAndSource() {
        Sent sent = send(List.of(), PATEL, null, null);
        String sql = sent.sql();
        assertTrue(sql.contains("where s.tenant_id=:tenantId and s.source_id=:sourceId"), sql);
        assertTrue(sql.contains("where v.tenant_id=s.tenant_id and v.source_id=s.source_id and v.submission_id=s.submission_id"), sql);
        assertTrue(sql.contains("and v.field_key in (:anywhereFields)"), sql);
        assertEquals(TENANT, sent.params().getValue("tenantId"));
        assertEquals(SOURCE, sent.params().getValue("sourceId"));
        assertEquals(List.of("contractNumber", "loanType", "closingAgent", "borrowerName"), sent.params().getValue("anywhereFields"));
        assertEquals("patel", sent.params().getValue("a3Value0"));
    }

    @Test
    void combinesWithFieldsAndADateRangeByAnd() {
        String sql = send(List.of(new SearchCriterion("loanType", MetadataSearchMode.EXACT, List.of("Auto"))), PATEL,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-15T23:59:59Z")).sql();
        assertTrue(sql.contains("s.created_at>=:createdFrom") && sql.contains("s.created_at<=:createdTo"), sql);
        assertTrue(sql.contains("v.field_key= :c0Field and (v.normalized_value=:c0Value0)"), sql);
        assertTrue(sql.contains("and m.match_rank is not null"), sql);
    }

    @Test
    void withoutAnywhereTheSearchIsUnchanged() {
        String sql = send(List.of(new SearchCriterion("loanType", MetadataSearchMode.EXACT, List.of("Auto"))), null, null, null).sql();
        assertFalse(sql.contains("match_rank"), sql);
        assertTrue(sql.contains("order by s.created_at desc, s.submission_id"), sql);
    }
}
