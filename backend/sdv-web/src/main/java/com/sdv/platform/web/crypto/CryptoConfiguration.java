package com.sdv.platform.web.crypto;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.envelope.StreamCipher;
import com.sdv.platform.application.port.KeyServiceHealthProbe;
import com.sdv.platform.application.port.StreamingCryptoPort;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;

/** Task 0076: documents through platform-core's crypto ({@code platform.crypto.*}), or none in a local start. */
@Configuration
public class CryptoConfiguration {
    /** The key service is one of the checks; the overall health address must not wait for it (task 0074). */
    @Bean
    static BeanDefinitionRegistryPostProcessor keyServiceNotInTheOverallHealth() {
        return registry -> {
            for (String name : registry.getBeanDefinitionNames()) {
                if (name.equals("keyServiceHealthIndicator") || name.equals("cryptoHealthIndicator")) registry.removeBeanDefinition(name);
            }
        };
    }

    @Configuration
    @ConditionalOnProperty(prefix = "platform.crypto", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class On {
        @Bean
        StreamingCryptoPort documentCrypto(StreamCipher cipher, CustomerId customer) {
            return new BoundStreamCrypto(cipher, customer);
        }

        @Bean
        KeyServiceHealthProbe keyServiceHealthProbe(KeyService keys) {
            return () -> {
                KeyService.Health h = keys.health();
                return new KeyServiceHealthProbe.Health(h.up() ? KeyServiceHealthProbe.Status.UP : KeyServiceHealthProbe.Status.DOWN, keys.id(),
                        h.up() ? "The key service answers." : h.code() == null ? "The key service doesn't work." : h.code().code(), Instant.now());
            };
        }
    }

    @Configuration
    @ConditionalOnProperty(prefix = "platform.crypto", name = "enabled", havingValue = "false")
    static class Off {
        @Bean
        KeyServiceHealthProbe noKeyServiceHealthProbe() {
            return () -> new KeyServiceHealthProbe.Health(KeyServiceHealthProbe.Status.NOT_CONFIGURED, "NONE", "No key service is configured.", Instant.now());
        }

        @Bean
        StreamingCryptoPort noDocumentCrypto() {
            return new StreamingCryptoPort() {
                @Override
                public CryptoEnvelope encrypt(Binding binding, InputStream plaintext, OutputStream ciphertext) throws IOException {
                    throw new IOException("Document encryption is switched off (platform.crypto.enabled).");
                }

                @Override
                public void decrypt(Binding binding, CryptoEnvelope envelope, InputStream ciphertext, OutputStream plaintext) throws IOException {
                    throw new IOException("Document encryption is switched off (platform.crypto.enabled).");
                }
            };
        }
    }
}
