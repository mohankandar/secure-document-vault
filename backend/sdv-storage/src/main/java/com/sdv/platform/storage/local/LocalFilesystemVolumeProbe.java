package com.sdv.platform.storage.local;

import com.sdv.platform.application.port.StorageVolumeProbe;
import com.sdv.platform.domain.storage.StorageCapacitySnapshot;
import org.springframework.stereotype.Component;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class LocalFilesystemVolumeProbe implements StorageVolumeProbe {
    /** Where documents may be kept when the installer names nothing: this folder under Vero's working folder. */
    static final String DEFAULT_ROOT = "data";

    private final List<Path> roots;

    /** @param allowedRoots `sdv.storage.allowed-roots`: folders, separated by ';'. Empty: only {@value #DEFAULT_ROOT}. */
    @Autowired
    public LocalFilesystemVolumeProbe(@Value("${sdv.storage.allowed-roots:}") String allowedRoots) {
        List<Path> parsed = new ArrayList<>();
        for (String part : (allowedRoots == null || allowedRoots.isBlank() ? DEFAULT_ROOT : allowedRoots).split(";")) {
            if (part.isBlank()) continue;
            try {
                if (shareOrDevice(part.trim())) throw new IllegalArgumentException("a network share or device path");
                parsed.add(Path.of(part.trim()).toAbsolutePath().normalize());
            } catch (RuntimeException ex) {
                throw new StartupFailure(SdvErrorCode.SYS_005, "sdv.storage.allowed-roots (SDV_STORAGE_ALLOWED_ROOTS) must list folders of this "
                        + "computer, separated by ';' (no network shares or device paths).");
            }
        }
        if (parsed.isEmpty()) throw new StartupFailure(SdvErrorCode.SYS_005, "sdv.storage.allowed-roots (SDV_STORAGE_ALLOWED_ROOTS) lists no folder.");
        this.roots = List.copyOf(parsed);
    }

    private static boolean shareOrDevice(String path) {
        return path.startsWith("\\\\") || path.startsWith("//") || path.startsWith("\\/") || path.startsWith("/\\");
    }

    @Override
    public boolean allowed(String rootPath) {
        if (rootPath == null || rootPath.isBlank() || shareOrDevice(rootPath.trim())) return false;
        Path path;
        try {
            path = normalizePath(rootPath);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        for (Path root : roots) {
            if (path.startsWith(root) && noLinkBetween(root, path)) return true;
        }
        return false;
    }

    /** Every folder from the allowed root down to the path that already exists is itself, not a link or junction elsewhere. */
    private static boolean noLinkBetween(Path root, Path path) {
        try {
            if (!Files.exists(root)) return true;
            Path existing = path;
            while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS) && existing.startsWith(root)) existing = existing.getParent();
            return existing.toRealPath().equals(root.toRealPath().resolve(root.relativize(existing)).normalize());
        } catch (IOException | SecurityException ex) {
            return false;
        }
    }

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
