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
            String filled = PlatformError.fill(code, Map.of("count", 3, "host", "idp.test", "reason", "HTTP 503", "warning", 1, "stopped", 2, "active", 3, "state", "DOWN"));
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
