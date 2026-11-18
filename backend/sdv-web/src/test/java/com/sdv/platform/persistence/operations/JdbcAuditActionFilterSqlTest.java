package com.sdv.platform.persistence.operations;

import com.sdv.platform.application.port.AuditQueryPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0011 export: the activity group and hidden routine codes are applied in SQL, within the tenant. */
class JdbcAuditActionFilterSqlTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcOperationalRepository repository = new JdbcOperationalRepository(jdbc);

    private static AuditQueryPort.Filter filter(Set<String> actions, Set<String> excluded) {
        return new AuditQueryPort.Filter(null, null, null, null, null, null, null, null, null, null, null, null, actions, excluded);
    }

    @Test
    @SuppressWarnings("unchecked")
    void includedAndExcludedCodesAreBoundParametersInsideTheTenant() {
        repository.findRows(TENANT, filter(Set.of("DOCUMENT_DOWNLOADED"), Set.of("AUDIT_VIEWED")), 10_001);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        String s = sql.getValue().replaceAll("\\s+", " ");
        assertTrue(s.contains("where tenant_id=:tenantId"), s);
        assertTrue(s.contains("and action in (:actions)"), s);
        assertTrue(s.contains("and action not in (:excludeActions)"), s);
        assertEquals(Set.of("DOCUMENT_DOWNLOADED"), params.getValue().getValue("actions"));
        assertEquals(Set.of("AUDIT_VIEWED"), params.getValue().getValue("excludeActions"));
        assertEquals(10_001, params.getValue().getValue("limit"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void noSetsMeansNoActionClauseAndAnEmptyIncludeSetMatchesNothing() {
        repository.findRows(TENANT, filter(null, Set.of()), 51);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertFalse(sql.getValue().contains(":actions"));
        assertFalse(sql.getValue().contains(":excludeActions"));

        reset(jdbc);
        assertTrue(repository.findRows(TENANT, filter(Set.of(), null), 51).isEmpty());
        verifyNoInteractions(jdbc);
    }
}
