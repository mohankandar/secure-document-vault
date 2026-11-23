package com.sdv.platform.application.identity;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.admission.AdmissionSettings;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.EntityMembershipResolver;
import com.documentplatform.identity.jdbc.IdentitySchema;
import com.documentplatform.identity.jdbc.PostgresIdentityLookup;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.documentplatform.licensing.Allowance;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.Deployment;
import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceSigner;
import com.documentplatform.licensing.LicenceType;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.TrustedKeys;
import com.documentplatform.licensing.jdbc.PostgresLastSeenStore;
import com.documentplatform.licensing.jdbc.PostgresLicenceStore;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.RoleConflictReporter;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.oidc.OidcJwtAuthenticationConverter;
import com.sdv.platform.security.auth.oidc.SdvRoleCatalog;
import com.sdv.platform.web.install.DemoStepAccess;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.Jwt;

import java.net.URI;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0041: Vero on platform-core identity against a real PostgreSQL database after the schema step: the identity
 * (V1-V3) and licensing (V1) tables are there at their version with their own history tables; platform-core's lookup
 * gives a person their entity and roles from group grants, and the separation rules still hold on those grants (owner
 * answer 8); Vero's own reads (an ACTIVE connection, slots to activate); a client binding gives a sending system's
 * entity and source; and the demo step activates slots under a stored, development-signed licence and makes Vero's rows.
 * <p>
 * Runs only when SDV_IT_JDBC_URL is set (CI, Test-SDV-SchemaUpgrade.ps1); never against the sdv database.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test")
class PlatformIdentityPostgresTest {
    static final String TENANT = "TEN-PG-IDENTITY-0001";
    static final String ACTIVE_SLOT = "ENT-PG-IDENTITY-0001";
    static final String NEW_SLOT = "ENT-PG-IDENTITY-0002";
    static final UUID ACTIVE = EntityUuid.fromSlotId(ACTIVE_SLOT);
    static final UUID NEW = EntityUuid.fromSlotId(NEW_SLOT);
    /** Activated by the demo-step test only, so the other tests always see NEW_SLOT not activated. */
    static final String DEMO_SLOT = "ENT-PG-IDENTITY-0003";
    static final UUID DEMO = EntityUuid.fromSlotId(DEMO_SLOT);
    static final URI ISSUER = URI.create("https://login.pg-identity.example.test/oauth2/default");
    static final ConnectionKey KEY = new ConnectionKey("customer-oidc", ISSUER, "sdv-api");
    static final String CLIENT = "pg-identity-client";
    static final UUID SOURCE = UUID.fromString("5e7d0000-0000-4000-8000-0000000000a1");

    static DriverManagerDataSource ds;
    static JdbcTemplate db;

    @BeforeAll
    static void fixture() {
        ds = new DriverManagerDataSource(System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"),
                System.getenv("SDV_IT_JDBC_PASSWORD"));
        db = new JdbcTemplate(ds);
        clean();
        db.update("insert into idn_customer(tenant_id, name) values (?, 'PG identity customer')", TENANT);
        db.update("insert into idn_entity(slot_id, entity_uuid, tenant_id, status, display_name, activated_by, activated_at) "
                + "values (?, ?, ?, 'ACTIVE', 'PG active entity', 'test', now())", ACTIVE_SLOT, ACTIVE, TENANT);
        db.update("insert into idn_entity(slot_id, entity_uuid, tenant_id, status) values (?, ?, ?, 'NOT_ACTIVATED')",
                NEW_SLOT, NEW, TENANT);
        db.update("insert into idn_entity(slot_id, entity_uuid, tenant_id, status) values (?, ?, ?, 'NOT_ACTIVATED')",
                DEMO_SLOT, DEMO, TENANT);
        db.update("insert into idn_login_connection(connection_id, tenant_id, product_code, provider_id, provider_type, "
                + "issuer_uri, api_audience, status, configuration_version) values (?, ?, 'SDV', 'customer-oidc', 'OIDC', ?, "
                + "'sdv-api', 'ACTIVE', 1)", UUID.randomUUID(), TENANT, ISSUER.toString());
        grant(ACTIVE_SLOT, "pg-users", "BUSINESS_USER");
        grant(ACTIVE_SLOT, "pg-restricted", "RESTRICTED_STEWARD");
        grant(ACTIVE_SLOT, "pg-admins", "BUSINESS_ADMIN");
        grant(NEW_SLOT, "pg-admins", "BUSINESS_ADMIN");
        grant(NEW_SLOT, "pg-users", "BUSINESS_USER");
        db.update("insert into idn_machine_binding(binding_id, tenant_id, product_code, client_id, slot_id, entity_uuid, "
                + "source_key, status, version) values (?, ?, 'SDV', ?, ?, ?, 'pg-source', 'ACTIVE', 1)",
                UUID.randomUUID(), TENANT, CLIENT, ACTIVE_SLOT, ACTIVE);
        db.update("insert into tenants(tenant_id, tenant_key, display_name, status) values (?, 'pg-active', 'PG active entity', 'ACTIVE')", ACTIVE);
        db.update("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) "
                + "values (?, ?, 'pg-source', 'PG source', 'ACTIVE', 'OAUTH2_CLIENT_CREDENTIALS')", SOURCE, ACTIVE);
    }

