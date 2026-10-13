package com.sdv.platform.web.admin.identity;

import com.sdv.platform.persistence.identity.JdbcOidcConnectionBindingRepository;
import com.sdv.platform.persistence.identity.JdbcOidcConnectionBindingRepository.ConnectionAdminView;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnterpriseIdentityPreCheckpointCVersionGuardContractTest {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void staleConnectionTestResultIsDiscardedWhenDraftVersionChanged() {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        long testedVersion = 7L;
        ConnectionAdminView testedSnapshot = new ConnectionAdminView(
                connectionId, tenantId, "customer-oidc", "OIDC", "Corporate Identity Provider",
                "http://localhost:8181/realms/document-platform-local", "sdv-web", "sdv-api",
                "DRAFT", testedVersion, Instant.now(), Instant.now(), null, null, null);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doReturn(List.of(testedSnapshot)).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(jdbc.update(contains("configuration_version=?"), any(Object[].class))).thenReturn(0);

        JdbcOidcConnectionBindingRepository repository = new JdbcOidcConnectionBindingRepository(jdbc);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.recordTest(tenantId, connectionId, testedVersion, true, "OIDC discovery validation passed.", "tenant.it.admin"));

        assertTrue(error.getMessage().contains("stale test result was discarded"));
    }
}
