package com.sdv.platform.domain.document;

import java.util.UUID;

public record DocumentStorageRef(
        UUID documentId,
        UUID tenantId,
        String providerId,
        UUID storageVolumeId,
        String objectKey,
        long encryptedSizeBytes,
        String integrityValue) {
}
