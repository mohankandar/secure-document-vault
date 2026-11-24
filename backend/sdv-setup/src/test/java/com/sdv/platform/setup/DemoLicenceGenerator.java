package com.sdv.platform.setup;

import com.documentplatform.licensing.Allowance;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.Deployment;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceType;
import com.documentplatform.licensing.TrustedKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Task 0041: makes Vero's demo licence in {@code dev/setup/} and the development build's licence keys. Runs only when
 * asked:
 *
 * <pre>mvn -pl sdv-setup test -Dtest=DemoLicenceGenerator -Dsdv.demo.regenerate=true</pre>
 *
 * Task 0057: signed with the JDK through {@link DevelopmentLicences} (platform-core 0.6.0 moved its signer out of
 * platform-licensing). A new development key pair is made each time; its private key is thrown away after signing, so it exists nowhere.
 * Only the public key is written, marked {@code "development": true}. A production release never lists it, so a
 * production install refuses the demo licence (LICENCE_UNKNOWN_KEY_ID). Vault is not used.
 */
class DemoLicenceGenerator {
    static final String DEV_KEY_ID = "vero-dev-demo-1";
    static final Path DEV_SETUP = Path.of("..", "..", "dev", "setup");

    @Test
    @EnabledIfSystemProperty(named = "sdv.demo.regenerate", matches = "true")
    void writeDemoLicence() throws Exception {
        KeyPair dev = DevelopmentLicences.newKey();
        List<Allowance> allowance = List.of(new Allowance("SDV", AllowanceType.PAGES_PER_MONTH, 100000));
        Licence licence = new Licence("LIC-VERO-DEMO-0001", 1, "Acme Lending (demo)", "TEN-VERO-DEMO-0001",
                List.of("SDV"),
                List.of(new EntitySlot("ENT-VERO-DEMO-0001", allowance), new EntitySlot("ENT-VERO-DEMO-0002", allowance)),
                LicenceType.PERPETUAL, LicenceKind.NON_PRODUCTION, LocalDate.parse("2026-01-01"), null,
                LocalDate.parse("2036-12-31"), Deployment.ON_PREM, DEV_KEY_ID, Instant.parse("2026-11-22T00:00:00Z"));
        Files.writeString(DEV_SETUP.resolve("licence-vero-demo.json"), DevelopmentLicences.sign(licence, dev.getPrivate()),
                StandardCharsets.UTF_8);
        Files.writeString(Path.of("..", "licence-build", "development", "META-INF", "platform", "licence-keys.json"),
                TrustedKeys.of(Map.of(DEV_KEY_ID, dev.getPublic()), Set.of(DEV_KEY_ID)).toJson(), StandardCharsets.UTF_8);
    }
}
