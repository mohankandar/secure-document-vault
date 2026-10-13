package com.sdv.platform.crypto.config;

import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.application.port.KeyServiceHealthProbe;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

@Configuration
@ConditionalOnProperty(prefix = "sdv.crypto", name = "key-provider", havingValue = "disabled", matchIfMissing = true)
public class DisabledCryptoConfiguration {
    @Bean
    KeyServiceHealthProbe unavailableKeyServiceHealthProbe() {
        return () -> new KeyServiceHealthProbe.Health(
                KeyServiceHealthProbe.Status.NOT_CONFIGURED, "NONE",
                "No key-protection provider is configured.", java.time.Instant.now());
    }

    @Bean
    StreamingCryptoPort unavailableStreamingCryptoPort() {
        return new StreamingCryptoPort() {
            @Override
            public CryptoEnvelope encrypt(UUID tenantId, InputStream plaintext, OutputStream ciphertext) throws IOException {
                throw new IOException("SDV document crypto is unavailable because no KeyProtectionProvider is configured.");
            }

            @Override
            public void decrypt(UUID tenantId, CryptoEnvelope envelope, InputStream ciphertext, OutputStream plaintext) throws IOException {
                throw new IOException("SDV document crypto is unavailable because no KeyProtectionProvider is configured.");
            }
        };
    }
}
