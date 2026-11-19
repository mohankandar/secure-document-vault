package com.sdv.platform.web;

import com.sdv.platform.web.schema.SchemaStepAccess;
import liquibase.analytics.configuration.AnalyticsArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UsageReportingTest {
    @AfterEach
    void reset() {
        System.clearProperty(UsageReporting.LIQUIBASE_ANALYTICS);
    }

    @Test
    void liquibaseItselfSeesAnalyticsSwitchedOff() {
        System.clearProperty(UsageReporting.LIQUIBASE_ANALYTICS);
        UsageReporting.switchOff();
        // Read through Liquibase's own setting, so a renamed property would fail here.
        assertEquals(Boolean.FALSE, AnalyticsArgs.ENABLED.getCurrentValue());
    }

    @Test
    void theSchemaStepSwitchesItOffBeforeAnythingElse() {
        System.clearProperty(UsageReporting.LIQUIBASE_ANALYTICS);
        SchemaStepAccess.runWithoutArguments();
        assertEquals("false", System.getProperty(UsageReporting.LIQUIBASE_ANALYTICS));
    }

    @Test
    void theAppSwitchesItOffAsTheFirstThingInMain() throws Exception {
        String source = new String(SdvApplication.class.getResourceAsStream("SdvApplication.class").readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(source.contains("UsageReporting") && source.contains("switchOff"), "SdvApplication.main must call UsageReporting.switchOff()");
    }
}
