package com.sdv.platform.web.erase;

import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.web.schema.DatabaseSchemaCheck;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Task 0080: the erase log is never in a document volume, nor a volume in it: their backups would bring an old log back with the documents.
 * Checked before anything is made in the folder. (A new volume there is refused too: LocalFilesystemVolumeProbe.) A missing or relative
 * setting is platform-core's refusal.
 */
@Component("eraseLogFolder")
public class EraseLogFolder {
    static final String IN_A_VOLUME = "platform.erase-log.location (SDV_ERASE_LOG_FOLDER) is inside a document volume, or a document volume is inside it.";

    @Autowired
    public EraseLogFolder(@Value("${platform.erase-log.location:}") String location, StorageVolumeRepository volumes, DatabaseSchemaCheck schema) {
        this(location, schema.current() ? volumes.findConfiguredRoots("LOCAL_FILESYSTEM").stream().map(StorageVolumeRepository.ConfiguredRoot::rootPath).toList() : List.of());
    }

    EraseLogFolder(String location, List<String> volumeRoots) {
        if (location == null || location.isBlank()) return;
        Path log = real(Path.of(location.trim()).toAbsolutePath().normalize());
        for (String root : volumeRoots) {
            Path volume = real(Path.of(root).toAbsolutePath().normalize());
            if (log.startsWith(volume) || volume.startsWith(log)) throw new StartupFailure(SdvErrorCode.ERS_002, IN_A_VOLUME);
        }
    }

    /** With links resolved, as far as the path exists. */
    private static Path real(Path path) {
        Path there = path;
        while (there != null && !Files.exists(there)) there = there.getParent();
        if (there == null) return path;
        try {
            return there.toRealPath().resolve(there.relativize(path)).normalize();
        } catch (IOException | SecurityException ex) {
            return path;
        }
    }
}
