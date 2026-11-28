package com.sdv.platform.web.monitoring;

import com.documentplatform.errors.PlatformCode;
import com.documentplatform.errors.PlatformError;
import com.documentplatform.errors.ReferenceId;
import com.documentplatform.monitoring.CheckState;
import com.documentplatform.monitoring.DependencyStatus;
import com.documentplatform.monitoring.Verdict;
import com.documentplatform.monitoring.spring.DiagnosticBundles;
import com.documentplatform.monitoring.spring.MonitoringAutoConfiguration;
import com.documentplatform.monitoring.spring.RequestReferenceFilter;
import com.sdv.platform.application.operations.BusyAnswers;
import com.sdv.platform.application.port.KeyServiceHealthProbe;
import com.sdv.platform.web.config.CorrelationIdFilter;
import com.sdv.platform.web.schema.SchemaVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.application.ReadinessStateHealthIndicator;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0074: Vero's checks, the request's reference, and what the diagnostic bundle holds. */
class VeroMonitoringTest {

    private static void is(Verdict verdict, CheckState state, String code) {
        assertEquals(state, verdict.state());
        if (code != null) assertEquals(code, verdict.code().code());
    }

    @Test
    void theChecksSayWhatIsWrong() {
        is(VeroMonitoringConfiguration.schema(new SchemaVersion.Status(SchemaVersion.State.CURRENT, List.of(), List.of(), 1)), CheckState.WORKING, null);
        is(VeroMonitoringConfiguration.schema(new SchemaVersion.Status(SchemaVersion.State.OLDER, List.of(), List.of(), 1)), CheckState.DOWN, "SDV-SYS-001");
        is(VeroMonitoringConfiguration.schema(new SchemaVersion.Status(SchemaVersion.State.NEWER, List.of(), List.of(), 1)), CheckState.DOWN, "SDV-SYS-002");
        is(VeroMonitoringConfiguration.schema(SchemaVersion.Status.unreachable()), CheckState.DOWN, "SDV-SYS-014");
        is(VeroMonitoringConfiguration.busy(BusyAnswers.Count.NONE), CheckState.WORKING, null);
        is(VeroMonitoringConfiguration.busy(new BusyAnswers.Count(3, 9, Instant.now(), 5)), CheckState.HAVING_PROBLEMS, "SDV-SYS-018");
        is(VeroMonitoringConfiguration.attempts(9), CheckState.WORKING, null);
        is(VeroMonitoringConfiguration.attempts(10), CheckState.HAVING_PROBLEMS, "SDV-SYS-020");
        is(VeroMonitoringConfiguration.keys(KeyServiceHealthProbe.Status.UP), CheckState.WORKING, null);
        is(VeroMonitoringConfiguration.keys(KeyServiceHealthProbe.Status.NOT_CONFIGURED), CheckState.HAVING_PROBLEMS, "SDV-KEY-001");
        is(VeroMonitoringConfiguration.keys(KeyServiceHealthProbe.Status.DOWN), CheckState.DOWN, "SDV-KEY-001");
        is(VeroMonitoringConfiguration.storage(List.of()), CheckState.WORKING, null);
    }

    @Test
    void everyCodeHasItsTextsAndFillsItsDetails() {
        for (PlatformCode code : VeroCheckCode.values()) {
            for (String text : new String[]{code.cause(), code.fix(), code.userMessage(), code.supportNote(), code.family()}) assertFalse(text.isBlank());
            String filled = PlatformError.fill(code, new java.util.HashMap<String, Object>(Map.of("count", 3, "host", "idp.test", "reason", "HTTP 503", "warning", 1, "stopped", 2, "active", 3, "state", "DOWN",
                    "seq", 4L, "code", "X")) {{ put("kind", "document"); put("record", "40000000-0000-0000-0000-000000000001"); }});
            assertFalse(filled.contains("{"), filled);
        }
    }

    @Test
    void verosOwnChecksNeverStopTheStart() {
        assertFalse(new VeroCheck("x", "y", Duration.ofSeconds(60), Verdict::working).requiredAtStartup());
    }

