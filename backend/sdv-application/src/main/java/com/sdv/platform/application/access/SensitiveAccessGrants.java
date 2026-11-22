package com.sdv.platform.application.access;

import java.util.Optional;
import java.util.UUID;

/**
 * Task 0028: the one-use grants a Business Admin gives a Restricted Steward to print or download one Highly
 * Confidential document. {@link DocumentAccessGuard#requirePrintOrDownload} asks here before it lets a Highly
 * Confidential document be printed or downloaded.
 */
public interface SensitiveAccessGrants {
    /** CHECK: is there a valid grant (nothing changes)? USE: use one now; it can't be used again. */
    enum Use { CHECK, USE }

    /**
     * A valid grant for this person, document and kind (PRINT or DOWNLOAD): approved, unused and inside its use
     * window. With {@link Use#USE} the grant is marked used, and audited, before this returns. Empty when there is none.
     */
    Optional<UUID> grant(UUID tenantId, UUID documentId, String actorId, DocumentAccessGuard.Attempt attempt, Use use,
                         String correlationId);

    /**
     * Task 0043: gives a used grant back (it is approved and unused again, inside the same use window), audited, when
     * nothing left Vero: a download that failed before its first byte, or a print that wasn't recorded. Only the grant
     * this person used for this document and kind. True when it was given back. No grants, nothing to give back.
     */
    default boolean giveBack(UUID tenantId, UUID documentId, String actorId, DocumentAccessGuard.Attempt attempt,
                             UUID grantId, String reason, String correlationId) {
        return false;
    }

    /** No grants at all: every Highly Confidential print and download is refused. */
    SensitiveAccessGrants NONE = (tenantId, documentId, actorId, attempt, use, correlationId) -> Optional.empty();
}
