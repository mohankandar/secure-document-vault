package com.sdv.platform.persistence.business;

import com.sdv.platform.application.port.BusinessActivityQueryPort;
import com.sdv.platform.application.port.DocumentTagRepository;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcDocumentActionRepository implements DocumentTagRepository, RoleCapabilityPolicyPort, BusinessActivityQueryPort {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDocumentActionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DocumentTag> findByDocument(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                select tag_id, document_id, tag_value, created_by, created_at
                  from document_tags
                 where tenant_id=:tenantId and document_id=:documentId
                 order by lower(tag_value), created_at, tag_id
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("documentId", documentId),
                (rs, row) -> new DocumentTag(rs.getObject("tag_id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getString("tag_value"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant()));
    }

    @Override
    public DocumentTag add(UUID tenantId, UUID documentId, String tagValue, String createdBy, Instant createdAt) {
        UUID tagId = UUID.randomUUID();
        jdbc.update("""
                insert into document_tags(tag_id, tenant_id, document_id, tag_value, created_by, created_at)
                values(:tagId,:tenantId,:documentId,:tagValue,:createdBy,:createdAt)
                on conflict (tenant_id, document_id, tag_value) do update set tag_value=excluded.tag_value
                """, new MapSqlParameterSource()
                .addValue("tagId", tagId).addValue("tenantId", tenantId).addValue("documentId", documentId)
                .addValue("tagValue", tagValue).addValue("createdBy", createdBy).addValue("createdAt", Timestamp.from(createdAt)));
        return jdbc.queryForObject("""
                select tag_id, document_id, tag_value, created_by, created_at
                  from document_tags
                 where tenant_id=:tenantId and document_id=:documentId and tag_value=:tagValue
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("documentId", documentId).addValue("tagValue", tagValue),
                (rs, row) -> new DocumentTag(rs.getObject("tag_id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getString("tag_value"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant()));
    }

    @Override
    public Map<String, Boolean> findOverrides(UUID tenantId, String roleName) {
        LinkedHashMap<String, Boolean> result = new LinkedHashMap<>();
        jdbc.query("""
                select capability, enabled
                  from tenant_role_capability_overrides
                 where tenant_id=:tenantId and role_name=:roleName
                 order by capability
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("roleName", roleName),
                rs -> { result.put(rs.getString("capability"), rs.getBoolean("enabled")); });
        return Map.copyOf(result);
    }

    @Override
    public void replaceOverrides(UUID tenantId, String roleName, Map<String, Boolean> capabilityStates, String updatedBy) {
        jdbc.update("delete from tenant_role_capability_overrides where tenant_id=:tenantId and role_name=:roleName",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("roleName", roleName));
        Instant now = Instant.now();
        for (Map.Entry<String, Boolean> entry : capabilityStates.entrySet()) {
            jdbc.update("""
                    insert into tenant_role_capability_overrides(tenant_id, role_name, capability, enabled, updated_by, updated_at)
                    values(:tenantId,:roleName,:capability,:enabled,:updatedBy,:updatedAt)
                    """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("roleName", roleName)
                    .addValue("capability", entry.getKey()).addValue("enabled", entry.getValue())
                    .addValue("updatedBy", updatedBy).addValue("updatedAt", Timestamp.from(now)));
        }
    }

    @Override
    public List<AuditEvent> findDocumentActivity(UUID tenantId, UUID documentId, Instant from, Instant to,
                                                  String action, String actorId, boolean includeViewEvents, int limit) {
        StringBuilder sql = new StringBuilder("""
                select audit_event_id, tenant_id, actor_type, actor_id, action, target_type, target_id,
                       occurred_at, correlation_id, outcome, safe_details::text as safe_details_json
                  from audit_events
                 where tenant_id=:tenantId and target_type='DOCUMENT' and target_id=:documentId
                """);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("documentId", documentId.toString()).addValue("limit", limit);
        if (from != null) { sql.append(" and occurred_at>=:from"); p.addValue("from", Timestamp.from(from)); }
        if (to != null) { sql.append(" and occurred_at<=:to"); p.addValue("to", Timestamp.from(to)); }
        if (action != null) { sql.append(" and action=:action"); p.addValue("action", action); }
        if (actorId != null) { sql.append(" and actor_id=:actorId"); p.addValue("actorId", actorId); }
        if (!includeViewEvents) {
            sql.append(" and action not in ('DOCUMENT_VIEW_OPENED','DOCUMENT_PAGE_VIEWED','DOCUMENT_TEXT_SEARCHED')");
        }
        sql.append(" order by occurred_at desc, audit_event_id desc limit :limit");
        return jdbc.query(sql.toString(), p, (rs, row) -> new AuditEvent(
                rs.getObject("audit_event_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("actor_type"), rs.getString("actor_id"), rs.getString("action"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("correlation_id"), AuditOutcome.valueOf(rs.getString("outcome")), rs.getString("safe_details_json")));
    }
}
