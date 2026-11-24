package com.sdv.platform.web.baseline;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.info.InfoContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.info.InfoEndpointAutoConfiguration;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0060 (plan 0059, 1.6): {@code /actuator/info} is open without a sign-in, so what it says is pinned: nothing.
 * Later Spring Boot versions can add process details (the working directory included).
 */
class InfoBaselineTest {
    @Test
    void infoSaysNothing() throws Exception {
        List<String> settings = new ArrayList<>();
        for (var source : new YamlPropertySourceLoader().load("vero", new ClassPathResource("application.yml"))) {
            for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                if (name.startsWith("management.") || name.startsWith("spring.application")) settings.add(name + "=" + source.getProperty(name));
            }
        }
        assertTrue(settings.contains("management.endpoints.web.exposure.include=health,info"), settings.toString());
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(InfoContributorAutoConfiguration.class, InfoEndpointAutoConfiguration.class))
                .withPropertyValues(settings.toArray(String[]::new))
                .run(context -> assertEquals(Map.of(), context.getBean(InfoEndpoint.class).info()));
    }

    /** Files that would fill the answer. */
    @Test
    void noBuildOrGitFileIsShipped() {
        assertNull(getClass().getResource("/META-INF/build-info.properties"));
        assertNull(getClass().getResource("/git.properties"));
    }
}
