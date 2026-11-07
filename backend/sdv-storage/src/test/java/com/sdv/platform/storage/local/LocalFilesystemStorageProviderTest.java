package com.sdv.platform.storage.local;

import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalFilesystemStorageProviderTest {
    @TempDir
    Path temp;

    @Test
    void writesOpaqueCiphertextObjectAtomicallyAndReadsItBack() throws Exception {
        UUID tenant = UUID.randomUUID();
        StorageVolume volume = volume(tenant, StorageVolumeState.ACTIVE);
        var provider = new LocalFilesystemStorageProvider();
        byte[] ciphertext = "not-readable-pdf-ciphertext".getBytes();

        var stored = provider.store(tenant, volume, out -> out.write(ciphertext));
        assertTrue(stored.objectKey().startsWith("objects/"));
        assertFalse(stored.objectKey().contains(tenant.toString()));
        assertEquals(ciphertext.length, stored.sizeBytes());
        assertTrue(provider.exists(tenant, volume, stored.objectKey()));

        try (var in = provider.open(tenant, volume, stored.objectKey())) {
            assertArrayEquals(ciphertext, in.readAllBytes());
        }
        provider.delete(tenant, volume, stored.objectKey());
        assertFalse(provider.exists(tenant, volume, stored.objectKey()));
    }

    @Test
    void rejectsWritesToReadOnlyVolumesAndTenantMismatchAndTraversal() {
        UUID tenant = UUID.randomUUID();
        var provider = new LocalFilesystemStorageProvider();
        assertThrows(IOException.class, () -> provider.store(
                tenant, volume(tenant, StorageVolumeState.READ_ONLY), out -> out.write(1)));
        assertThrows(SecurityException.class, () -> provider.store(
                UUID.randomUUID(), volume(tenant, StorageVolumeState.ACTIVE), out -> out.write(1)));
        assertThrows(IOException.class, () -> provider.open(
                tenant, volume(tenant, StorageVolumeState.ACTIVE), "../escape.sdvobj"));
    }

    private StorageVolume volume(UUID tenant, StorageVolumeState state) {
        Instant now = Instant.now();
        return new StorageVolume(UUID.randomUUID(), tenant, "LOCAL_FILESYSTEM", "test",
                temp.toAbsolutePath().normalize().toString(), state, 10, 90, 95, now, now);
    }
}
