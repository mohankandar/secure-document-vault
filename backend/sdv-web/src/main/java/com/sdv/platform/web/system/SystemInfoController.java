package com.sdv.platform.web.system;

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

    public SystemInfoController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/info")
    public SystemInfo systemInfo() {
        String version = Optional.ofNullable(getClass().getPackage().getImplementationVersion())
                .orElse("development");
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());

        return new SystemInfo(
                "SDV",
                "Secure Document Vault",
                version,
                "UP",
                profiles,
                MDC.get("correlationId"),
                Instant.now());
    }

    public record SystemInfo(
            String productCode,
            String productName,
            String version,
            String status,
            List<String> activeProfiles,
            String correlationId,
            Instant timestamp) {
    }
}
