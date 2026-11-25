package com.sdv.platform.persistence.retention;

import com.sdv.platform.application.port.EraseRepository;
import com.sdv.platform.domain.identity.VeroProduct;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcEraseRepository implements EraseRepository {
    /** A record (r) with values, at least one erased document, no other document, and no delivery still open. */
    private static final String WITHOUT_DOCUMENTS = """
            exists (select 1 from fld_record f
                     where f.entity_uuid=r.tenant_id and f.product_code='%s'
                       and f.record_type='s-'||r.source_id::text and f.record_id=r.record_id::text)
            and exists (select 1 from submissions s join documents d on d.tenant_id=s.tenant_id and d.submission_id=s.submission_id
                         where s.tenant_id=r.tenant_id and s.source_id=r.source_id and s.record_id=r.record_id)
            and not exists (select 1 from submissions s join documents d on d.tenant_id=s.tenant_id and d.submission_id=s.submission_id
                         where s.tenant_id=r.tenant_id and s.source_id=r.source_id and s.record_id=r.record_id and d.status<>'EXPIRED_DELETED')
            and not exists (select 1 from submissions s
                         where s.tenant_id=r.tenant_id and s.source_id=r.source_id and s.record_id=r.record_id and s.status in ('OPEN','RECEIVING'))
            """.formatted(VeroProduct.CODE);

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcEraseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean audited(UUID auditEventId) {
        return yes("select exists(select 1 from audit_events where audit_event_id=:id)", new MapSqlParameterSource("id", auditEventId));
    }

    @Override
    public boolean kept(UUID tenantId, UUID documentId) {
        return yes("select exists(select 1 from documents where tenant_id=:tenantId and document_id=:documentId and exists ("
                + JdbcRetentionRepository.ACTIVE_HOLD + "))", document(tenantId, documentId));
    }

    @Override
    public boolean eraseContent(UUID tenantId, UUID documentId, Instant at, String reason) {
        MapSqlParameterSource p = document(tenantId, documentId);
        jdbc.queryForList("select document_id from documents where tenant_id=:tenantId and document_id=:documentId for update", p, UUID.class);
        jdbc.update("delete from document_redaction_operations where tenant_id=:tenantId and document_id=:documentId", p);
        jdbc.update("delete from document_crypto_metadata where tenant_id=:tenantId and document_id=:documentId", p);
        jdbc.update("delete from document_storage_refs where tenant_id=:tenantId and document_id=:documentId", p);
        return jdbc.update("""
                update documents
                   set status='EXPIRED_DELETED', deleted_at=:at, deletion_reason=:reason,
                       retention_delete_started_at=null, processing_error_code=null
                 where tenant_id=:tenantId and document_id=:documentId and status<>'EXPIRED_DELETED'
                """, p.addValue("at", Timestamp.from(at)).addValue("reason", reason)) == 1;
    }

    @Override
    public List<RecordKey> findRecordsToErase(int limit) {
        return jdbc.query("select r.tenant_id, r.source_id, r.record_id from records r where " + WITHOUT_DOCUMENTS + " order by r.created_at limit :limit",
                new MapSqlParameterSource("limit", limit), (rs, row) -> new RecordKey(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("source_id", UUID.class), rs.getObject("record_id", UUID.class)));
    }

    @Override
    public boolean lockRecordToErase(RecordKey key) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenantId", key.tenantId())
                .addValue("sourceId", key.sourceId()).addValue("recordId", key.recordId());
        String own = " from records r where r.tenant_id=:tenantId and r.source_id=:sourceId and r.record_id=:recordId ";
        // The same lock as intake's (PlatformRecordValues.store); then a statement of its own, so it sees what committed meanwhile.
        if (jdbc.queryForList("select r.record_id" + own + "for no key update", p, UUID.class).isEmpty()) return false;
        return yes("select exists(select 1" + own + "and " + WITHOUT_DOCUMENTS + ")", p);
    }

    private boolean yes(String sql, MapSqlParameterSource params) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, params, Boolean.class));
    }

    private static MapSqlParameterSource document(UUID tenantId, UUID documentId) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("documentId", documentId);
    }
}
