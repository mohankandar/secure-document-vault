package com.sdv.platform.storage.local;

import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Component
public class LocalFilesystemStorageProvider implements StorageProvider {
    public static final String PROVIDER_ID = "LOCAL_FILESYSTEM";

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public StoredObject store(UUID tenantId, StorageVolume volume, ContentWriter writer) throws IOException {
        requireTenantAndProvider(tenantId, volume);
        if (volume.state() != StorageVolumeState.ACTIVE) {
            throw new IOException("Storage volume is not ACTIVE for new writes.");
        }
        if (writer == null) throw new IllegalArgumentException("Content writer is required.");

        Path root = Path.of(volume.rootPath()).toAbsolutePath().normalize();
        String objectId = UUID.randomUUID().toString().replace("-", "");
        String objectKey = "objects/" + objectId.substring(0, 2) + "/" + objectId.substring(2, 4) + "/" + objectId + ".sdvobj";
        Path target = resolveObject(root, objectKey);
        Path stagingDir = root.resolve(".staging").normalize();
        Files.createDirectories(stagingDir);
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempFile(stagingDir, "sdv-", ".part");

        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(staging,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                OutputStream output = Channels.newOutputStream(channel);
                writer.write(output);
                output.flush();
                channel.force(true);
            }
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                throw new IOException("Configured filesystem does not support required atomic object finalization.", ex);
            }
            moved = true;
            return new StoredObject(objectKey, Files.size(target));
        } finally {
            if (!moved) Files.deleteIfExists(staging);
        }
    }

    @Override
    public InputStream open(UUID tenantId, StorageVolume volume, String objectKey) throws IOException {
        requireTenantAndProvider(tenantId, volume);
        if (volume.state() == StorageVolumeState.OFFLINE) throw new IOException("Storage volume is OFFLINE.");
        Path path = resolveObject(Path.of(volume.rootPath()).toAbsolutePath().normalize(), objectKey);
        return Files.newInputStream(path, StandardOpenOption.READ);
    }

    @Override
    public void delete(UUID tenantId, StorageVolume volume, String objectKey) throws IOException {
        requireTenantAndProvider(tenantId, volume);
        Path path = resolveObject(Path.of(volume.rootPath()).toAbsolutePath().normalize(), objectKey);
        Files.deleteIfExists(path);
    }

    @Override
    public boolean exists(UUID tenantId, StorageVolume volume, String objectKey) throws IOException {
        requireTenantAndProvider(tenantId, volume);
        Path path = resolveObject(Path.of(volume.rootPath()).toAbsolutePath().normalize(), objectKey);
        return Files.isRegularFile(path);
    }

    private static void requireTenantAndProvider(UUID tenantId, StorageVolume volume) {
        if (volume == null) throw new IllegalArgumentException("Storage volume is required.");
        if (!volume.tenantId().equals(tenantId)) throw new SecurityException("Storage volume tenant does not match request tenant.");
        if (!PROVIDER_ID.equals(volume.providerId())) throw new IllegalArgumentException("Unsupported storage provider: " + volume.providerId());
    }

    private static Path resolveObject(Path root, String objectKey) throws IOException {
        if (objectKey == null || objectKey.isBlank()) throw new IllegalArgumentException("objectKey is required.");
        Path relative = Path.of(objectKey.replace('/', java.io.File.separatorChar));
        if (relative.isAbsolute() || objectKey.contains("..") || objectKey.contains("\\")) {
            throw new IOException("Invalid opaque storage object key.");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) throw new IOException("Storage object key escapes configured root.");
        return resolved;
    }
}
