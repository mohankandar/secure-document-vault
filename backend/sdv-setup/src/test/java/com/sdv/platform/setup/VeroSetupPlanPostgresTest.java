package com.sdv.platform.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0057 (LV-16 item 3): platform-level grants in Vero's role mapping, checked by platform-core's setup command
 * against the scratch database of the schema check (identity schema V5). Only {@code plan}: nothing is written.
 * PostgreSQL only, like the other {@code *PostgresTest}s (CI and step 4f of {@code Test-SDV-SchemaUpgrade.ps1}).
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class VeroSetupPlanPostgresTest {

    /** The setup command's own database settings, from the test's scratch database settings. */
    static Map<String, String> database() {
        return Map.of("PLATFORM_SETUP_DB_URL", System.getenv("SDV_IT_JDBC_URL"),
                "PLATFORM_SETUP_DB_USER", System.getenv("SDV_IT_JDBC_USER"),
                "PLATFORM_SETUP_DB_PASSWORD", System.getenv("SDV_IT_JDBC_PASSWORD"));
    }

    @Test
    void theDemoPlanGrantsThePlatformOperationsAdminInEveryEntity() throws Exception {
        VeroSetupFilesTest.Run run = VeroSetupFilesTest.setup(database(), VeroSetupFilesTest.withoutKeyService(VeroSetupFilesTest.demoPlanWithMapping(null)));
        assertEquals(0, run.exit(), run.out() + run.err());
        assertTrue(run.out().contains("platform grant SDV (every entity): group \"PlatformOperations\" -> PLATFORM_OPERATIONS_ADMIN"),
                run.out());
        assertFalse(run.out().contains("ENT-VERO-DEMO-0001: group \"PlatformOperations\""), run.out());
    }

    @Test
    void anEntityRoleOnAPlatformLevelRowRefusesTheFile() throws Exception {
        VeroSetupFilesTest.Run run = VeroSetupFilesTest.setup(database(),
                VeroSetupFilesTest.withoutKeyService(VeroSetupFilesTest.demoPlanWithMapping("*,,BusinessAdmins,BUSINESS_ADMIN")));
        assertEquals(2, run.exit(), run.out() + run.err());
        assertTrue(run.err().contains("SETUP_ENTITY_ROLE_PLATFORM_WIDE"), run.err());
    }

    @Test
    void aPlatformRoleOnAnEntityRowRefusesTheFile() throws Exception {
        VeroSetupFilesTest.Run run = VeroSetupFilesTest.setup(database(),
                VeroSetupFilesTest.withoutKeyService(VeroSetupFilesTest.demoPlanWithMapping("ENT-VERO-DEMO-0001,,PlatformOperations,PLATFORM_OPERATIONS_ADMIN")));
        assertEquals(2, run.exit(), run.out() + run.err());
        assertTrue(run.err().contains("SETUP_PLATFORM_ROLE_ON_ENTITY"), run.err());
    }
}
