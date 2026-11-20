package com.sdv.platform.persistence.business;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Task 0023: the one query behind "has records" and "last delivered" in the search schema. */
class SourceActivitySqlTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcBusinessRecordRepository repository = new JdbcBusinessRecordRepository(jdbc);

    private record Sent(String sql, SqlParameterSource params) { }

    @SuppressWarnings("unchecked")
    private Sent send() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        repository.findSourceActivity(TENANT);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(1)).query(sql.capture(), params.capture(), any(RowMapper.class));
        return new Sent(sql.getValue().replaceAll("\\s+", " ").trim(), params.getValue());
    }

    @Test
    void oneQueryForAllSystems_readsOnlyTheCallersTenant() {
        Sent sent = send();
        assertTrue(sent.sql().contains("from ingestion_sources src where src.tenant_id=:tenantId"), sent.sql());
        // The submissions read are the same tenant's and the same system's, never another tenant's.
        assertTrue(sent.sql().contains("where s.tenant_id=src.tenant_id and s.source_id=src.source_id"), sent.sql());
        assertEquals(TENANT, sent.params().getValue("tenantId"));
    }

    @Test
    void lastDeliveryIsTheNewestActivity_andNothingIsCounted() {
        String sql = send().sql();
        assertTrue(sql.contains("select max(s.last_activity_at) from submissions s"), sql);
        assertFalse(sql.contains("count("), sql);
    }
}
