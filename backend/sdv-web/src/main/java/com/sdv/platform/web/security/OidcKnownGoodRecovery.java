package com.sdv.platform.web.security;

import com.sdv.platform.persistence.identity.JdbcOidcConnectionBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Explicit one-shot operational recovery for an OIDC lockout. Disabled by default. */
@Component
@ConditionalOnProperty(name="sdv.auth.jwt.recovery.enabled", havingValue="true")
public class OidcKnownGoodRecovery implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(OidcKnownGoodRecovery.class);
    private final JdbcOidcConnectionBindingRepository repository;
    private final String tenantId,connectionId,requestId; private final long expectedVersion;

    public OidcKnownGoodRecovery(JdbcOidcConnectionBindingRepository repository,
            @Value("${sdv.auth.jwt.recovery.tenant-id:}") String tenantId,
            @Value("${sdv.auth.jwt.recovery.connection-id:}") String connectionId,
            @Value("${sdv.auth.jwt.recovery.expected-version:0}") long expectedVersion,
            @Value("${sdv.auth.jwt.recovery.request-id:}") String requestId) {
        this.repository=repository; this.tenantId=tenantId; this.connectionId=connectionId; this.expectedVersion=expectedVersion; this.requestId=requestId;
    }

    @Override public void run(ApplicationArguments args) {
        if(tenantId==null||tenantId.isBlank()||connectionId==null||connectionId.isBlank()||requestId==null||requestId.isBlank()||expectedVersion<=0)
            throw new IllegalStateException("OIDC recovery is enabled but tenant-id, connection-id, expected-version, and unique request-id are not fully configured.");
        var result=repository.recoverKnownGood(UUID.fromString(tenantId.trim()),UUID.fromString(connectionId.trim()),expectedVersion,requestId.trim());
        log.warn("ENTERPRISE_IDENTITY_RECOVERY_APPLIED tenantId={} connectionId={} version={} requestId={}",tenantId.trim(),result.active().connectionId(),result.active().configurationVersion(),requestId.trim());
    }
}
