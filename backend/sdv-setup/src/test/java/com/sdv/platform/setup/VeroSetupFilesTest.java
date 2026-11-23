package com.sdv.platform.setup;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.setup.ProductRoles;
import com.documentplatform.setup.SetupCommand;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0041: Vero's setup inputs, checked by platform-core's own setup command. Without database settings the command
 * reads and checks every input file first (licence signature, sign-in file, product roles, role mapping, client
 * bindings) and only then stops at the missing database settings, so reaching that refusal proves the files are valid.
 */
class VeroSetupFilesTest {
    static final Path ROOT = Path.of("..", "..");
    static final Path DEV = ROOT.resolve("dev/setup");
    static final Path PRODUCT_ROLES = ROOT.resolve("backend/sdv-security/src/main/resources/setup/product-roles.json");

    record Run(int exit, String out, String err) { }

    static Run setup(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Path log;
        try {
            log = Files.createTempFile("vero-setup", ".log");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        List<String> all = new java.util.ArrayList<>(List.of(args));
        all.addAll(List.of("--run-log", log.toString()));
        int exit = new SetupCommand(Map.of(), Clock.systemUTC()).run(all,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    static String[] demoPlan(String keys) {
        return new String[]{"plan", "--licence", DEV.resolve("licence-vero-demo.json").toString(),
                "--signin", DEV.resolve("signin-vero-demo.json").toString(), "--product", "SDV",
                "--keys", keys, "--product-roles", PRODUCT_ROLES.toString(), "--no-customer-keys"};
    }

    @Test
    void theDemoFilesPassEveryCheckUpToTheDatabase() {
        Run run = setup(demoPlan(DEV.resolve("development-trusted-keys.json").toString()));
        assertTrue(run.out().startsWith("Setup plan for product SDV, customer TEN-VERO-DEMO-0001, licence LIC-VERO-DEMO-0001 v1 (NON_PRODUCTION)"), run.out() + run.err());
        assertEquals(2, run.exit(), run.err());
        assertTrue(run.err().startsWith("SETUP_DB_SETTINGS_MISSING") || run.err().contains("SETUP_DB_SETTINGS_MISSING"), run.err());
    }

    @Test
    void withoutTheDevelopmentKeyTheDemoLicenceIsRefused() throws Exception {
        Path empty = Files.createTempFile("keys", ".json");
        Files.writeString(empty, "{\"format\": \"platform-licence-keys/1\", \"keys\": []}");
        Run run = setup(demoPlan(empty.toString()));
        assertEquals(2, run.exit());
        assertTrue(run.err().contains("SETUP_KEYS_INVALID") || run.err().contains("SETUP_LICENCE_REFUSED"), run.err());
    }

    @Test
    void theShippedProductRolesFileIsVerosAndKeysAreOptional() throws Exception {
        ProductRoles roles = ProductRoles.parse(Files.readString(PRODUCT_ROLES), "SDV");
        assertEquals("SDV", roles.productCode());
        assertFalse(roles.customerKeysRequired(), "customerKeys optional until Vero uses platform-crypto (owner answer 12)");
        assertTrue(roles.isPlatformRole("PLATFORM_OPERATIONS_ADMIN"));
    }

    @Test
    void theDevelopmentKeyIsNeverInsideAShippedJar() {
        // The setup tool's own classpath holds no trusted-keys file; the app's resources hold none either (C7).
        assertNull(getClass().getClassLoader().getResource("development-trusted-keys.json"));
        assertNull(getClass().getClassLoader().getResource("trusted-keys.json"));
        assertFalse(Files.exists(ROOT.resolve("backend/sdv-web/src/main/resources/development-trusted-keys.json")));
        assertFalse(Files.exists(ROOT.resolve("backend/sdv-web/src/main/resources/trusted-keys.json")));
    }

    @Test
    void theDemoEntityIdsAreTheOnesDerivedFromTheSlots() {
        // The frontend, the scripts and the screen tour use these constants.
        assertEquals(UUID.fromString(DemoEntities.DEMO), EntityUuid.fromSlotId("ENT-VERO-DEMO-0001"));
        assertEquals(UUID.fromString(DemoEntities.ISOLATION), EntityUuid.fromSlotId("ENT-VERO-DEMO-0002"));
    }

    /** The demo entity UUIDs as written in the frontend and the scripts. */
    static final class DemoEntities {
        static final String DEMO = "bd960ee9-9d54-5053-9958-92aaa54893a6";
        static final String ISOLATION = "9b233c00-fe6e-582c-a9de-c1fa46f87254";
    }
}
