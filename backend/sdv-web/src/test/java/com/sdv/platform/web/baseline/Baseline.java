package com.sdv.platform.web.baseline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 0060: what Vero does today, kept as text under src/test/resources/baseline. A difference is a change of
 * behaviour or of stored data and needs a decision; a file is never updated only to make a test pass. The text seen is
 * written to target/baseline so that a reviewer can compare.
 */
public final class Baseline {
    private Baseline() { }

    public static void matches(String name, CharSequence seen) {
        try {
            Path out = Path.of("target", "baseline", name + ".txt");
            Files.createDirectories(out.getParent());
            Files.writeString(out, seen);
            try (InputStream in = Baseline.class.getResourceAsStream("/baseline/" + name + ".txt")) {
                String kept = in == null ? "(no baseline file " + name + ".txt)" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(kept.replace("\r\n", "\n").strip(), seen.toString().replace("\r\n", "\n").strip(), name);
            }
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
