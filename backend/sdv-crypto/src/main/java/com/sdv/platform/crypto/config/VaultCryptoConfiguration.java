package com.sdv.platform.crypto.config;

import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.crypto.enc.SdvEnc001StreamingCrypto;
import com.sdv.platform.crypto.key.KeyProtectionProvider;
import com.sdv.platform.crypto.key.VaultTransitKeyProtectionProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

@Configuration
@ConditionalOnProperty(prefix = "sdv.crypto", name = "key-provider", havingValue = "vault")
public class VaultCryptoConfiguration {
    @Bean
    VaultTransitKeyProtectionProvider vaultTransitKeyProtectionProvider(Environment environment) {
        String baseUrl = required(environment, "sdv.crypto.vault.base-url");
        String token = resolveToken(environment);
        String mount = environment.getProperty("sdv.crypto.vault.mount", "transit");
        String keyPrefix = environment.getProperty("sdv.crypto.vault.key-prefix", "sdv-tenant-");
        return new VaultTransitKeyProtectionProvider(URI.create(baseUrl), token, mount, keyPrefix);
    }

    @Bean
    StreamingCryptoPort sdvEnc001StreamingCrypto(KeyProtectionProvider keyProtectionProvider) {
        return new SdvEnc001StreamingCrypto(keyProtectionProvider);
    }


    static String resolveToken(Environment environment) {
        String direct = environment.getProperty("sdv.crypto.vault.token");
        String tokenFile = environment.getProperty("sdv.crypto.vault.token-file");
        boolean hasDirect = direct != null && !direct.isBlank();
        boolean hasFile = tokenFile != null && !tokenFile.isBlank();
        if (hasDirect == hasFile) {
            throw new IllegalStateException("Configure exactly one of sdv.crypto.vault.token or sdv.crypto.vault.token-file.");
        }
        if (hasDirect) return direct.trim();
        try {
            String value = Files.readString(Path.of(tokenFile.trim()), StandardCharsets.UTF_8).trim();
            if (value.isBlank()) throw new IllegalStateException("Configured SDV Vault token file is empty.");
            return value;
        } catch (IOException ex) {
            throw new IllegalStateException("Configured SDV Vault token file cannot be read.", ex);
        }
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required SDV crypto configuration is missing: " + name);
        }
        return value;
    }
}
