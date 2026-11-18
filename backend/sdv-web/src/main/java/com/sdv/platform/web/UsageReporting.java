package com.sdv.platform.web;

/**
 * Vero sends no usage data anywhere. Third-party libraries that would report usage are switched off here, before
 * they load: the app's main and the schema step both call this first.
 *
 * Today the only such library is Liquibase (4.30 and later send anonymous usage analytics unless
 * liquibase.analytics.enabled is false). Liquibase reads this setting from Java system properties, not from
 * Spring's application.yml, so it is set here rather than in configuration.
 */
public final class UsageReporting {
    public static final String LIQUIBASE_ANALYTICS = "liquibase.analytics.enabled";

    private UsageReporting() { }

    public static void switchOff() {
        System.setProperty(LIQUIBASE_ANALYTICS, "false");
    }
}
