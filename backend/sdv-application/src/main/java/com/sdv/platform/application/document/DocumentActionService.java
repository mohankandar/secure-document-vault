package com.sdv.platform.application.document;

import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.access.SensitiveAccessGrants;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessActivityQueryPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.DocumentTagRepository;
import com.sdv.platform.application.port.DocumentCommentRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Tags, comments, print events and a document's own activity. Task 0026: each one first checks that the person may
 * open the document at its effective level ({@link DocumentAccessGuard}); print also refuses Highly Confidential.
 */
@Service
public class DocumentActionService {
    private static final int MAX_TAG_LENGTH = 80;
    private static final int MAX_COMMENT_LENGTH = 2000;
    private final DocumentIntakeRepository documents;
    private final DocumentTagRepository tags;
    private final DocumentCommentRepository comments;
    private final BusinessActivityQueryPort activity;
    private final AuditEventPort audit;
    private final DocumentAccessGuard access;
    private final Clock clock = Clock.systemUTC();

    public DocumentActionService(DocumentIntakeRepository documents,
                                 DocumentTagRepository tags,
                                 DocumentCommentRepository comments,
                                 BusinessActivityQueryPort activity,
                                 AuditEventPort audit,
                                 DocumentAccessGuard access) {
        this.documents = documents;
        this.tags = tags;
        this.comments = comments;
        this.activity = activity;
        this.audit = audit;
        this.access = Objects.requireNonNull(access, "access");
    }

    public List<DocumentTagRepository.DocumentTag> tags(UUID tenantId, UUID documentId, Clearance clearance,
                                                        String actorId, String correlationId) {
        requireAvailable(tenantId, documentId, clearance, DocumentAccessGuard.Attempt.TAGS, actorId, correlationId);
        return tags.findByDocument(tenantId, documentId);
    }

    public DocumentTagRepository.DocumentTag addTag(UUID tenantId, UUID documentId, String rawTag, Clearance clearance,
                                                     String actorId, String correlationId) {
        Document document = requireAvailable(tenantId, documentId, clearance, DocumentAccessGuard.Attempt.ADD_TAG,
                actorId, correlationId);
        String tag = rawTag == null ? "" : rawTag.trim();
        if (tag.isEmpty()) throw new CodedRefusal.BadRequest(SdvErrorCode.DOC_010, "Tag is required.");
        if (tag.length() > MAX_TAG_LENGTH) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.DOC_010, "Tag exceeds " + MAX_TAG_LENGTH + " characters.");
        }
        DocumentTagRepository.DocumentTag created = tags.add(tenantId, documentId, tag, actorId, clock.instant());
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId,
                "DOCUMENT_TAG_ADDED", "DOCUMENT", document.documentId().toString(), clock.instant(), correlationId,
                AuditOutcome.SUCCESS, "{\"tagAdded\":true}"));
        return created;
    }

    public List<DocumentCommentRepository.DocumentComment> comments(UUID tenantId, UUID documentId, Clearance clearance,
                                                                    String actorId, String correlationId) {
        requireAvailable(tenantId, documentId, clearance, DocumentAccessGuard.Attempt.COMMENTS, actorId, correlationId);
        return comments.findByDocument(tenantId, documentId);
    }

    public DocumentCommentRepository.DocumentComment addComment(UUID tenantId, UUID documentId, String rawComment,
                                                                 Clearance clearance, String actorId, String correlationId) {
        Document document = requireAvailable(tenantId, documentId, clearance, DocumentAccessGuard.Attempt.ADD_COMMENT,
                actorId, correlationId);
        String comment = rawComment == null ? "" : rawComment.trim();
        if (comment.isEmpty()) throw new CodedRefusal.BadRequest(SdvErrorCode.DOC_010, "Comment is required.");
        if (comment.length() > MAX_COMMENT_LENGTH) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.DOC_010,
                    "Comment exceeds " + MAX_COMMENT_LENGTH + " characters.");
        }
        DocumentCommentRepository.DocumentComment created = comments.add(tenantId, documentId, comment, actorId, clock.instant());
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId,
                "DOCUMENT_COMMENT_ADDED", "DOCUMENT", document.documentId().toString(), clock.instant(), correlationId,
                AuditOutcome.SUCCESS, "{\"commentAdded\":true}"));
        return created;
    }

    /**
     * The event names the effective level. Task 0028: Highly Confidential needs a valid print grant for this person
     * and document, which this uses up (one print per approval); the event then names the request it used.
     * <p>
     * Task 0043 (owner answer 4 to report 0034, the download's rule): the screen prints only after Vero answers this
     * call, so the grant counts as used only once the print is recorded. If the print event can't be written, the
     * print doesn't go ahead and the grant is given back (audited), so it can still be used once.
     */
    public void recordPrint(UUID tenantId, UUID documentId, Clearance clearance, String actorId, String correlationId) {
        Document document = requireAvailable(tenantId, documentId, clearance, DocumentAccessGuard.Attempt.PRINT,
                actorId, correlationId);
        DocumentAccessGuard.PrintOrDownload allowed = access.requirePrintOrDownload(document, clearance,
                DocumentAccessGuard.Attempt.PRINT, actorId, correlationId, SensitiveAccessGrants.Use.USE);
        SensitivityLevel level = allowed.level();
        try {
            audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId,
                    "DOCUMENT_PRINTED", "DOCUMENT", document.documentId().toString(), clock.instant(), correlationId,
                    AuditOutcome.SUCCESS, "{\"currentApproved\":true,\"sensitivityLevel\":\"" + level.name() + "\""
                            + (allowed.grantId() == null ? "" : ",\"accessRequestId\":\"" + allowed.grantId() + "\"")
                            + com.sdv.platform.application.access.AuditRoles.field(clearance) + "}"));
        } catch (RuntimeException ex) {
            if (allowed.grantId() != null) {
                access.giveBack(document, DocumentAccessGuard.Attempt.PRINT, allowed.grantId(), actorId, correlationId,
                        "print not recorded");
            }
            throw ex;
        }
    }

    public List<ActivityEntry> activity(UUID tenantId, UUID documentId, Instant from, Instant to,
                                        String action, String actorId, boolean includeViewEvents, int limit,
                                        Clearance clearance, String requesterId, String correlationId) {
        requireAvailable(tenantId, documentId, clearance, DocumentAccessGuard.Attempt.ACTIVITY, requesterId, correlationId);
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 200);
        return activity.findDocumentActivity(tenantId, documentId, from, to, blank(action), blank(actorId), includeViewEvents, safeLimit)
                .stream().map(ActivityEntry::from).toList();
    }

    /** The level check comes before the status check, so a restricted document reveals nothing, not even its status. */
    private Document requireAvailable(UUID tenantId, UUID documentId, Clearance clearance,
                                      DocumentAccessGuard.Attempt attempt, String actorId, String correlationId) {
        Document document = documents.findDocumentByIdForTenant(tenantId, documentId)
                .orElseThrow(() -> DocumentRefusals.notFound(documentId));
        access.requireOpenable(document, clearance, attempt, actorId, correlationId);
        if (document.status() != DocumentStatus.AVAILABLE) {
            throw DocumentRefusals.notAvailable(documentId, document.status());
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
