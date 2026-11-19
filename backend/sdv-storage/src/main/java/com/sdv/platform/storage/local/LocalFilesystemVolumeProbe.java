package com.sdv.platform.storage.local;

import com.sdv.platform.application.port.StorageVolumeProbe;
import com.sdv.platform.domain.storage.StorageCapacitySnapshot;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class LocalFilesystemVolumeProbe implements StorageVolumeProbe {
    @Override
    public String normalize(String rootPath) {
        return normalizePath(rootPath).toString();
    }

    @Override
    public PreparedRoot prepare(String rootPath) {
        Path root = normalizePath(rootPath);
        try {
            Files.createDirectories(root);
            root = root.toRealPath();
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("Storage root is not a directory.");
            Path probe = Files.createTempFile(root, ".sdv-write-probe-", ".tmp");
            try {
                Files.write(probe, new byte[]{0x53, 0x44, 0x56});
            } finally {
                Files.deleteIfExists(probe);
            }
            return new PreparedRoot(root.toString(), inspect(root.toString()));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Storage root cannot be created or written: " + root, ex);
        }
    }

    @Override
    public FolderCheck check(String rootPath) {
        Path root = normalizePath(rootPath);
        Path existing = root;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) {
            return new FolderCheck(root.toString(), false, null, null, "This drive or share does not exist.");
        }
        if (!Files.isDirectory(existing)) {
            return new FolderCheck(root.toString(), false, null, null, "Part of this path is a file, not a folder.");
        }
        // Folders this check creates, outermost first; all are removed again at the end.
        List<Path> created = new ArrayList<>();
        try {
            for (Path missing = root; !missing.equals(existing); missing = missing.getParent()) created.addFirst(missing);
            for (Path folder : created) Files.createDirectory(folder);
            Path probe = Files.createTempFile(root, ".sdv-write-check-", ".tmp");
            try {
                Files.write(probe, new byte[]{0x53, 0x44, 0x56});
            } finally {
                Files.deleteIfExists(probe);
            }
            FileStore store = Files.getFileStore(root);
            return new FolderCheck(root.toString(), true, store.getUsableSpace(), store.getTotalSpace(), null);
        } catch (IOException | SecurityException ex) {
            Long usable = null;
            Long total = null;
            try {
                FileStore store = Files.getFileStore(existing);
                usable = store.getUsableSpace();
                total = store.getTotalSpace();
            } catch (IOException | SecurityException ignored) {
                // Space stays unknown.
            }
            return new FolderCheck(root.toString(), false, usable, total, "Vero cannot create or write files in this folder.");
        } finally {
            for (int i = created.size() - 1; i >= 0; i--) {
                try {
                    Files.deleteIfExists(created.get(i));
                } catch (IOException | SecurityException ignored) {
                    // A folder that cannot be removed is left; it is empty.
                }
            }
        }
    }

    @Override
    public StorageCapacitySnapshot inspect(String normalizedRootPath) {
        Path root = normalizePath(normalizedRootPath);
        try {
            if (!Files.isDirectory(root)) throw new IllegalStateException("Storage root does not exist: " + root);
            FileStore store = Files.getFileStore(root);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            int usedPercent = total <= 0 ? 100 : (int) Math.min(100.0d,
                    Math.max(0.0d, Math.floor(((total - usable) * 100.0d) / total)));
            return new StorageCapacitySnapshot(total, usable, usedPercent, Files.isWritable(root), Instant.now());
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to inspect storage root: " + root, ex);
        }
    }

    @Override
    public boolean overlaps(String normalizedRootPathA, String normalizedRootPathB) {
        Path a = normalizePath(normalizedRootPathA);
        Path b = normalizePath(normalizedRootPathB);
        return a.startsWith(b) || b.startsWith(a);
    }

    private static Path normalizePath(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) throw new IllegalArgumentException("rootPath is required.");
        try {
            return Path.of(rootPath.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("rootPath is invalid.", ex);
        }
    }
}
