package com.sdv.platform.web.security;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.persistence.identity.JdbcOidcConnectionBindingRepository;
import com.sdv.platform.web.schema.DatabaseSchemaCheck;
import com.sdv.platform.web.schema.DatabaseSchemaCheckAccess;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0037, item 5: the sign-in recovery (SDV-SYS-011) and the connection bootstrap's tenant ID (SDV-SYS-005). */
class StartupSignInCodesTest {
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String CONNECTION = "22222222-2222-2222-2222-222222222222";
    private final JdbcOidcConnectionBindingRepository repository = mock(JdbcOidcConnectionBindingRepository.class);

    private StartupFailure recovery(DatabaseSchemaCheck schema, String tenant, String connection, long version, String request) {
        return assertThrows(StartupFailure.class, () -> new OidcKnownGoodRecovery(repository, schema, tenant, connection, version, request)
                .run(new DefaultApplicationArguments()));
    }

    @Test
    void everyRefusedRecoveryIsSys011AndSaysWhichCheck() {
        DatabaseSchemaCheck current = DatabaseSchemaCheckAccess.current();
        StartupFailure notMatching = recovery(DatabaseSchemaCheckAccess.unreachable().check(), TENANT, CONNECTION, 3, "r-1");
        assertEquals(SdvErrorCode.SYS_011, notMatching.errorCode());
        assertTrue(notMatching.detail().startsWith("OIDC recovery is enabled but was not applied."));
        assertEquals(SdvErrorCode.SYS_011, recovery(current, TENANT, CONNECTION, 0, "r-1").errorCode(), "incomplete settings");
        StartupFailure badId = recovery(current, "not-a-uuid", CONNECTION, 3, "r-1");
        assertEquals("sdv.auth.jwt.recovery.tenant-id must be a UUID.", badId.detail(), "no raw \"Invalid UUID string\"");
        when(repository.recoverKnownGood(any(), any(), anyLong(), any()))
                .thenThrow(new IllegalStateException("Recovery request-id has already been consumed and will not be replayed."));
        StartupFailure used = recovery(current, TENANT, CONNECTION, 3, "r-1");
        assertEquals(SdvErrorCode.SYS_011, used.errorCode());
        assertTrue(used.detail().contains("already been consumed"));
    }

    @Test
    void theBootstrapRefusesAMalformedTenantIdAsASignInSetting() {
        assertEquals(SdvErrorCode.SYS_005, assertThrows(StartupFailure.class, () -> OidcConnectionBootstrap.tenant("tenant-1")).errorCode());
        assertEquals(UUID.fromString(TENANT), OidcConnectionBootstrap.tenant(" " + TENANT + " "));
    }

    @Test
    void theBootstrapIsStartUpWorkThatWaitsForTheDatabase() {
        OidcConnectionBootstrap bootstrap = new OidcConnectionBootstrap(repository, "enterprise-jwt", TENANT, "customer-oidc",
                "https://idp.example.org/realm", "vero-ui", "vero", true);
        assertInstanceOf(com.sdv.platform.application.port.DatabaseStartupWork.class, bootstrap);
        bootstrap.run();
        verify(repository).bootstrapIfAbsent(eq(UUID.fromString(TENANT)), eq("customer-oidc"), any(), eq("vero-ui"), eq("vero"));
    }
}
