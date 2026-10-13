package com.sdv.platform.persistence.business;

import com.sdv.platform.application.port.DocumentCommentRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcDocumentCommentRepository implements DocumentCommentRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDocumentCommentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DocumentComment> findByDocument(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                select comment_id, document_id, comment_text, created_by, created_at
                  from document_comments
                 where tenant_id=:tenantId and document_id=:documentId
                 order by created_at desc, comment_id desc
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("documentId", documentId),
                (rs, row) -> new DocumentComment(
                        rs.getObject("comment_id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getString("comment_text"),
                        rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    @Override
    public DocumentComment add(UUID tenantId, UUID documentId, String commentText, String createdBy, Instant createdAt) {
        UUID commentId = UUID.randomUUID();
        jdbc.update("""
                insert into document_comments(comment_id, tenant_id, document_id, comment_text, created_by, created_at)
                values(:commentId,:tenantId,:documentId,:commentText,:createdBy,:createdAt)
                """, new MapSqlParameterSource()
                .addValue("commentId", commentId)
                .addValue("tenantId", tenantId)
                .addValue("documentId", documentId)
                .addValue("commentText", commentText)
                .addValue("createdBy", createdBy)
                .addValue("createdAt", Timestamp.from(createdAt)));
        return new DocumentComment(commentId, documentId, commentText, createdBy, createdAt);
    }
}
