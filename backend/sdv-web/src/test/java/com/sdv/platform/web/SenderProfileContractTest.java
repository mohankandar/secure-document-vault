package com.sdv.platform.web;

import com.documentplatform.integration.profile.ProfileValidator;
import com.documentplatform.integration.testkit.ProfileContract;
import com.documentplatform.integration.testkit.openapi.OpenApiSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0041 (board LV-2): Vero's sender profile ({@code docs/api/sdv-ingestion-v1.profile.json}, format
 * {@code platform-integration-profile/1} from platform-core v0.2.0) passes platform-core's own validator and fits Vero's
 * published contract, checked with platform-core's contract kit. {@link SenderProfileServerContractTest} runs the same
 * kit against a running Vero (PostgreSQL only). Test scope only: no platform-integration on the app's classpath.
 */
class SenderProfileContractTest {
    // Maven runs tests from the module folder (backend/sdv-web).
    static final Path PROFILE = Path.of("..", "..", "docs", "api", "sdv-ingestion-v1.profile.json");
    static final Path SPEC = Path.of("..", "..", "docs", "api", "sdv-ingestion-v1.openapi.json");

    static ProfileValidator.Result validated() throws Exception {
        return ProfileValidator.validate(Files.readString(PROFILE, StandardCharsets.UTF_8));
    }

    @Test
    void theProfilePassesTheFormatsEveryCheck() throws Exception {
        ProfileValidator.Result result = validated();
        assertEquals(List.of(), result.problems(), "platform-integration-profile/1 problems");
        assertNotNull(result.profile());
    }

    @Test
    void theProfileFitsThePublishedContract() throws Exception {
        OpenApiSpec spec = OpenApiSpec.parse(Files.readString(SPEC, StandardCharsets.UTF_8));
        List<ProfileContract.Problem> problems = ProfileContract.checkAgainstSpec(validated().profile(), spec);
        assertEquals(List.of(), problems, "the profile against sdv-ingestion-v1.openapi.json");
    }

    @Test
    void theProfileIsForThisContractVersionAndItsReleaseTag() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String specVersion = json.readTree(SPEC.toFile()).path("info").path("version").asText();
        var profile = json.readTree(PROFILE.toFile());
        assertEquals(specVersion, profile.path("api").path("version").asText());
        assertEquals(json.readTree(SPEC.toFile()).path("info").path("title").asText(), profile.path("api").path("title").asText());
        // Setup checks that the release tag named for a profile ends with its version: sdv-ingestion-v<version>.
        assertEquals(specVersion, profile.path("version").asText());
        assertTrue(("sdv-ingestion-v" + specVersion).endsWith(profile.path("version").asText()));
    }

    @Test
    void everyCodeListIsAlsoTheKitsList() throws Exception {
        var spec = new ObjectMapper().readTree(SPEC.toFile());
        List<String> mismatches = new java.util.ArrayList<>();
        spec.findParents("x-sdv-codes").forEach(n -> {
            if (!n.path("x-sdv-codes").equals(n.path("x-error-codes"))) mismatches.add(n.path("description").asText());
        });
        assertEquals(List.of(), mismatches, "x-error-codes must be the same list as x-sdv-codes");
    }
}
