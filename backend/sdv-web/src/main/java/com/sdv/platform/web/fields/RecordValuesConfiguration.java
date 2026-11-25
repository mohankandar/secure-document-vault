package com.sdv.platform.web.fields;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.sdv.platform.application.port.RecordValuesPort;
import com.sdv.platform.persistence.fields.PlatformRecordValues;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.UUID;

/** Task 0078: the records' values. Without a key service (a local start) everything that isn't sensitive works; a sensitive value is refused. */
@Configuration
class RecordValuesConfiguration {
    @Bean
    RecordValuesPort recordValues(DataSource dataSource, ObjectProvider<KeyService> keys, ObjectProvider<CustomerId> customer,
                                  @Value("${platform.licensing.tenant-id:}") String tenant) {
        CustomerId own = customer.getIfAvailable(() -> tenant.isBlank() ? CustomerId.of(new UUID(0, 0)) : CustomerId.fromTenantId(tenant));
        return new PlatformRecordValues(dataSource, own, keys.getIfAvailable(RecordValuesConfiguration::none));
    }

    private static KeyService none() {
        return (KeyService) Proxy.newProxyInstance(KeyService.class.getClassLoader(), new Class<?>[]{KeyService.class}, (proxy, method, args) -> switch (method.getName()) {
            case "id", "toString" -> "none";
            case "hashCode" -> 0;
            case "equals" -> proxy == args[0];
            default -> throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, "no key service is configured");
        });
    }
}
