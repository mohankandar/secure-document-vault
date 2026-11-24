package com.sdv.platform.setup;

import com.documentplatform.identity.EntityUuid;
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
 * checks the licence signature, the sign-in file and the product roles, then stops at the missing database settings.
 * Task 0057: with platform-core 0.6.1 the role mapping's rows (an unknown role, a platform role on an entity row) are
 * checked only once the database is reached; {@link VeroSetupPlanPostgresTest} runs those checks on PostgreSQL.
 */
class VeroSetupFilesTest {
    static final Path ROOT = Path.of("..", "..");
    static final Path DEV = ROOT.resolve("dev/setup");
    static final Path PRODUCT_ROLES = ROOT.resolve("backend/sdv-security/src/main/resources/setup/product-roles.json");

    record Run(int exit, String out, String err) { }

    static Run setup(String... args) {
        return setup(Map.of(), args);
    }

    /** {@code env}: the setup command's environment (database settings), never the process's own. */
    static Run setup(Map<String, String> env, String... args) {
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
        int exit = new SetupCommand(env, Clock.systemUTC()).run(all,
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
        // Task 0057: platform-core 0.6.0 made its ProductRoles reader package-private. Setup itself reads the file
        // (theDemoFilesPassEveryCheckUpToTheDatabase), so only Vero's own promises are checked here.
        String roles = Files.readString(PRODUCT_ROLES).replaceAll("\\s+", "");
        assertTrue(roles.contains("\"productCode\":\"SDV\""), roles);
        assertTrue(roles.contains("\"customerKeys\":\"optional\""), "customerKeys optional until Vero uses platform-crypto (owner answer 12)");
        assertTrue(roles.contains("\"platformRoles\":[\"PLATFORM_OPERATIONS_ADMIN\"]"), roles);
    }

    /** Task 0057 (LV-16 item 3): the demo role mapping grants the Platform Operations Admin platform-wide. */
    @Test
    void theDemoRoleMappingHasAPlatformLevelGrant() throws Exception {
        List<String> rows = Files.readAllLines(DEV.resolve("role-mapping-SDV.csv"));
        assertTrue(rows.contains("*,,PlatformOperations,PLATFORM_OPERATIONS_ADMIN"), String.join("\n", rows));
        assertTrue(rows.stream().filter(r -> r.startsWith("ENT-")).noneMatch(r -> r.endsWith(",PLATFORM_OPERATIONS_ADMIN")),
                "a platform role is never granted on an entity row");
    }

    /** The demo plan, but with a copy of the demo files whose role mapping has one more row (none: {@code null}). */
    static String[] demoPlanWithMapping(String extraRow) throws Exception {
        Path dir = Files.createTempDirectory("vero-setup-mapping");
        Files.copy(DEV.resolve("signin-vero-demo.json"), dir.resolve("signin-vero-demo.json"));
        Files.copy(DEV.resolve("client-bindings-SDV.csv"), dir.resolve("client-bindings-SDV.csv"));
        Files.writeString(dir.resolve("role-mapping-SDV.csv"),
                Files.readString(DEV.resolve("role-mapping-SDV.csv")) + (extraRow == null ? "" : extraRow + "\r\n"));
        String[] plan = demoPlan(DEV.resolve("development-trusted-keys.json").toString());
        plan[4] = dir.resolve("signin-vero-demo.json").toString();
        return plan;
    }

    /** Task 0057: the test licences are signed with the JDK over the bytes platform-core's verifier checks. */
    @Test
    void aLicenceSignedForTheTestsPassesPlatformCoresVerifier() {
        var key = DevelopmentLicences.newKey();
        var allowance = List.of(new com.documentplatform.licensing.Allowance("SDV",
                com.documentplatform.licensing.AllowanceType.PAGES_PER_MONTH, 10));
        var licence = new com.documentplatform.licensing.Licence("LIC-SIGN-TEST-0001", 1, "Signing test", "TEN-SIGN-TEST-0001",
                List.of("SDV"), List.of(new com.documentplatform.licensing.EntitySlot("ENT-SIGN-TEST-0001", allowance)),
                com.documentplatform.licensing.LicenceType.PERPETUAL, com.documentplatform.licensing.LicenceKind.NON_PRODUCTION,
                java.time.LocalDate.parse("2026-01-01"), null, java.time.LocalDate.parse("2036-12-31"),
                com.documentplatform.licensing.Deployment.ON_PREM, "sign-test-1", java.time.Instant.parse("2026-11-23T00:00:00Z"));
        var keys = com.documentplatform.licensing.TrustedKeys.of(Map.of("sign-test-1", key.getPublic()), java.util.Set.of("sign-test-1"));
        var verified = new com.documentplatform.licensing.LicenceVerifier(keys)
                .verify(DevelopmentLicences.sign(licence, key.getPrivate()), "TEN-SIGN-TEST-0001");
        assertEquals(licence, verified);
        assertTrue(keys.isDevelopmentKey("sign-test-1"));
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
