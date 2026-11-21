package com.sdv.platform.application.port;

import com.sdv.platform.domain.document.SensitivityLevel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0027: legal holds (table legal_holds, migration 026). Everything is read and written within one tenant.
 * <p>
 * {@link #lockDocument} and the retention cleanup's claim (JdbcRetentionRepository.claimDueDocument) both take the
 * document row's lock first, so a hold and a deletion can't pass each other: whichever comes second sees the other.
 */
public interface LegalHoldRepository {
    /** The document, with its row locked until the transaction ends (select ... for update); empty when unknown. */
    Optional<DocumentFacts> lockDocument(UUID tenantId, UUID documentId);

    /** The hold, with its row locked until the transaction ends; empty when unknown. */
    Optional<LegalHold> lockHold(UUID tenantId, UUID holdId);

    void insert(LegalHold hold);

    /** Releases an active hold; fails unless exactly one active hold was released. */
    /** Task 0028, 0.3: releasedByName is the name from the signed-in session at the time (null when unknown). */
    void release(UUID tenantId, UUID holdId, String releasedBy, String releasedByName, Instant releasedAt);

    /**
     * Documents whose record's primary identifier (its first business identifier in display order, as the record
     * number elsewhere) has this value, as typed or as stored after normalization. Documents the retention cleanup
     * deleted or is deleting are left out. Newest first.
     */
    List<DocumentFacts> findByPrimaryIdentifier(UUID tenantId, String value, int limit);

    /** Holds in place, newest first, each with its document. */
    List<HoldRow> findActive(UUID tenantId, int limit);

    /**
     * What a Business Admin may see of a document: no title, file name or content. activeHoldId is null when the
     * document is not on hold. status and the IDs are for the server's checks; the views decide what is sent.
     */
    record DocumentFacts(
            UUID documentId,
            UUID sourceId,
            UUID submissionId,
            String status,
            SensitivityLevel ownLevel,
            Instant receivedAt,
            Integer pageCount,
            String primaryIdentifier,
            String primaryIdentifierLabel,
            UUID activeHoldId) { }

    record LegalHold(
            UUID holdId,
            UUID tenantId,
            UUID documentId,
            String reason,
            String placedBy,
            /* Task 0028, 0.3: names from the signed-in session at the time of the action; null for older holds. */
            String placedByName,
            Instant placedAt,
            String releasedBy,
            String releasedByName,
            Instant releasedAt) {
        public boolean active() {
            return releasedAt == null;
        }
    }

    record HoldRow(LegalHold hold, DocumentFacts document) { }
}
