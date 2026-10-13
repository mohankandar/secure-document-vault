package com.sdv.platform.application.port;

import com.sdv.platform.domain.storage.StorageVolume;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public interface StorageProvider {
    String providerId();
    StoredObject store(UUID tenantId, StorageVolume volume, ContentWriter writer) throws IOException;
    InputStream open(UUID tenantId, StorageVolume volume, String objectKey) throws IOException;
    void delete(UUID tenantId, StorageVolume volume, String objectKey) throws IOException;
    boolean exists(UUID tenantId, StorageVolume volume, String objectKey) throws IOException;

    @FunctionalInterface
    interface ContentWriter {
        void write(OutputStream output) throws IOException;
    }

    record StoredObject(String objectKey, long sizeBytes) { }
}
