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

    List<DueDocument> findDueDocuments(int limit);

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
            Instant createdAt) { }

    record DueDocument(UUID tenantId, UUID documentId) { }
}
