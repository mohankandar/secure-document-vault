package com.sdv.platform.application.redaction;

import java.time.Instant;
import java.util.UUID;

public record RedactionOperation(
        UUID operationId, UUID tenantId, UUID documentId, UUID sourceId, UUID submissionId,
        String actorId, String requestSha256, String reason, String regionsJson,
        String oldProviderId, UUID oldStorageVolumeId, String oldObjectKey,
        long oldEncryptedSizeBytes, String oldIntegrityValue, String oldContentSha256, long oldContentSizeBytes,
        String newProviderId, UUID newStorageVolumeId, String newObjectKey,
        long newEncryptedSizeBytes, String newIntegrityValue,
        String newCryptoProfile, int newCryptoVersion, String newKeyProviderId,
        String newProtectedDataKeyReference, String newKeyProviderVersion,
        String newContentSha256, long newContentSizeBytes, Instant createdAt) {
}
