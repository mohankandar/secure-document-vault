package com.sdv.platform.setup;

import com.documentplatform.licensing.LicenceBuild;
import com.documentplatform.licensing.TrustedKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0093: the tool behind New-SDV-ReleaseLicenceKey.ps1 and New-SDV-Licence.ps1, with throwaway keys in a temporary
 * folder. A licence signed with the release build's key passes the setup command's own licence check; one signed with
 * another key doesn't.
 */
class ReleaseLicenceKeyTest {
    static final Path TOOL = VeroSetupFilesTest.ROOT.resolve("tools/licence/VeroLicenceTool.java");
    static final Path EXAMPLE = VeroSetupFilesTest.ROOT.resolve("dev/licence/licence-input.example.json");
    @TempDir Path dir;

    record Ran(int exit, String out) { }

    static Ran tool(Object... args) throws Exception {
        // Only platform-core's jars: the tool needs nothing of Vero.
        String classpath = String.join(java.io.File.pathSeparator, jar(LicenceBuild.class), jar(Class.forName("com.documentplatform.errors.Catalogue")));
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, TOOL.toString()));
        for (Object a : args) command.add(a.toString());
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return new Ran(p.waitFor(), out);
    }

    static String jar(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    Path key(String id, Path build) throws Exception {
        Path secret = Files.createFile(dir.resolve(id + ".private-key"));
        Ran made = tool("new-key", secret, dir.resolve(id + ".public.json"), id);
        assertEquals(0, made.exit(), made.out());
        assertFalse(made.out().contains(Files.readString(secret)), "the private key was printed");
        Ran published = tool("publish", dir.resolve(id + ".public.json"), build.resolve("licence-keys.json"), build.resolve("licence-build.properties"));
        assertEquals(0, published.exit(), published.out());
        return secret;
    }

    /** What a release build with these keys trusts: never a development key. */
    static LicenceBuild release(Path keys) throws Exception {
        return LicenceBuild.of(TrustedKeys.parse(Files.readString(keys)), false, Instant.parse("2026-11-24T00:00:00Z"));
    }

    /** Setup's plan for this licence: with a licence it accepts it goes on, to the sign-in file of another customer. */
    static VeroSetupFilesTest.Run plan(LicenceBuild build, Path licence) {
        return VeroSetupFilesTest.setup(build, Map.of(), "plan", "--licence", licence.toString(),
                "--signin", VeroSetupFilesTest.DEV.resolve("signin-vero-demo.json").toString(), "--product", "SDV",
                "--product-roles", VeroSetupFilesTest.PRODUCT_ROLES.toString());
    }

    @Test
    void aLicenceOfTheReleaseKeyIsAcceptedAndAnotherKeysIsNot() throws Exception {
        Path build = dir.resolve("build"), other = dir.resolve("other");
        Path a = key("throwaway-a", build), b = key("throwaway-b", other);
        Path good = dir.resolve("a.json"), foreign = dir.resolve("b.json");
        Ran signed = tool("sign", a, "throwaway-a", build.resolve("licence-keys.json"), EXAMPLE, good);
        assertEquals(0, signed.exit(), signed.out());
        assertFalse(signed.out().contains(Files.readString(a)));
        assertEquals(0, tool("sign", b, "throwaway-b", other.resolve("licence-keys.json"), EXAMPLE, foreign).exit());

        LicenceBuild release = release(build.resolve("licence-keys.json"));
        VeroSetupFilesTest.Run accepted = plan(release, good);
        assertTrue(accepted.err().contains("SETUP_WRONG_TENANT"), accepted.err());
        assertTrue(plan(release, foreign).err().contains("LICENCE_UNKNOWN_KEY_ID"));

        // The other key's signature under the trusted name.
        Path forged = dir.resolve("forged.json"), renamedBuild = dir.resolve("renamed");
        Path c = Files.createFile(dir.resolve("c.private-key"));
        assertEquals(0, tool("new-key", c, dir.resolve("c.public.json"), "throwaway-a").exit());
        assertEquals(0, tool("publish", dir.resolve("c.public.json"), renamedBuild.resolve("licence-keys.json"), renamedBuild.resolve("licence-build.properties")).exit());
        assertEquals(0, tool("sign", c, "throwaway-a", renamedBuild.resolve("licence-keys.json"), EXAMPLE, forged).exit());
        assertTrue(plan(release, forged).err().contains("LICENCE_FORGED"));
        // And the tool itself doesn't sign with it for this build.
        assertEquals(2, tool("sign", c, "throwaway-a", build.resolve("licence-keys.json"), EXAMPLE, dir.resolve("no.json")).exit());
        assertFalse(Files.exists(dir.resolve("no.json")));

        // A changed licence.
        Path changed = Files.writeString(dir.resolve("changed.json"), Files.readString(good).replace("Example Customer", "Another Customer"));
        assertTrue(plan(release, changed).err().contains("LICENCE_TAMPERED"));
        // No price, and the product only.
        assertFalse(Files.readString(good).toLowerCase(java.util.Locale.ROOT).contains("price"));
    }

    @Test
    void aSecondKeyStandsBesideTheFirstAndNeitherIsReplaced() throws Exception {
        Path build = dir.resolve("build");
        key("throwaway-a", build);
        key("throwaway-b", build);
        String both = Files.readString(build.resolve("licence-keys.json"));
        assertEquals(java.util.Set.of("throwaway-a", "throwaway-b"), TrustedKeys.parse(both).keyIds());
        assertEquals("built-at=${maven.build.timestamp}", Files.readString(build.resolve("licence-build.properties")).strip());
        assertEquals(2, tool("publish", dir.resolve("throwaway-a.public.json"), build.resolve("licence-keys.json"), build.resolve("licence-build.properties")).exit());
        // A private key file that isn't empty is never written.
        assertEquals(2, tool("new-key", dir.resolve("throwaway-a.private-key"), dir.resolve("again.json"), "throwaway-a").exit());
        assertEquals(both, Files.readString(build.resolve("licence-keys.json")));
    }

    @Test
    void aPriceOrAnUnknownFieldRefusesTheInput() throws Exception {
        Path build = dir.resolve("build");
        Path a = key("throwaway-a", build);
        Path input = Files.writeString(dir.resolve("input.json"), Files.readString(EXAMPLE).replace("\"version\": 1", "\"price\": 5, \"version\": 1"));
        assertEquals(2, tool("sign", a, "throwaway-a", build.resolve("licence-keys.json"), input, dir.resolve("out.json")).exit());
        assertFalse(Files.exists(dir.resolve("out.json")));
    }

    /** What Test-SDV-ReleaseLicenceKey.ps1 made with the owner's two scripts. */
    @Test
    @EnabledIfSystemProperty(named = "vero.licence.check", matches = ".+")
    void theOwnersScripts() throws Exception {
        Path made = Path.of(System.getProperty("vero.licence.check"));
        LicenceBuild release = release(made.resolve("build/licence-keys.json"));
        assertTrue(plan(release, made.resolve("a.licence.json")).err().contains("SETUP_WRONG_TENANT"));
        assertTrue(plan(release, made.resolve("b.licence.json")).err().contains("LICENCE_UNKNOWN_KEY_ID"));
    }
}
