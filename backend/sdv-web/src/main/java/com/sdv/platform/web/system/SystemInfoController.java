package com.sdv.platform.web.system;

import com.sdv.platform.web.schema.DatabaseSchemaCheck;
import com.sdv.platform.web.schema.SchemaVersion;
import org.slf4j.MDC;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/system")
public final class SystemInfoController {
    private final Environment environment;
    private final DatabaseSchemaCheck schemaCheck;

    public SystemInfoController(Environment environment, DatabaseSchemaCheck schemaCheck) {
        this.environment = environment;
        this.schemaCheck = schemaCheck;
    }

    @GetMapping("/info")
    public SystemInfo systemInfo() {
        String version = Optional.ofNullable(getClass().getPackage().getImplementationVersion())
                .orElse("development");
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        SchemaVersion.Status schema = schemaCheck.status();

        return new SystemInfo(
                "SDV",
                "Secure Document Vault",
                version,
                schema.current() ? "UP" : "DOWN",
                profiles,
                new DatabaseSchema(schema.state().name(), schema.message()),
                MDC.get("correlationId"),
                Instant.now());
    }

    /** state: CURRENT, OLDER, NEWER or UNREACHABLE (checked once at startup); message: the plain sentence. */
    public record DatabaseSchema(String state, String message) {
    }

    public record SystemInfo(
            String productCode,
            String productName,
            String version,
            String status,
            List<String> activeProfiles,
            DatabaseSchema databaseSchema,
            String correlationId,
            Instant timestamp) {
    }
}
