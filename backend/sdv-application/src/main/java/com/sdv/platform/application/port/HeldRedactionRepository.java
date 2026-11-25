package com.sdv.platform.application.port;

import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStorageRef;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Task 0066: approved redactions of documents on legal hold (held_redactions) and their sealed originals. */
public interface HeldRedactionRepository {
    /** Whether a hold is in place. Call after locking the document's row, in a statement of its own. */
    boolean onHold(UUID tenantId, UUID documentId);

    /** Takes approved redactions to apply, for 15 minutes; counts the attempt. */
    List<Approved> claimApproved(int limit);

    /** Gives a claim back without counting the attempt. */
    void releaseClaim(UUID tenantId, UUID requestId);

    void markApplied(UUID tenantId, UUID requestId, Instant at);

    /** It can't be applied any more. */
    void giveUp(UUID tenantId, UUID requestId);

    void insertSealed(Sealed sealed);

    /** The document's sealed originals that still exist, oldest first. */
    List<Sealed> findSealed(UUID tenantId, UUID documentId);

    Optional<Sealed> findSealed(UUID tenantId, UUID documentId, UUID sealedId);

    /** Those being destroyed, and the sealed ones whose document has no hold in place. */
    List<Sealed> findToDestroy(int limit);

    /** Already DESTROYING (an earlier sweep did not finish). */
    boolean isDestroying(UUID tenantId, UUID sealedId);

    /** From SEALED to DESTROYING: nobody opens it from here on. Call with the document's row locked, after {@link #onHold}. */
    boolean startDestroying(UUID tenantId, UUID sealedId, Instant at);

    /** The database has a row for it, in any state. */
    boolean knowsSealed(UUID tenantId, UUID sealedId);

    /** SEALED or DESTROYING. */
    Optional<Sealed> findUndestroyed(UUID tenantId, UUID sealedId);

    /** To DESTROYED; its data key is forgotten. Only the erase log's handler calls it. */
    boolean markDestroyed(UUID tenantId, UUID sealedId, Instant at);

    record Approved(UUID requestId, UUID tenantId, UUID documentId, String requestedBy, String reason, String regionsJson,
                    String contentSha256, int attempts) { }

    record Sealed(UUID sealedId, UUID requestId, DocumentStorageRef storage, DocumentCryptoMetadata crypto,
                  String contentSha256, long contentSizeBytes, Instant sealedAt) {
        public UUID tenantId() {
            return storage.tenantId();
        }

        public UUID documentId() {
            return storage.documentId();
        }
    }
}
