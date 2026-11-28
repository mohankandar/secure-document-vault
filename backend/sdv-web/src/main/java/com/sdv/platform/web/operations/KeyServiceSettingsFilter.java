package com.sdv.platform.web.operations;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Task 0102 (VSEC-207): the diagnostic bundle never carries a key-service setting. platform-core's bundle takes every
 * {@code platform.*} setting from a config file it doesn't recognise as secret by its name, so an install whose login
 * file has a neutral name (for example {@code D:\vero\config\login.properties}) would send the role ID to support. Vero
 * takes out of the bundle's {@value #CONFIGURATION} every setting of the key service ({@code platform.crypto.*},
 * {@code spring.cloud.vault.*}) and every setting named like a role ID or a secret ID, before the bundle leaves Vero.
 * Every other entry is copied as it is; the manifest's checksum of the changed entry is written again, so the bundle
 * still checks. A bundle that can't be read is refused (fail closed); nothing is sent then.
 */
public final class KeyServiceSettingsFilter {
    static final String CONFIGURATION = "configuration.properties";
    static final String MANIFEST = "manifest.txt";
    static final String NOTE = "# Key-service settings left out by Vero (the key service's address, mounts, role and secret IDs).";
    private static final Pattern DENIED = Pattern.compile(
            "^(platform\\.crypto\\.|spring\\.cloud\\.vault\\.).*|.*(role|secret)[-_.]?id.*");

    private KeyServiceSettingsFilter() {
    }

    /** Whether a setting's name is the key service's. */
    static boolean denied(String name) {
        return DENIED.matcher(name.trim().toLowerCase(Locale.ROOT)).matches();
    }

    /** The bundle without key-service settings. */
    public static byte[] filter(byte[] bundle) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bundle.length);
        int entries = 0;
        String newSum = null;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bundle));
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                byte[] content = in.readAllBytes();
                if (CONFIGURATION.equals(e.getName())) {
                    String oldSum = sha256(content);
                    content = settings(content);
                    newSum = oldSum + " " + sha256(content);
                } else if (MANIFEST.equals(e.getName()) && newSum != null) {
                    String[] sums = newSum.split(" ");
                    content = new String(content, StandardCharsets.UTF_8).replace("sha256 " + sums[0] + "  " + CONFIGURATION,
                            "sha256 " + sums[1] + "  " + CONFIGURATION).getBytes(StandardCharsets.UTF_8);
                }
                ZipEntry copy = new ZipEntry(e.getName());
                if (e.getLastModifiedTime() != null) copy.setLastModifiedTime(e.getLastModifiedTime());
                zip.putNextEntry(copy);
                zip.write(content);
                zip.closeEntry();
                entries++;
            }
        }
        if (entries == 0) throw new IOException("The diagnostic bundle could not be read.");
        return out.toByteArray();
    }

    private static byte[] settings(byte[] content) {
        StringBuilder kept = new StringBuilder();
        boolean left = false;
        for (String line : new String(content, StandardCharsets.UTF_8).split("\n", -1)) {
            String body = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            int split = firstSeparator(body);
            if (!body.startsWith("#") && split > 0 && denied(body.substring(0, split))) {
                left = true;
                continue;
            }
            kept.append(line).append('\n');
        }
        if (kept.length() > 0) kept.setLength(kept.length() - 1);
        String text = left ? NOTE + "\n" + kept : kept.toString();
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static int firstSeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '=' || c == ':') return i;
        }
        return -1;
    }
}
