package com.sdv.platform.application.retention;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.RetentionRepository;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.persistence.legalhold.JdbcLegalHoldRepository;
import com.sdv.platform.persistence.retention.JdbcRetentionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0027: the retention cleanup never deletes a document with a legal hold in place, and its run summary says how
 * many it kept (a count only). The behaviour against PostgreSQL, including a hold and a claim at the same moment, is
 * in LegalHoldPostgresTest (run by Test-SDV-SchemaUpgrade.ps1).
 */
class RetentionLegalHoldTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOCUMENT = UUID.fromString("40000000-0000-0000-0000-000000000001");

    private static String flat(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    @Test
    void theRunSummaryCountsHeldDocumentsAndEachClaimRunsInItsOwnTransaction() {
        RetentionRepository repository = mock(RetentionRepository.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.findDueDocuments(anyInt())).thenReturn(List.of(new RetentionRepository.DueDocument(TENANT, DOCUMENT)));
        when(repository.countHeldDueDocuments()).thenReturn(3L);
        when(repository.claimDueDocument(eq(TENANT), eq(DOCUMENT), any())).thenReturn(false); // someone else had it
        DocumentIntakeRepository documents = mock(DocumentIntakeRepository.class);
        RetentionDeletionService service = new RetentionDeletionService(repository, documents,
                mock(StorageVolumeRepository.class), List.of(), mock(AuditEventPort.class), tx);

        RetentionDeletionService.SweepResult result = service.sweep(100);
        assertEquals(3L, result.heldSkipped());
        assertEquals(1, result.candidates());
        assertEquals(0, result.completed());
        InOrder order = inOrder(tx, repository);
        order.verify(tx).getTransaction(any());
        order.verify(repository).claimDueDocument(eq(TENANT), eq(DOCUMENT), any());
        order.verify(tx).commit(any());
        verifyNoInteractions(documents);
        assertEquals(0L, new RetentionDeletionService.SweepResult(1, 1, 0).heldSkipped());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCleanupsQueriesSkipActiveHoldsAndTheClaimLocksTheRowFirst() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        JdbcRetentionRepository repository = new JdbcRetentionRepository(jdbc);
        String activeHold = "not exists (select 1 from legal_holds h where h.tenant_id=documents.tenant_id "
                + "and h.document_id=documents.document_id and h.released_at is null)";

        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        repository.findDueDocuments(100);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertTrue(flat(sql.getValue()).contains(activeHold), sql.getValue());

        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(2L);
        assertEquals(2L, repository.countHeldDueDocuments());
        verify(jdbc).queryForObject(sql.capture(), any(SqlParameterSource.class), eq(Long.class));
        assertTrue(flat(sql.getValue()).contains("and exists (select 1 from legal_holds h"), sql.getValue());
        assertTrue(flat(sql.getValue()).contains("expires_at <= current_timestamp"), sql.getValue());

        // An unknown document: nothing locked, nothing claimed.
        assertFalse(repository.claimDueDocument(TENANT, DOCUMENT, Instant.now()));
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));

        reset(jdbc);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of(DOCUMENT));
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        assertTrue(repository.claimDueDocument(TENANT, DOCUMENT, Instant.now()));
        InOrder order = inOrder(jdbc);
        order.verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertTrue(flat(sql.getValue()).contains("for update"), "the row is locked first");
        assertTrue(flat(sql.getValue()).contains("tenant_id=:tenantId and document_id=:documentId"));
        order.verify(jdbc).update(sql.capture(), any(SqlParameterSource.class));
        assertTrue(flat(sql.getValue()).contains(activeHold), "then claimed only without an active hold: " + sql.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyLegalHoldStatementIsLimitedToTheTenantAndReadsNoFileNameOrTitle() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of(DOCUMENT));
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        JdbcLegalHoldRepository repository = new JdbcLegalHoldRepository(jdbc);
        Optional<?> ignored = repository.lockDocument(TENANT, DOCUMENT);
        repository.lockHold(TENANT, DOCUMENT);
        repository.findByPrimaryIdentifier(TENANT, "300000000001", 100);
        repository.findActive(TENANT, 200);
        repository.insert(new com.sdv.platform.application.port.LegalHoldRepository.LegalHold(UUID.randomUUID(), TENANT,
                DOCUMENT, "Litigation", "admin", Instant.now(), null, null));
        repository.release(TENANT, DOCUMENT, "admin", Instant.now());

        ArgumentCaptor<String> reads = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> readParams = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(5)).query(reads.capture(), readParams.capture(), any(RowMapper.class));
        ArgumentCaptor<String> writes = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> writeParams = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc, times(2)).update(writes.capture(), writeParams.capture());
        for (String s : reads.getAllValues()) {
            String f = flat(s);
            assertTrue(f.contains("tenant_id=:tenantId"), f);
            assertFalse(f.contains("original_filename") || f.contains("title"), f);
        }
        for (SqlParameterSource p : readParams.getAllValues()) assertEquals(TENANT, p.getValue("tenantId"));
        for (SqlParameterSource p : writeParams.getAllValues()) assertEquals(TENANT, p.getValue("tenantId"));
        assertTrue(flat(reads.getAllValues().get(0)).contains("for update"), "placing a hold locks the document row");
        assertTrue(flat(reads.getAllValues().get(2)).contains("for update"), "releasing locks the hold row");
        String lookup = flat(reads.getAllValues().get(3));
        assertTrue(lookup.contains("d.status not in ('EXPIRED_DELETED', 'RETENTION_DELETING')"), lookup);
        assertTrue(lookup.contains("order by o.display_position nulls last, v.field_key"), "the primary identifier: " + lookup);
        assertTrue(flat(writes.getAllValues().get(1)).contains("released_at is null"), "only an active hold is released");
    }
}
