package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RetentionRepository {
    void updateDefaultRetentionDays(UUID tenantId, Integer defaultRetentionDays, Instant updatedAt);

    Optional<PolicyVersion> findCurrentPolicy(UUID tenantId);

    List<PolicyVersion> findPolicyHistory(UUID tenantId, int limit);

    int nextPolicyVersion(UUID tenantId);

    void closeCurrentPolicy(UUID tenantId, Instant effectiveUntil);

    void insertPolicyVersion(PolicyVersion policy);

    /** Due for deletion, oldest first. Task 0027: never a document with a legal hold in place. */
    List<DueDocument> findDueDocuments(int limit);

    /** Task 0027: how many documents are due for deletion but on legal hold (all tenants; a count only). */
    long countHeldDueDocuments();

    /**
     * Marks a due document as being deleted. Task 0027: never one with a legal hold in place; the caller runs it in
     * a transaction, because it locks the document row first.
     */
    boolean claimDueDocument(UUID tenantId, UUID documentId, Instant startedAt);

    void finalizeExpiredDocument(UUID tenantId, UUID documentId, Instant deletedAt, String deletionReason);

    record PolicyVersion(
            UUID policyVersionId,
            UUID tenantId,
            int version,
            String policyName,
            String description,
            Integer retentionDays,
            Instant effectiveFrom,
            Instant effectiveUntil,
            String createdBy,
            String createdByDisplayName,
            Instant createdAt) { }

    record DueDocument(UUID tenantId, UUID documentId) { }
}