    @Test
    void theReferenceIsPlatformCoresAndACallerNeverChoosesIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
        request.addHeader("X-Correlation-Id", "mine-1");
        request.addHeader("X-Request-Id", "R-000000000000");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seen = new String[2];
        new RequestReferenceFilter().doFilter(request, response, (q, r) -> new CorrelationIdFilter().doFilter(q, r, (q2, r2) -> {
            seen[0] = org.slf4j.MDC.get("correlationId");
            seen[1] = org.slf4j.MDC.get("ref");
        }));
        assertTrue(ReferenceId.isReference(seen[0]), seen[0]);
        assertEquals(seen[0], seen[1], "one reference, in both places of the log");
        assertEquals(seen[0], response.getHeader("X-Correlation-Id"));
        assertNotEquals("mine-1", seen[0]);
        assertNotEquals("R-000000000000", seen[0]);
        assertNull(org.slf4j.MDC.get("correlationId"));
    }

    @Test
    void readinessStaysSpringsOwnAndTheListIsThere() {
        runner().run(context -> {
            assertInstanceOf(ReadinessStateHealthIndicator.class, context.getBean("readinessStateHealthIndicator"));
            assertNotNull(context.getBean("dependenciesHealthIndicator", HealthIndicator.class));
            assertFalse(context.getBean(DependencyStatus.class).runAll().isEmpty());
        });
    }

    /** Everything a customer wouldn't want to send away, put where the bundle looks; none of it may be in the zip. */
    @Test
    void theBundleHoldsNoSecretNameTitleOrValue() {
        Map<String, Object> settings = Map.ofEntries(
                Map.entry("spring.datasource.password", "SECRET-DB"), Map.entry("spring.datasource.url", "jdbc:postgresql://SECRET-HOST/db"),
                Map.entry("server.ssl.key-store-password", "SECRET-SSL"), Map.entry("platform.licensing.token", "SECRET-TOKEN"),
                Map.entry("sdv.auth.jwt.issuer", "https://SECRET-ISSUER"), Map.entry("sdv.vault.token", "SECRET-VAULT"),
                Map.entry("management.demo", "Bearer SECRET.eyJhbGciOiJSUzI1NiJ9.abcdefghij"), Map.entry("server.port", "8280"));
        runner().withInitializer(c -> c.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("commandLineArgs", settings))).run(context -> {
            context.getBean(DependencyStatus.class).runAll();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            context.getBean(DiagnosticBundles.class).write(out);
            StringBuilder all = new StringBuilder();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
                for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) all.append(e.getName()).append('\n').append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
            String text = all.toString();
            assertTrue(text.contains("configuration.properties") && text.contains("checks.txt") && text.contains("manifest.txt"), text);
            assertTrue(text.contains("server.port"), "the settings part is there");
            assertFalse(text.contains("SECRET"), text);
            assertFalse(text.contains("Dana Person") || text.contains("Deed of trust"), "a check's own words never carry content");
        });
    }

    /**
     * Task 0102 (VSEC-207): the key-service login in an imported file with a neutral name. platform-core's bundle takes
     * it (P-2); Vero's filter takes every key-service setting out before the bundle leaves.
     */
    @Test
    void aKeyServiceLoginInANeutrallyNamedFileNeverLeavesInTheBundle() {
        String roleId = "4f6c2b1e-9a7d-4c3e-8b2f-1d0e9c8b7a65";
        Map<String, Object> login = Map.of("platform.crypto.vault.role-id", roleId, "platform.crypto.vault.address", "https://keys.internal.example:8200",
                "platform.crypto.vault.transit-mount", "vero-transit", "spring.cloud.vault.app-role.role-id", roleId,
                "custom.service.roleId", roleId, "server.port", "8280");
        runner().withInitializer(c -> c.getEnvironment().getPropertySources().addFirst(new org.springframework.boot.env.OriginTrackedMapPropertySource(
                "Config resource 'file [D:\\vero\\config\\login.properties]' via location 'optional:file:D:/vero/config/login.properties'", login))).run(context -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            context.getBean(DiagnosticBundles.class).write(out);
            assertTrue(text(out.toByteArray()).contains(roleId), "platform-core's bundle takes it (P-2); this test shows the filter is needed");
            byte[] bundle = com.sdv.platform.web.operations.KeyServiceSettingsFilter.filter(out.toByteArray());
            String filtered = text(bundle);
            assertFalse(filtered.contains(roleId), filtered);
            assertFalse(filtered.contains("platform.crypto.vault") || filtered.contains("spring.cloud.vault.app") || filtered.contains("keys.internal")
                    || filtered.contains("vero-transit"), filtered);
            assertTrue(filtered.contains("server.port=8280"), "every other setting stays");
            assertTrue(filtered.contains("checks.txt") && filtered.contains("manifest.txt"), "every other part stays");
            // The manifest's checksum is the filtered entry's, so the bundle still checks.
            Map<String, byte[]> entries = new java.util.HashMap<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bundle))) {
                for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) entries.put(e.getName(), zip.readAllBytes());
            }
            String sum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(entries.get("configuration.properties")));
            assertTrue(new String(entries.get("manifest.txt"), StandardCharsets.UTF_8).contains("sha256 " + sum + "  configuration.properties"));
        });
    }

    private static String text(byte[] bundle) throws java.io.IOException {
        StringBuilder all = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bundle))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) all.append(e.getName()).append('\n').append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        return all.toString();
    }

    @Test
    void anUnreadableBundleIsRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> com.sdv.platform.web.operations.KeyServiceSettingsFilter.filter("not a zip".getBytes(StandardCharsets.UTF_8)));
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(MonitoringAutoConfiguration.class))
                .withPropertyValues("platform.monitoring.startup-checks=false")
                .withBean("availability", ApplicationAvailabilityBean.class)
                .withBean("readinessStateHealthIndicator", HealthIndicator.class, () -> new ReadinessStateHealthIndicator(new ApplicationAvailabilityBean()))
                .withBean("storage", com.documentplatform.monitoring.DependencyCheck.class, () -> new VeroCheck("storage", "folders", Duration.ofSeconds(60), () -> {
                    // A check that fails with text in its exception: only the class name may be shown.
                    throw new IllegalStateException("Deed of trust of Dana Person, SECRET-VALUE");
                }));
    }
}
