package com.sdv.platform.persistence.business;

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
 * Checks the search SQL the repository sends. (sdv-persistence has no test dependencies, so this test lives in
 * sdv-web, which already depends on sdv-persistence and spring-boot-starter-test.)
 * Contains and Starts with must ignore case; Exact must keep the field's configured normalization.
 */
class JdbcBusinessRecordRepositorySearchSqlTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcBusinessRecordRepository repository = new JdbcBusinessRecordRepository(jdbc);

    @SuppressWarnings("unchecked")
    private String sqlFor(SearchCriterion criterion, Instant createdFrom, Instant createdTo) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        repository.findMatchingSubmissions(TENANT, SOURCE, criterion == null ? List.of() : List.of(criterion),
                createdFrom, createdTo, null, null, 0, 26);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        return sql.getValue().replaceAll("\\s+", " ");
    }

    @Test
    void containsIgnoresCase() {
        String sql = sqlFor(new SearchCriterion("lastName", MetadataSearchMode.PARTIAL, List.of("pat")), null, null);
        assertTrue(sql.contains("position(lower(:c0Value0) in lower(v.normalized_value))>0"), sql);
    }

    @Test
    void startsWithIgnoresCase() {
        String sql = sqlFor(new SearchCriterion("lastName", MetadataSearchMode.PREFIX, List.of("PAT")), null, null);
        assertTrue(sql.contains("position(lower(:c0Value0) in lower(v.normalized_value))=1"), sql);
    }

    @Test
    void exactKeepsConfiguredNormalization() {
        String sql = sqlFor(new SearchCriterion("contractNumber", MetadataSearchMode.EXACT, List.of("300000000076")), null, null);
        assertTrue(sql.contains("v.normalized_value=:c0Value0"), sql);
        assertFalse(sql.contains("lower("), "Exact matches must not be lower-cased: " + sql);
    }

    @SuppressWarnings("unchecked")
    private String incompleteSql(Instant createdFrom, Instant createdTo) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        repository.findMatchingSubmissions(TENANT, SOURCE, List.of(), createdFrom, createdTo, null, null, true, 0, 26);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        return sql.getValue().replaceAll("\\s+", " ");
    }

    @Test
    void incompleteOnlyAloneKeepsOnlySubmissionsThatAreNotComplete() {
        String sql = incompleteSql(null, null);
        assertTrue(sql.contains("s.status<>'COMPLETE'"), sql);
        assertFalse(sql.contains("exists ("), sql);
        assertFalse(sql.contains("created_at>="), sql);
    }

    @Test
    void incompleteOnlyWithReceivedRangeAppliesBoth() {
        String sql = incompleteSql(Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-11-18T23:59:59Z"));
        assertTrue(sql.contains("s.status<>'COMPLETE'"), sql);
        assertTrue(sql.contains("s.created_at>=:createdFrom") && sql.contains("s.created_at<=:createdTo"), sql);
    }

    @Test
    void searchWithoutTheFlagDoesNotFilterOnStatus() {
        String sql = sqlFor(new SearchCriterion("contractNumber", MetadataSearchMode.EXACT, List.of("1")), null, null);
        assertFalse(sql.contains("s.status<>'COMPLETE'"), sql);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentlyViewedReadsOnlyTheActorsOwnSuccessfulOpensOfAvailableDocuments() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        repository.findRecentlyViewedDocuments(TENANT, "maya.okafor", Instant.parse("2026-08-20T00:00:00Z"), 500);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sqlCaptor.capture(), params.capture(), any(RowMapper.class));
        String sql = sqlCaptor.getValue().replaceAll("\\s+", " ");
        assertTrue(sql.contains("tenant_id=:tenantId"), sql);
        assertTrue(sql.contains("action='DOCUMENT_VIEW_OPENED'"), sql);
        assertTrue(sql.contains("actor_id=:actorId"), sql);
        assertTrue(sql.contains("outcome='SUCCESS'"), sql);
        assertTrue(sql.contains("d.status='AVAILABLE'"), "only documents still available (nothing past retention or deleted): " + sql);
        assertEquals("maya.okafor", params.getValue().getValue("actorId"));
        assertEquals(TENANT, params.getValue().getValue("tenantId"));
        assertEquals(500, params.getValue().getValue("scanLimit"));
    }

    @Test
    void receivedRangeAloneFiltersOnSubmissionCreatedAt() {
        String sql = sqlFor(null, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-15T23:59:59Z"));
        assertTrue(sql.contains("s.created_at>=:createdFrom"), sql);
        assertTrue(sql.contains("s.created_at<=:createdTo"), sql);
        assertFalse(sql.contains("exists ("), "A date range alone needs no metadata criterion: " + sql);
    }
}
