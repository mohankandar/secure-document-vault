package com.sdv.platform.web;

import com.documentplatform.licensing.LicenceService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0073: how Vero uses platform-core 1.0: its public types only, one version, and no development key in a release. */
class PlatformCoreUseTest {
    private static final Path BACKEND = Path.of("").toAbsolutePath().getParent();

    private static List<Path> files(String suffix) throws Exception {
        try (var all = Files.walk(BACKEND)) {
            return all.filter(p -> p.toString().endsWith(suffix) && !p.toString().contains("target")).toList();
        }
    }

    @Test
    void onlyPublicTypes() throws Exception {
        Pattern internal = Pattern.compile("com\\.documentplatform\\.[a-z.]*\\binternal\\b");
        List<String> found = new ArrayList<>();
        int files = 0;
        for (Path f : files(".java")) {
            files++;
            if (internal.matcher(Files.readString(f)).find()) found.add(f.getFileName().toString());
        }
        assertTrue(files > 300, "Vero's sources were found: " + files);
        assertEquals(List.of(), found, "a package named internal may change in any release of platform-core");
    }

    @Test
    void oneVersionThroughTheBom() throws Exception {
        for (Path f : files("pom.xml")) {
            var m = Pattern.compile("<groupId>com\\.documentplatform\\.platform</groupId>\\s*<artifactId>([^<]+)</artifactId>\\s*<version>([^<]+)<").matcher(Files.readString(f));
            while (m.find()) {
                assertEquals("platform-bom", m.group(1), f + " names a version of its own");
                assertEquals("${platform.core.version}", m.group(2));
            }
        }
        assertNotNull(LicenceService.class.getResource("LicenceService.class"));
    }

    /** The folder every release is built with. A development key there fails the build (and CI looks into the jars). */
    @Test
    void theReleaseKeysHoldNoDevelopmentKey() throws Exception {
        Path release = BACKEND.resolve("licence-build/release");
        if (Files.isDirectory(release)) {
            try (var all = Files.walk(release)) {
                for (Path f : all.filter(Files::isRegularFile).toList()) {
                    assertFalse(Files.readString(f).toLowerCase(java.util.Locale.ROOT).contains("development"), f + " holds a development key or setting");
                }
            }
        }
        assertTrue(Files.readString(BACKEND.resolve("licence-build/development/META-INF/platform/licence-build.properties")).contains("built-at="));
        for (String setting : List.of("trusted-keys", "trusted-keys-file")) {
            for (Path f : files(".yml")) assertFalse(Pattern.compile("(?m)^\\s{4,}licensing:[\\s\\S]{0,200}" + setting).matcher(Files.readString(f)).find(), f.toString());
        }
    }
}
