package com.sdv.platform.web.business;

import com.sdv.platform.application.document.DocumentActionService;
import com.sdv.platform.application.port.DocumentTagRepository;
import com.sdv.platform.application.port.DocumentCommentRepository;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/business/documents/{documentId}")
public class DocumentActionController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final DocumentActionService actions;

    public DocumentActionController(SecurityContextFacade security, AuthorizationService authorization, DocumentActionService actions) {
        this.security = security;
        this.authorization = authorization;
        this.actions = actions;
    }

    @GetMapping("/tags")
    public List<TagView> tags(@PathVariable UUID documentId) {
        SdvPrincipal principal = require(Capability.VIEW_PDF);
        return actions.tags(principal.tenantId(), documentId).stream().map(TagView::from).toList();
    }

    @PostMapping("/tags")
    @ResponseStatus(HttpStatus.CREATED)
    public TagView addTag(@PathVariable UUID documentId, @RequestBody TagRequest request) {
        SdvPrincipal principal = require(Capability.ADD_DOCUMENT_TAG);
        return TagView.from(actions.addTag(principal.tenantId(), documentId, request == null ? null : request.tag(), principal.subject(), correlationId()));
    }

    @GetMapping("/comments")
    public List<CommentView> comments(@PathVariable UUID documentId) {
        SdvPrincipal principal = require(Capability.VIEW_PDF);
        return actions.comments(principal.tenantId(), documentId).stream().map(CommentView::from).toList();
    }

    @PostMapping("/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentView addComment(@PathVariable UUID documentId, @RequestBody CommentRequest request) {
        SdvPrincipal principal = require(Capability.ADD_DOCUMENT_COMMENT);
        return CommentView.from(actions.addComment(principal.tenantId(), documentId, request == null ? null : request.comment(), principal.subject(), correlationId()));
    }

    @PostMapping("/print-event")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void print(@PathVariable UUID documentId) {
        SdvPrincipal principal = require(Capability.PRINT_DOCUMENT);
        actions.recordPrint(principal.tenantId(), documentId, principal.subject(), correlationId());
    }

    @GetMapping("/activity")
    public List<DocumentActionService.ActivityEntry> activity(@PathVariable UUID documentId,
                                                               @RequestParam(required = false) Instant from,
                                                               @RequestParam(required = false) Instant to,
                                                               @RequestParam(required = false) String action,
                                                               @RequestParam(required = false) String actorId,
                                                               @RequestParam(defaultValue = "false") boolean includeViewEvents,
                                                               @RequestParam(defaultValue = "100") int limit) {
        SdvPrincipal principal = require(Capability.SEARCH_RECORDS);
        return actions.activity(principal.tenantId(), documentId, from, to, action, actorId, includeViewEvents, limit);
    }

    private SdvPrincipal require(Capability capability) {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, capability);
        return principal;
    }

    private static String correlationId() { return MDC.get("correlationId"); }

    public record TagRequest(String tag) { }
    public record CommentRequest(String comment) { }
    public record TagView(UUID tagId, UUID documentId, String tag, String createdBy, Instant createdAt) {
        static TagView from(DocumentTagRepository.DocumentTag tag) {
            return new TagView(tag.tagId(), tag.documentId(), tag.tagValue(), tag.createdBy(), tag.createdAt());
        }
    }
    public record CommentView(UUID commentId, UUID documentId, String comment, String createdBy, Instant createdAt) {
        static CommentView from(DocumentCommentRepository.DocumentComment comment) {
            return new CommentView(comment.commentId(), comment.documentId(), comment.commentText(), comment.createdBy(), comment.createdAt());
        }
    }
}
