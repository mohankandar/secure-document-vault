package com.sdv.platform.web.security;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.persistence.identity.JdbcOidcConnectionBindingRepository;
import com.sdv.platform.web.schema.DatabaseSchemaCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Explicit one-shot operational recovery for an OIDC lockout. Disabled by default.
 * Task 0037: every refusal stops the start with SDV-SYS-011 and says which check failed.
 */
@Component
@ConditionalOnProperty(name="sdv.auth.jwt.recovery.enabled", havingValue="true")
public class OidcKnownGoodRecovery implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(OidcKnownGoodRecovery.class);
    private final JdbcOidcConnectionBindingRepository repository;
    private final DatabaseSchemaCheck schema;
    private final String tenantId,connectionId,requestId; private final long expectedVersion;

    public OidcKnownGoodRecovery(JdbcOidcConnectionBindingRepository repository, DatabaseSchemaCheck schema,
            @Value("${sdv.auth.jwt.recovery.tenant-id:}") String tenantId,
            @Value("${sdv.auth.jwt.recovery.connection-id:}") String connectionId,
            @Value("${sdv.auth.jwt.recovery.expected-version:0}") long expectedVersion,
            @Value("${sdv.auth.jwt.recovery.request-id:}") String requestId) {
        this.repository=repository; this.schema=schema; this.tenantId=tenantId; this.connectionId=connectionId; this.expectedVersion=expectedVersion; this.requestId=requestId;
    }

    @Override public void run(ApplicationArguments args) {
        // An explicitly requested recovery must not be skipped silently, so a mismatched database stops the start.
        if(!schema.current())
            throw new StartupFailure(SdvErrorCode.SYS_011, "OIDC recovery is enabled but was not applied. "+schema.status().message());
        if(tenantId==null||tenantId.isBlank()||connectionId==null||connectionId.isBlank()||requestId==null||requestId.isBlank()||expectedVersion<=0)
            throw new StartupFailure(SdvErrorCode.SYS_011, "OIDC recovery is enabled but tenant-id, connection-id, expected-version, and unique request-id are not fully configured.");
        UUID tenant=uuid(tenantId,"sdv.auth.jwt.recovery.tenant-id"), connection=uuid(connectionId,"sdv.auth.jwt.recovery.connection-id");
        JdbcOidcConnectionBindingRepository.ActivationResult result;
        try {
            result=repository.recoverKnownGood(tenant,connection,expectedVersion,requestId.trim());
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw new StartupFailure(SdvErrorCode.SYS_011, ex.getMessage(), ex);
        }
        log.warn("ENTERPRISE_IDENTITY_RECOVERY_APPLIED tenantId={} connectionId={} version={} requestId={}",tenantId.trim(),result.active().connectionId(),result.active().configurationVersion(),requestId.trim());
    }

    static UUID uuid(String value, String property) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new StartupFailure(SdvErrorCode.SYS_011, property + " must be a UUID.");
        }
    }
}