    static void grant(String slot, String group, String role) {
        db.update("insert into idn_group_grant(slot_id, product_code, issuer_uri, external_group, role) values (?, 'SDV', ?, ?, ?)",
                slot, ISSUER.toString(), group, role);
    }

    @AfterAll
    static void clean() {
        if (db == null) return;
        // Task 0050: audit rows are append-only (the database refuses to delete them), so they stay, and so do the
        // entities they name. The scratch database is made new for every run.
        db.update("delete from retention_policy_versions where tenant_id in (?, ?, ?)", ACTIVE, NEW, DEMO);
        db.update("delete from ingestion_sources where tenant_id in (?, ?, ?)", ACTIVE, NEW, DEMO);
        db.update("delete from idn_machine_binding where tenant_id = ?", TENANT);
        db.update("delete from idn_group_grant where slot_id in (?, ?, ?)", ACTIVE_SLOT, NEW_SLOT, DEMO_SLOT);
        db.update("delete from idn_login_connection where tenant_id = ?", TENANT);
        db.update("delete from idn_entity where tenant_id = ?", TENANT);
        db.update("delete from idn_customer where tenant_id = ?", TENANT);
        db.update("delete from lic_licence");
    }

    @Test
    void theSchemaStepMadeTheIdentityAndLicensingTablesWithTheirOwnHistory() {
        assertEquals(3, db.queryForObject("select max(version) from idn_schema_version", Integer.class));
        assertEquals(1, db.queryForObject("select max(version) from lic_schema_version", Integer.class));
        assertTrue(db.queryForObject("select count(*) from idn_schema_history where success", Integer.class) >= 3);
        assertTrue(db.queryForObject("select count(*) from lic_schema_history where success", Integer.class) >= 1);
        assertEquals(0, db.queryForObject("select count(*) from pg_tables where tablename in ('enterprise_identity_connections', "
                + "'enterprise_identity_connection_history', 'role_group_mappings_seen', 'role_mapping_notices')", Integer.class));
        IdentitySchema.verify(ds);
    }

    @Test
    void verosReadsFindTheConnectionAndTheSlotsABusinessAdminMayActivate() {
        PlatformIdentityReads reads = new PlatformIdentityReads(new NamedParameterJdbcTemplate(ds));
        assertTrue(reads.connectionActive(KEY));
        assertEquals(false, reads.connectionActive(new ConnectionKey("customer-oidc", ISSUER, "another-audience")));
        assertEquals(List.of(NEW_SLOT), reads.notActivatedSlotsGranting(KEY, List.of("pg-admins", "other"), "BUSINESS_ADMIN"));
        assertEquals(List.of(), reads.notActivatedSlotsGranting(KEY, List.of("pg-users"), "BUSINESS_ADMIN"), "not an admin grant");
        assertEquals(ACTIVE_SLOT, reads.slotOf(ACTIVE).orElseThrow().slotId());
    }

    @Test
    void aPersonGetsTheirActiveEntitysRolesAndTheSeparationRulesHoldOnPlatformGrants() {
        List<RoleConflictReporter.RoleConflict> conflicts = new ArrayList<>();
        OidcJwtAuthenticationConverter converter = converter(conflicts);
        SdvPrincipal user = (SdvPrincipal) converter.convert(person(List.of("pg-users"))).getPrincipal();
        assertEquals(ACTIVE, user.tenantId(), "only the ACTIVE entity is admissible; the other slot is not activated");
        assertEquals(Set.of(SdvRole.BUSINESS_USER), user.roles());

        SdvPrincipal conflicted = (SdvPrincipal) converter.convert(person(List.of("pg-users", "pg-restricted", "pg-admins"))).getPrincipal();
        assertEquals(Set.of(SdvRole.BUSINESS_USER), conflicted.roles(), "Restricted Steward and Business Admin are withheld");
        assertEquals(1, conflicts.size());
        assertEquals(ACTIVE, conflicts.get(0).tenantId());
    }

    @Test
    void aClientBindingGivesTheSendingSystemsEntityAndSource() {
        var senders = new com.sdv.platform.web.security.PlatformIdentityConfigurationAccess().senders(ds);
        var binding = senders.activeBinding(CLIENT).orElseThrow();
        assertEquals(ACTIVE, binding.entityUuid());
        assertEquals("pg-source", binding.sourceKey());
        assertEquals(Set.of(EntityId.of(ACTIVE)), senders.admissible(KEY, CLIENT));
        assertEquals(Set.of(), senders.admissible(new ConnectionKey("customer-oidc", ISSUER, "another-audience"), CLIENT));
        assertEquals(Optional.empty(), senders.activeBinding("not-bound"));
    }

