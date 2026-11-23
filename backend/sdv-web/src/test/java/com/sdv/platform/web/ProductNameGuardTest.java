package com.sdv.platform.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 0042: the product-name guard. Vero's code, SQL, JSON, YAML, scripts and install guides (test and demo data
 * included) must not carry another product's name: any case, as a whole word. The names are in
 * {@code product-name-guard/names.txt}; a file may hold one only when {@code product-name-guard/exceptions.txt}
 * lists it with a reason. A listed file that no longer holds a name fails too, so the list only shrinks.
 * <p>
 * The names are never written in this class, so the guard does not need an exception for itself.
 */
class ProductNameGuardTest {

    /** Code, SQL, JSON, YAML and scripts: scanned wherever they are. */
    private static final Set<String> CODE_EXTENSIONS = Set.of(
        "java", "kt", "ts", "tsx", "js", "jsx", "mjs", "cjs", "css", "html", "sql", "xml", "json", "yml", "yaml",
        "properties", "ps1", "psm1", "psd1", "sh", "cmd", "bat", "py", "hcl", "env");

    /** Folders that are never scanned: build output, dependencies, runtime data, the IDE, the history. */
    private static final Set<String> SKIPPED_FOLDERS = Set.of(
        ".git", "node_modules", "target", "dist", "build", ".runtime", "test-results", "playwright-report",
        "coverage", ".vite", ".idea", ".vscode", ".claude");

    private static final String RESOURCES = "/product-name-guard/";

    @Test
    void noOtherProductNameOutsideTheListedExceptions() throws IOException {
        Path root = repositoryRoot();
        List<String> names = readNames();
        Map<String, String> exceptions = readExceptions();
        Pattern pattern = namePattern(names);

        Map<String, List<String>> hits = scan(root, pattern);

        List<String> problems = new ArrayList<>();
        hits.forEach((file, lines) -> {
            if (!exceptions.containsKey(file)) {
                problems.add(file + " (not in exceptions.txt): " + String.join("; ", lines));
            }
        });
        exceptions.keySet().stream().filter(file -> !hits.containsKey(file)).forEach(file ->
            problems.add(file + " is listed in exceptions.txt but holds no such name any more: remove it from the list"));

        if (!problems.isEmpty()) {
            fail("Another product's name in Vero (task 0042 product-name guard):\n  " + String.join("\n  ", problems));
        }
    }

    @Test
    void theNamePatternMatchesWholeWordsInAnyCaseOnly() throws IOException {
        Pattern pattern = namePattern(readNames());
        String name = readNames().get(0);
        assertTrue(pattern.matcher(name).find());
        assertTrue(pattern.matcher("a " + name.toUpperCase(Locale.ROOT) + " b").find());
        assertTrue(pattern.matcher("\"" + name.toLowerCase(Locale.ROOT) + "-client\"").find());
        assertTrue(pattern.matcher("sender_" + name.toLowerCase(Locale.ROOT)).find(), "an underscore is a word break");
        assertFalse(pattern.matcher(name + "x").find(), "part of a longer word is not the name");
        assertFalse(pattern.matcher("x" + name).find(), "part of a longer word is not the name");
        assertFalse(pattern.matcher("platform-core").find(), "platform-core is allowed as a library name");
    }

    @Test
    void everyExceptionHasAReason() throws IOException {
        Map<String, String> exceptions = readExceptions();
        exceptions.forEach((file, reason) -> assertFalse(reason.isBlank(), file + " has no reason"));
        assertEquals(exceptions.size(), new TreeSet<>(exceptions.keySet()).size());
    }

    // ------------------------------------------------------------------ scanning

    static Map<String, List<String>> scan(Path root, Pattern pattern) throws IOException {
        Map<String, List<String>> hits = new TreeMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && SKIPPED_FOLDERS.contains(dir.getFileName().toString())
                    ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (attrs.isRegularFile() && inScope(relative)) {
                    List<String> found = matches(file, pattern);
                    if (!found.isEmpty()) {
                        hits.put(relative, found);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return hits;
    }

    /**
     * Code, SQL, JSON, YAML and scripts anywhere except the design and work notes under docs/ (prose and mock-ups,
     * not code); JSON and YAML under docs/ (the API specs) are scanned. Install guides: everything under
     * docs/install/ and the install runbooks under docs/operations/.
     */
    static boolean inScope(String relative) {
        String lower = relative.toLowerCase(Locale.ROOT);
        String fileName = lower.substring(lower.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1);
        if (lower.startsWith("docs/install/")) {
            return true;
        }
        if (lower.startsWith("docs/operations/") && extension.equals("md") && fileName.contains("install")) {
            return true;
        }
        if (lower.startsWith("docs/")) {
            return Set.of("json", "yml", "yaml", "sql").contains(extension)
                && !lower.startsWith("docs/work/") && !lower.startsWith("docs/design/");
        }
        return CODE_EXTENSIONS.contains(extension);
    }

    private static List<String> matches(Path file, Pattern pattern) throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException notUtf8) {
            lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        }
        List<String> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = pattern.matcher(lines.get(i));
            if (m.find()) {
                found.add("line " + (i + 1) + " '" + m.group() + "'");
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ inputs

    /** Whole word: not next to a letter or digit (an underscore or a hyphen is a break). Any case. */
    static Pattern namePattern(List<String> names) {
        String alternatives = String.join("|", names.stream().map(Pattern::quote).toList());
        return Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static List<String> readNames() throws IOException {
        List<String> names = contentLines("names.txt");
        assertFalse(names.isEmpty(), "names.txt lists no names");
        return names;
    }

    /** {@code path | reason}, one per line; the path from the repository root with forward slashes. */
    private static Map<String, String> readExceptions() throws IOException {
        Map<String, String> exceptions = new LinkedHashMap<>();
        for (String line : contentLines("exceptions.txt")) {
            int bar = line.indexOf('|');
            if (bar < 0) {
                fail("exceptions.txt line without '|': " + line);
            }
            String path = line.substring(0, bar).trim();
            String reason = line.substring(bar + 1).trim();
            if (exceptions.put(path, reason) != null) {
                fail("exceptions.txt lists " + path + " twice");
            }
        }
        return exceptions;
    }

    private static List<String> contentLines(String resource) throws IOException {
        try (InputStream in = ProductNameGuardTest.class.getResourceAsStream(RESOURCES + resource)) {
            if (in == null) {
                fail("Missing test resource " + RESOURCES + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
        }
    }

    /** The folder that holds backend/pom.xml and frontend/: found from the test's working folder upwards. */
    static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("backend/pom.xml")) && Files.isDirectory(dir.resolve("frontend"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        fail("Repository root (backend/pom.xml and frontend/) not found above " + Path.of("").toAbsolutePath());
        return null;
    }
}
