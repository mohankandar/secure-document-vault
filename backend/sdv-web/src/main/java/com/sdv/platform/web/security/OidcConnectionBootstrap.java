package com.sdv.platform.web.security;

import com.sdv.platform.persistence.identity.JdbcOidcConnectionBindingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.UUID;

@Component
public class OidcConnectionBootstrap implements ApplicationRunner {
    private final JdbcOidcConnectionBindingRepository repository;
    private final String mode, tenantId, providerId, issuer, browserClientId, audience; private final boolean enabled;
    public OidcConnectionBootstrap(JdbcOidcConnectionBindingRepository repository,
      @Value("${sdv.auth.mode:disabled}") String mode, @Value("${sdv.auth.jwt.tenant-id:}") String tenantId,
      @Value("${sdv.auth.jwt.provider-id:customer-oidc}") String providerId, @Value("${sdv.auth.jwt.issuer:}") String issuer,
      @Value("${sdv.auth.jwt.browser-client-id:}") String browserClientId, @Value("${sdv.auth.jwt.audience:}") String audience,
      @Value("${sdv.auth.jwt.persistent-connection-enabled:true}") boolean enabled) {
      this.repository=repository;this.mode=mode;this.tenantId=tenantId;this.providerId=providerId;this.issuer=issuer;this.browserClientId=browserClientId;this.audience=audience;this.enabled=enabled;
    }
    @Override public void run(ApplicationArguments args) {
      if(!enabled||!"enterprise-jwt".equalsIgnoreCase(mode)||tenantId.isBlank()||issuer.isBlank()||audience.isBlank())return;
      repository.bootstrapIfAbsent(UUID.fromString(tenantId.trim()),providerId,URI.create(issuer.trim()),browserClientId,audience);
    }
}