    @Test
    void theDemoStepActivatesUnderAStoredDevelopmentLicenceAndMakesVerosRow() throws Exception {
        KeyPair key = Ed25519.generate();
        TrustedKeys keys = TrustedKeys.of(Map.of("pg-dev-1", key.getPublic()), Set.of("pg-dev-1"));
        List<Allowance> a = List.of(new Allowance("SDV", AllowanceType.PAGES_PER_MONTH, 10));
        Licence licence = new Licence("LIC-PG-IDENTITY-0001", 1, "PG identity customer", TENANT, List.of("SDV"),
                List.of(new EntitySlot(ACTIVE_SLOT, a), new EntitySlot(NEW_SLOT, a), new EntitySlot(DEMO_SLOT, a)), LicenceType.PERPETUAL,
                LicenceKind.NON_PRODUCTION, LocalDate.parse("2026-01-01"), null, LocalDate.parse("2036-12-31"),
                Deployment.ON_PREM, "pg-dev-1", Instant.parse("2026-11-22T00:00:00Z"));
        db.update("delete from lic_licence");
        new LicenceService(new PostgresLicenceStore(ds), new LicenceVerifier(keys),
                new ClockGuard(Clock.systemUTC(), new PostgresLastSeenStore(ds)), DemoStepAccess.noUsage(), "SDV", null)
                .store(LicenceSigner.sign(licence, key.getPrivate()));

        assertEquals(0, DemoStepAccess.apply(ds, keys, Map.of(DEMO_SLOT, "PG demo entity")));
        assertEquals("ACTIVE", db.queryForObject("select status from idn_entity where slot_id = ?", String.class, DEMO_SLOT));
        assertEquals("PG demo entity", db.queryForObject("select display_name from tenants where tenant_id = ?", String.class, DEMO));
        assertEquals(1, db.queryForObject("select count(*) from audit_events where tenant_id = ? and action = 'ENTITY_ACTIVATED'", Integer.class, DEMO));
        assertEquals(0, DemoStepAccess.apply(ds, keys, Map.of(DEMO_SLOT, "PG demo entity")), "a rerun changes nothing");
        assertEquals(1, db.queryForObject("select count(*) from audit_events where tenant_id = ? and action = 'ENTITY_ACTIVATED'", Integer.class, DEMO));

        TrustedKeys production = TrustedKeys.of(Map.of("pg-dev-1", key.getPublic()));
        assertEquals(2, DemoStepAccess.apply(ds, production, Map.of(DEMO_SLOT, "PG demo entity")),
                "the demo step refuses a licence that is not signed by a development key");
    }

    static OidcJwtAuthenticationConverter converter(List<RoleConflictReporter.RoleConflict> conflicts) {
        PostgresIdentityLookup lookup = new PostgresIdentityLookup(ds, "SDV");
        PlatformIdentityReads reads = new PlatformIdentityReads(new NamedParameterJdbcTemplate(ds));
        var settings = new OidcJwtAuthenticationConverter.Settings("customer-oidc", "sdv-api", "groups",
                OidcJwtAuthenticationConverter.DEFAULT_CLIENT_ID_CLAIMS, "sdv_entity_id", "sdv_source_id", "sdv_source_key");
        var people = new EntityMembershipResolver(lookup, lookup, SdvRoleCatalog.shipped(),
                new AdmissionSettings(false, true, "customer-oidc", "sdv-api"));
        var lookups = new OidcJwtAuthenticationConverter.SignInLookups() {
            @Override public boolean connectionActive(ConnectionKey key) { return reads.connectionActive(key); }
            @Override public List<String> activatableSlots(ConnectionKey key, Object groups) { return List.of(); }
        };
        var none = new OidcJwtAuthenticationConverter.Senders() {
            @Override public Optional<com.documentplatform.identity.store.MachineBinding> activeBinding(String c) { return Optional.empty(); }
            @Override public Set<EntityId> admissible(ConnectionKey k, String c) { return Set.of(); }
        };
        var request = new OidcJwtAuthenticationConverter.RequestView() {
            @Override public String requestedEntity() { return null; }
            @Override public boolean intake() { return false; }
        };
        return new OidcJwtAuthenticationConverter(settings, new OidcJwtIdentityNormalizer("customer-oidc",
                new IdentityClaimConfig("preferred_username", "email", "name", "groups")), people, lookups, none,
                (e, k) -> Optional.empty(), request, conflicts::add);
    }

    static Jwt person(List<String> groups) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("pg-person").issuer(ISSUER.toString())
                .audience(List.of("sdv-api")).issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300))
                .claim("groups", groups).claim("azp", "sdv-web").build();
    }
}
