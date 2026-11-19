package com.sdv.platform.persistence.retention;

import com.sdv.platform.application.port.RetentionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The SQL behind "the person's name with each new retention rule" (task 0019 follow-up, migration 022). */
class JdbcRetentionRepositoryCreatorNameSqlTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcRetentionRepository repository = new JdbcRetentionRepository(jdbc);

    private static String flat(String sql) { return sql.replaceAll("\\s+", " "); }

    @Test
    void theNameIsInsertedWithTheRule() {
        Instant now = Instant.parse("2026-11-18T12:00:00Z");
        repository.insertPolicyVersion(new RetentionRepository.PolicyVersion(UUID.randomUUID(), TENANT, 4, "Keep for 7 years",
                "why", 2555, now, null, "priya-sub", "Priya Natarajan", now));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());
        String s = flat(sql.getValue());
        assertTrue(s.contains("created_by, created_by_display_name, created_at)"), s);
        assertTrue(s.contains(":createdBy, :createdByDisplayName, :createdAt)"), s);
        assertEquals("Priya Natarajan", params.getValue().getValue("createdByDisplayName"));
        assertEquals("priya-sub", params.getValue().getValue("createdBy"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void bothReadsSelectTheName() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        repository.findCurrentPolicy(TENANT);
        repository.findPolicyHistory(TENANT, 50);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        for (String s : sql.getAllValues()) assertTrue(flat(s).contains("created_by, created_by_display_name, created_at"), s);
    }
}
