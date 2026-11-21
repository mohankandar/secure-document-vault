package com.sdv.platform.application.identity;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.persistence.identity.JdbcOidcConnectionBindingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0038 (SDV-IDP-007), against a real PostgreSQL database with the application's repository: one issuer,
 * audience and provider can be ACTIVE for one tenant only.
 * <ul>
 *   <li>Confirmed: the unique index {@code uq_sdv_eic_active_binding} refuses a second ACTIVE row written directly.</li>
 *   <li>Tenant IT's draft for an issuer ACTIVE for another tenant is refused when it is saved (409 IDP-007).</li>
 *   <li>A draft saved before the other tenant took the issuer is refused when it is activated; nothing changes.</li>
 * </ul>
 * Runs only when SDV_IT_JDBC_URL is set (Test-SDV-SchemaUpgrade.ps1, scratch database sdv_schema_test); it never
 * points at the sdv database. In the normal build this test is skipped.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test")
class SignInConnectionPostgresTest {
    private static final UUID T1 = UUID.fromString("10000000-0000-0000-0000-0000000038a1");
    private static final UUID T2 = UUID.fromString("10000000-0000-0000-0000-0000000038b2");
    /** A tenant with no connection at all, for the direct write (the per-tenant ACTIVE index can't be what refuses it). */
    private static final UUID T3 = UUID.fromString("10000000-0000-0000-0000-0000000038c3");
    private static final String PROVIDER = "customer-oidc";
    private static final URI SHARED = URI.create("https://idp-0038.example.test/realms/shared");
    private static final URI OWN = URI.create("https://idp-0038.example.test/realms/t2");

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    private final JdbcOidcConnectionBindingRepository repository = new JdbcOidcConnectionBindingRepository(jdbc);

    private long count(String where) {
        Long n = jdbc.queryForObject("select count(*) from enterprise_identity_connections where " + where, Long.class);
        return n == null ? 0 : n;
    }

    @Test
    void oneIssuerIsActiveForOneTenantOnly() {
        for (UUID t : new UUID[]{T1, T2, T3}) {
            jdbc.update("delete from enterprise_identity_connection_history where tenant_id=?", t);
            jdbc.update("delete from enterprise_identity_connections where tenant_id=?", t);
            jdbc.update("insert into tenants(tenant_id, tenant_key, display_name, status) values (?, ?, 'Sign-in test', 'ACTIVE') "
                    + "on conflict (tenant_id) do nothing", t, "signin-" + t.toString().substring(30));
        }
        // T2 signs in through its own issuer, and stages a draft for the shared one while nobody uses it.
        repository.bootstrapIfAbsent(T2, PROVIDER, OWN, "vero-ui", "vero-api");
        var staged = repository.saveDraft(T2, PROVIDER, "Shared", SHARED, "vero-ui", "vero-api", "t2-it");
        staged = repository.recordTest(T2, staged.connectionId(), staged.configurationVersion(), true, "ok", "t2-it");

        // T1 then takes the shared issuer.
        repository.bootstrapIfAbsent(T1, PROVIDER, SHARED, "vero-ui", "vero-api");
        assertEquals(T1, repository.findActive(PROVIDER, SHARED, "vero-api").orElseThrow().tenantId());

        // The index alone refuses a second ACTIVE row (this is why every sign-in was not failing today).
        DuplicateKeyException twin = assertThrows(DuplicateKeyException.class, () -> jdbc.update("""
                insert into enterprise_identity_connections(connection_id, tenant_id, provider_id, provider_type, display_name,
                    issuer_uri, api_audience, status, configuration_version, created_at, updated_at)
                values (?, ?, ?, 'OIDC', 'Twin', ?, 'vero-api', 'ACTIVE', 99, ?, ?)""",
                UUID.randomUUID(), T3, PROVIDER, SHARED.toString(), Timestamp.from(Instant.now()), Timestamp.from(Instant.now())));
        assertTrue(twin.getMessage().contains("uq_sdv_eic_active_binding"), twin.getMessage());

        // T2's activation is refused with IDP-007 and changes nothing: T2 keeps its own issuer.
        long version = staged.configurationVersion();
        UUID connection = staged.connectionId();
        CodedRefusal.Conflict atActivation = assertThrows(CodedRefusal.Conflict.class,
                () -> repository.activateValidated(T2, connection, version, "t2-it"));
        assertEquals(SdvErrorCode.IDP_007, atActivation.errorCode());
        assertEquals(1, count("tenant_id='" + T2 + "' and status='ACTIVE' and issuer_uri='" + OWN + "'"));
        assertEquals(1, count("issuer_uri='" + SHARED + "' and status='ACTIVE'"));

        // A new save for the shared issuer is refused as soon as it is saved, and nothing is written.
        long before = count("tenant_id='" + T2 + "'");
        CodedRefusal.Conflict atSave = assertThrows(CodedRefusal.Conflict.class,
                () -> repository.saveDraft(T2, PROVIDER, "Shared again", SHARED, "vero-ui", "vero-api", "t2-it"));
        assertEquals(SdvErrorCode.IDP_007, atSave.errorCode());
        assertEquals(JdbcOidcConnectionBindingRepository.IDP_007_REFUSAL, atSave.getMessage());
        assertEquals(before, count("tenant_id='" + T2 + "'"));

        // The same issuer with T2's own audience is a different sign-in and is fine.
        assertDoesNotThrow(() -> repository.saveDraft(T2, PROVIDER, "Shared, own audience", SHARED, "vero-ui", "vero-api-t2", "t2-it"));
        // T1 may stage a new version of its own issuer.
        assertDoesNotThrow(() -> repository.saveDraft(T1, PROVIDER, "T1 v2", SHARED, "vero-ui", "vero-api", "t1-it"));
    }
}
