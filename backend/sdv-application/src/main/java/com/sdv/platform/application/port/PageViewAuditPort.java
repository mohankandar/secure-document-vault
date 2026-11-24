package com.sdv.platform.application.port;

import com.sdv.platform.domain.audit.AuditEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Page events for one viewing (task 0007): a page sent to someone's browser is recorded once per viewing,
 * whatever the zoom. The server decides which viewing a request belongs to; the browser cannot skip the record.
 */
public interface PageViewAuditPort {
    /**
     * True when {@code viewingId} is a DOCUMENT_VIEW_OPENED event by this person, for this document in this tenant,
     * that happened at or after {@code notBefore}.
     */
    boolean isViewing(UUID tenantId, String actorId, UUID documentId, UUID viewingId, Instant notBefore);

    /**
     * Task 0066: as {@link #isViewing}, for an opening of this sealed original. An opening of the document itself is
     * never one, nor the other way round, so a page of one can't hide behind the other's record.
     */
    default boolean isSealedViewing(UUID tenantId, String actorId, UUID documentId, UUID sealedId, UUID viewingId, Instant notBefore) {
        return false;
    }

    /**
     * Appends {@code pageEvent} unless a DOCUMENT_PAGE_VIEWED event for the same viewing and page already exists
     * (at or after {@code notBefore}). Atomic per viewing and page. Returns true when the event was written.
     */
    boolean appendPageEventOnce(AuditEvent pageEvent, UUID viewingId, int pageNumber, Instant notBefore);

    /** Task 0063: appends {@code event} (DOCUMENT_PREVIEWED) unless this viewing already has one. Atomic per viewing. */
    boolean appendPreviewEventOnce(AuditEvent event, UUID viewingId, Instant notBefore);
}
