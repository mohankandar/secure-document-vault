package com.sdv.platform.application.document;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessActivityQueryPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.DocumentTagRepository;
import com.sdv.platform.application.port.DocumentCommentRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class DocumentActionService {
    private static final int MAX_TAG_LENGTH = 80;
    private static final int MAX_COMMENT_LENGTH = 2000;
    private final DocumentIntakeRepository documents;
    private final DocumentTagRepository tags;
    private final DocumentCommentRepository comments;
    private final BusinessActivityQueryPort activity;
    private final AuditEventPort audit;
    private final Clock clock = Clock.systemUTC();

    public DocumentActionService(DocumentIntakeRepository documents,
                                 DocumentTagRepository tags,
                                 DocumentCommentRepository comments,
                                 BusinessActivityQueryPort activity,
                                 AuditEventPort audit) {
        this.documents = documents;
        this.tags = tags;
        this.comments = comments;
        this.activity = activity;
        this.audit = audit;
    }

    public List<DocumentTagRepository.DocumentTag> tags(UUID tenantId, UUID documentId) {
        requireAvailable(tenantId, documentId);
        return tags.findByDocument(tenantId, documentId);
    }

    public DocumentTagRepository.DocumentTag addTag(UUID tenantId, UUID documentId, String rawTag,
                                                     String actorId, String correlationId) {
        Document document = requireAvailable(tenantId, documentId);
        String tag = rawTag == null ? "" : rawTag.trim();
        if (tag.isEmpty()) throw new IllegalArgumentException("Tag is required.");
        if (tag.length() > MAX_TAG_LENGTH) throw new IllegalArgumentException("Tag exceeds " + MAX_TAG_LENGTH + " characters.");
        DocumentTagRepository.DocumentTag created = tags.add(tenantId, documentId, tag, actorId, clock.instant());
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId,
                "DOCUMENT_TAG_ADDED", "DOCUMENT", document.documentId().toString(), clock.instant(), correlationId,
                AuditOutcome.SUCCESS, "{\"tagAdded\":true}"));
        return created;
    }

    public List<DocumentCommentRepository.DocumentComment> comments(UUID tenantId, UUID documentId) {
        requireAvailable(tenantId, documentId);
        return comments.findByDocument(tenantId, documentId);
    }

    public DocumentCommentRepository.DocumentComment addComment(UUID tenantId, UUID documentId, String rawComment,
                                                                 String actorId, String correlationId) {
        Document document = requireAvailable(tenantId, documentId);
        String comment = rawComment == null ? "" : rawComment.trim();
        if (comment.isEmpty()) throw new IllegalArgumentException("Comment is required.");
        if (comment.length() > MAX_COMMENT_LENGTH) throw new IllegalArgumentException("Comment exceeds " + MAX_COMMENT_LENGTH + " characters.");
        DocumentCommentRepository.DocumentComment created = comments.add(tenantId, documentId, comment, actorId, clock.instant());
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId,
                "DOCUMENT_COMMENT_ADDED", "DOCUMENT", document.documentId().toString(), clock.instant(), correlationId,
                AuditOutcome.SUCCESS, "{\"commentAdded\":true}"));
        return created;
    }

    public void recordPrint(UUID tenantId, UUID documentId, String actorId, String correlationId) {
        Document document = requireAvailable(tenantId, documentId);
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId,
                "DOCUMENT_PRINTED", "DOCUMENT", document.documentId().toString(), clock.instant(), correlationId,
                AuditOutcome.SUCCESS, "{\"currentApproved\":true}"));
    }

    public List<ActivityEntry> activity(UUID tenantId, UUID documentId, Instant from, Instant to,
                                        String action, String actorId, boolean includeViewEvents, int limit) {
        requireAvailable(tenantId, documentId);
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 200);
        return activity.findDocumentActivity(tenantId, documentId, from, to, blank(action), blank(actorId), includeViewEvents, safeLimit)
                .stream().map(ActivityEntry::from).toList();
    }

    private Document requireAvailable(UUID tenantId, UUID documentId) {
        Document document = documents.findDocumentByIdForTenant(tenantId, documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found."));
        if (document.status() != DocumentStatus.AVAILABLE) {
            throw new IllegalStateException("Only AVAILABLE documents support this action.");
        }
        return document;
    }

    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record ActivityEntry(UUID auditEventId, String actorType, String actorId, String action,
                                Instant occurredAt, AuditOutcome outcome, String safeDetailsJson) {
        static ActivityEntry from(AuditEvent event) {
            return new ActivityEntry(event.auditEventId(), event.actorType(), event.actorId(), event.action(),
                    event.occurredAt(), event.outcome(), event.safeDetailsJson());
        }
    }
}
