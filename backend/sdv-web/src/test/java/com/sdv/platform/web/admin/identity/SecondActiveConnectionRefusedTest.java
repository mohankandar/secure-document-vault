package com.sdv.platform.web.admin.identity;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.persistence.identity.JdbcOidcConnectionBindingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0038 (SDV-IDP-007), without a database: a draft whose issuer, audience and provider are already ACTIVE for
 * another tenant is refused when it is saved, before anything is written; and two ACTIVE rows found at sign-in are a
 * coded internal failure, not a bare IllegalStateException. JdbcOidcConnectionBindingPostgresTest does the same on
 * PostgreSQL, with the unique index.
 */
class SecondActiveConnectionRefusedTest {
    private static final UUID MINE = UUID.fromString("10000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("10000000-0000-0000-0000-0000000000b2");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcOidcConnectionBindingRepository repository = new JdbcOidcConnectionBindingRepository(jdbc);

    @Test
    @SuppressWarnings("unchecked")
    void aDraftForAnIssuerActiveElsewhereIsRefusedAtSaveAndNothingIsWritten() {
        when(jdbc.query(startsWith("select tenant_id from enterprise_identity_connections"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(OTHER));
        CodedRefusal.Conflict refused = assertThrows(CodedRefusal.Conflict.class, () -> repository.saveDraft(MINE, "customer-oidc",
                "Mine", URI.create("https://idp.example.test/realms/shared/"), "vero-ui", "vero-api", "it-admin"));
        assertEquals(SdvErrorCode.IDP_007, refused.errorCode());
        assertEquals(JdbcOidcConnectionBindingRepository.IDP_007_REFUSAL, refused.getMessage());
        assertFalse(refused.getMessage().contains(OTHER.toString()), "the answer names no other tenant");
        assertTrue(refused.logDetail().contains("ACTIVE for tenant " + OTHER), "the log line does");
        assertTrue(refused.logDetail().contains("issuer=https://idp.example.test/realms/shared"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoActiveRowsAtSignInAreIdp007() {
        var binding = new com.documentplatform.identity.OidcConnectionBinding(UUID.randomUUID(), MINE, "customer-oidc",
                URI.create("https://idp.example.test/realms/shared"), "vero-api", 1L);
        var twin = new com.documentplatform.identity.OidcConnectionBinding(UUID.randomUUID(), OTHER, "customer-oidc",
                URI.create("https://idp.example.test/realms/shared"), "vero-api", 1L);
        when(jdbc.query(startsWith("select connection_id"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(binding, twin));
        InternalCheckFailed failed = assertThrows(InternalCheckFailed.class,
                () -> repository.findActive("customer-oidc", URI.create("https://idp.example.test/realms/shared"), "vero-api"));
        assertEquals(SdvErrorCode.IDP_007, failed.errorCode());
        assertTrue(failed.getMessage().contains(MINE.toString()) && failed.getMessage().contains(OTHER.toString()),
                "the log line names both tenants for IT: " + failed.getMessage());
    }
}
