package com.sdv.platform.web.security;

import com.sdv.platform.application.port.DatabaseStartupWork;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.persistence.identity.JdbcOidcConnectionBindingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.UUID;

/**
 * Writes the configured sign-in connection when the tenant has none yet. Task 0037: it is start-up work that waits
 * for the database (DatabaseStartupWork), so nothing is written while the database doesn't match this version and it
 * runs as soon as it does, also when the database comes back after the start.
 */
@Component
public class OidcConnectionBootstrap implements DatabaseStartupWork {
    private final JdbcOidcConnectionBindingRepository repository;
    private final String mode, tenantId, providerId, issuer, browserClientId, audience; private final boolean enabled;
    public OidcConnectionBootstrap(JdbcOidcConnectionBindingRepository repository,
      @Value("${sdv.auth.mode:disabled}") String mode, @Value("${sdv.auth.jwt.tenant-id:}") String tenantId,
      @Value("${sdv.auth.jwt.provider-id:customer-oidc}") String providerId, @Value("${sdv.auth.jwt.issuer:}") String issuer,
      @Value("${sdv.auth.jwt.browser-client-id:}") String browserClientId, @Value("${sdv.auth.jwt.audience:}") String audience,
      @Value("${sdv.auth.jwt.persistent-connection-enabled:true}") boolean enabled) {
      this.repository=repository;this.mode=mode;this.tenantId=tenantId;this.providerId=providerId;this.issuer=issuer;this.browserClientId=browserClientId;this.audience=audience;this.enabled=enabled;
    }

    @Override public String name() { return "sign-in connection bootstrap"; }

    @Override public void run() {
      if(!enabled||!"enterprise-jwt".equalsIgnoreCase(mode)||tenantId.isBlank()||issuer.isBlank()||audience.isBlank())return;
      repository.bootstrapIfAbsent(tenant(tenantId),providerId,URI.create(issuer.trim()),browserClientId,audience);
    }

    /** The sign-in settings check refuses a malformed tenant ID first (SDV-SYS-005); this keeps the same answer. */
    static UUID tenant(String value) {
      try {
        return UUID.fromString(value.trim());
      } catch (IllegalArgumentException ex) {
        throw new StartupFailure(SdvErrorCode.SYS_005, "sdv.auth.jwt.tenant-id (SDV_AUTH_JWT_TENANT_ID) must be a valid UUID when configured.");
      }
    }
}
