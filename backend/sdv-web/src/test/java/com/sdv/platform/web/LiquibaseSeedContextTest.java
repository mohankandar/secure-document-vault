package com.sdv.platform.web;

import liquibase.Contexts;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.changelog.filter.ContextChangeSetFilter;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves which changesets a start would run, using the same pieces the app uses at startup: Spring resolves
 * spring.liquibase.contexts from application.yml (plus application-local.yml for the local profile), and
 * Liquibase's own parser and context filter decide which changesets of the real master changelog run.
 * A start without the local profile must not run Liquibase at all (task 0013: the schema step does that), and
 * the production context must never select the local seed (002), which inserts the demo tenants.
 */
class LiquibaseSeedContextTest {
    private static final String MASTER = "db/changelog/db.changelog-master.yaml";
    private static final String LOCAL_SEED = "002-local-foundation-seed";

    private static String liquibaseContexts(boolean localProfile) throws Exception {
        return property(localProfile, "spring.liquibase.contexts");
    }

    private static String property(boolean localProfile, String name) throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        // Without environment overrides, as on a fresh install.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        if (localProfile) {
            for (PropertySource<?> source : loader.load("local", new ClassPathResource("application-local.yml"))) {
                env.getPropertySources().addLast(source);
            }
        }
        for (PropertySource<?> source : loader.load("base", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        return env.getProperty(name);
    }

    @Test
    void aStartWithoutTheLocalProfileDoesNotRunLiquibase() throws Exception {
        assertEquals("false", property(false, "spring.liquibase.enabled"));
    }

    @Test
    void theLocalProfileDoesNotMigrateAtStartupEither() throws Exception {
        // Task 0041 (owner answer 10): local runs the schema step too.
        assertEquals("false", property(true, "spring.liquibase.enabled"));
        assertEquals("false", property(true, "spring.flyway.enabled"));
        assertEquals("false", property(false, "spring.flyway.enabled"));
    }
    @Test
    void theProductionContextCannotBeOverriddenFromTheEnvironment() throws Exception {
        // The old SDV_LIQUIBASE_CONTEXTS placeholder is gone: the value is the literal "production".
        assertEquals("production", property(false, "spring.liquibase.contexts"));
        String yaml = new String(new ClassPathResource("application.yml").getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(yaml.contains("SDV_LIQUIBASE_CONTEXTS"));
    }

    private static List<ChangeSet> changeSets() throws Exception {
        ResourceAccessor accessor = new ClassLoaderResourceAccessor(LiquibaseSeedContextTest.class.getClassLoader());
        DatabaseChangeLog log = ChangeLogParserFactory.getInstance().getParser(MASTER, accessor)
                .parse(MASTER, new ChangeLogParameters(), accessor);
        return log.getChangeSets();
    }

    private static Set<String> runningIds(String contexts) throws Exception {
        ContextChangeSetFilter filter = new ContextChangeSetFilter(new Contexts(contexts));
        return changeSets().stream().filter(cs -> filter.accepts(cs).isAccepted()).map(ChangeSet::getId).collect(Collectors.toSet());
    }

    @Test
    void aStartWithoutTheLocalProfileUsesTheProductionContext() throws Exception {
        assertEquals("production", liquibaseContexts(false));
    }

    @Test
    void theLocalProfileUsesTheProductionContextToo() throws Exception {
        assertEquals("production", liquibaseContexts(true));
    }
    @Test
    void aNonLocalStartRunsNoLocalSeedData() throws Exception {
        Set<String> running = runningIds(liquibaseContexts(false));
        assertFalse(running.contains(LOCAL_SEED), "the local seed must not run outside the local profile");
        for (ChangeSet cs : changeSets()) {
            if (cs.getContextFilter() != null && !cs.getContextFilter().isEmpty()
                    && cs.getContextFilter().getContexts().contains("local")) {
                assertFalse(running.contains(cs.getId()), cs.getId() + " is local-only and must not run outside the local profile");
            }
        }
    }

    @Test
    void noChangesetIsLocalOnlyAndEveryOneRunsInProduction() throws Exception {
        // Task 0041 (owner answer 3): the local seed (002) is gone; demo entities come from setup and the demo step.
        Set<String> production = runningIds(liquibaseContexts(false));
        for (ChangeSet cs : changeSets()) {
            assertTrue(cs.getContextFilter() == null || cs.getContextFilter().isEmpty(), cs.getId() + " has a context");
            assertTrue(production.contains(cs.getId()), cs.getId() + " must run in production");
        }
        assertFalse(runningIds(null).contains(LOCAL_SEED));
        assertTrue(production.contains("018-search-date-indexes"));
        assertTrue(production.contains("035-drop-old-identity"));
    }
}
