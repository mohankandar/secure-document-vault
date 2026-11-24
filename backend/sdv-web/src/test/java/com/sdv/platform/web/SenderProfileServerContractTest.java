package com.sdv.platform.web;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.testkit.MockIssuer;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.testkit.ProfileContract;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 0041 (board LV-2): platform-core's contract kit runs Vero's sender profile against a running Vero
 * ({@code ProfileContract.checkAgainstServer}): readiness is healthy or busy, the client-credentials sign-in works, and
 * an empty create is refused with a code a rule names. No real data.
 * <p>
 * The stack: Vero itself (the packaged app, OIDC sign-in) on the scratch database, platform-core's {@link MockIssuer}
 * as the login server, a small token endpoint beside it that hands out client-credentials tokens for one test client,
 * and that client bound to a test entity and sending system the way setup binds it ({@code idn_machine_binding}). The
 * token carries no custom claim (contract 1.1.0).
 * <p>
 * Runs only when SDV_IT_JDBC_URL is set (CI's PostgreSQL job, or Test-SDV-SchemaUpgrade.ps1's scratch database after
 * the schema step); never against the sdv database.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test")
class SenderProfileServerContractTest {
    static final String TENANT = "TEN-PROFILE-CHECK-0001";
    static final String SLOT = "ENT-PROFILE-CHECK-0001";
    static final UUID ENTITY = EntityUuid.fromSlotId(SLOT);
    static final UUID SOURCE = UUID.fromString("5e7d0000-0000-4000-8000-000000000041");
    static final String CLIENT = "profile-check-client";
    static final String CLIENT_SECRET = "made-up-for-this-test-only";
    static final String AUDIENCE = "sdv-profile-check";

    static JdbcTemplate db;
    static JdbcTemplate database() {
        return new JdbcTemplate(new DriverManagerDataSource(System.getenv("SDV_IT_JDBC_URL"),
            System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD")));
    }
    static MockIssuer issuer;
    static HttpServer tokens;
    static ConfigurableApplicationContext vero;
    static int port;

    @BeforeAll
    static void start() throws Exception {
        db = database();
        issuer = MockIssuer.start();
        tokens = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        tokens.createContext("/token", exchange -> {
            Map<String, String> form = new HashMap<>();
            for (String pair : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
            boolean ok = "client_credentials".equals(form.get("grant_type")) && CLIENT.equals(form.get("client_id"))
                    && CLIENT_SECRET.equals(form.get("client_secret"));
            String body;
            if (ok) {
                Map<String, Object> claims = issuer.claims(AUDIENCE, Duration.ofMinutes(5));
                claims.put("sub", "service-account-" + CLIENT);
                claims.put("azp", CLIENT);
                claims.put("client_id", CLIENT);
                body = "{\"access_token\":\"" + issuer.sign(claims) + "\",\"token_type\":\"Bearer\",\"expires_in\":300}";
            } else {
                body = "{\"error\":\"invalid_client\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(ok ? 200 : 401, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        tokens.start();

        clean();
        db.update("insert into idn_customer(tenant_id, name) values (?, 'Profile check customer')", TENANT);
        db.update("insert into idn_entity(slot_id, entity_uuid, tenant_id, status, display_name, activated_by, activated_at) "
                + "values (?, ?, ?, 'ACTIVE', 'Profile check entity', 'profile-check', now())", SLOT, ENTITY, TENANT);
        db.update("insert into idn_login_connection(connection_id, tenant_id, product_code, provider_id, provider_type, "
                + "issuer_uri, api_audience, status, configuration_version) values (?, ?, 'SDV', 'customer-oidc', 'OIDC', ?, ?, "
                + "'ACTIVE', 1)", UUID.randomUUID(), TENANT, issuer.issuer().toString(), AUDIENCE);
        db.update("insert into idn_machine_binding(binding_id, tenant_id, product_code, client_id, slot_id, entity_uuid, "
                + "source_key, status, version) values (?, ?, 'SDV', ?, ?, ?, 'profile-check', 'ACTIVE', 1)",
                UUID.randomUUID(), TENANT, CLIENT, SLOT, ENTITY);
        db.update("insert into tenants(tenant_id, tenant_key, display_name, status) values (?, 'profile-check', "
                + "'Profile check entity', 'ACTIVE')", ENTITY);
        db.update("insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type) "
                + "values (?, ?, 'profile-check', 'Profile check sending system', 'ACTIVE', 'OAUTH2_CLIENT_CREDENTIALS')",
                SOURCE, ENTITY);

        Path vaultToken = Files.createTempFile("placeholder-vault-token", ".txt");
        Files.writeString(vaultToken, "placeholder-not-a-token");
        int closed;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = s.getLocalPort();
        }
        Map<String, Object> settings = new HashMap<>();
        settings.put("server.port", 0);
        settings.put("spring.datasource.url", System.getenv("SDV_IT_JDBC_URL"));
        settings.put("spring.datasource.username", System.getenv("SDV_IT_JDBC_USER"));
        settings.put("spring.datasource.password", System.getenv("SDV_IT_JDBC_PASSWORD") == null ? "" : System.getenv("SDV_IT_JDBC_PASSWORD"));
        settings.put("sdv.auth.mode", "enterprise-jwt");
        settings.put("sdv.auth.jwt.issuer", issuer.issuer().toString());
        settings.put("sdv.auth.jwt.jwk-set-uri", issuer.jwksUri().toString());
        settings.put("sdv.auth.jwt.audience", AUDIENCE);
        settings.put("sdv.crypto.key-provider", "vault");
        settings.put("sdv.crypto.vault.base-url", "http://127.0.0.1:" + closed);
        settings.put("sdv.crypto.vault.token-file", vaultToken.toString());
        // Command-line arguments, so they win over application.yml (builder properties are only defaults).
        // The real app, without the test classpath's own @Configuration classes (other tests' stacks).
        vero = new SpringApplicationBuilder(SdvApplication.class).initializers(context -> context.getBeanFactory()
                .registerSingleton("withoutTestClasses", new org.springframework.boot.context.TypeExcludeFilter() {
                    @Override
                    public boolean match(org.springframework.core.type.classreading.MetadataReader reader,
                                         org.springframework.core.type.classreading.MetadataReaderFactory factory) {
                        try {
                            return reader.getResource().getURL().toString().contains("test-classes");
                        } catch (java.io.IOException e) {
                            return false;
                        }
                    }
                })).run(settings.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
        port = Integer.parseInt(vero.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stop() {
        if (vero != null) vero.close();
        if (tokens != null) tokens.stop(0);
        if (issuer != null) issuer.close();
        clean();
    }

    static void clean() {
        if (db == null) return;
        // Task 0050: audit rows are append-only (the database refuses to delete them), so they stay, and so does the
        // entity they name. The scratch database is made new for every run.
        db.update("delete from unidentified_intake_attempts where tenant_id = ?", ENTITY);
        db.update("delete from ingestion_sources where tenant_id = ?", ENTITY);
        db.update("delete from idn_machine_binding where tenant_id = ?", TENANT);
        db.update("delete from idn_login_connection where tenant_id = ?", TENANT);
        db.update("delete from idn_entity where tenant_id = ?", TENANT);
        db.update("delete from idn_customer where tenant_id = ?", TENANT);
    }

    @Test
    void aRunningVeroAnswersAsTheProfileSays() throws Exception {
        var profile = SenderProfileContractTest.validated().profile();
        SignIn signIn = SignIn.OAuth2ClientCredentials.secret(URI.create("http://127.0.0.1:" + tokens.getAddress().getPort() + "/token"),
                CLIENT, SecretRef.of("profile-check-secret"), null);
        com.documentplatform.notifications.secret.SecretResolver secrets = ref ->
                "profile-check-secret".equals(ref.value()) ? Optional.of(Secret.of(CLIENT_SECRET)) : Optional.empty();
        // On premises, with internal addresses allowed: Vero and the login server run on this computer.
        Installation here = new Installation(false, true, false, null, null);
        List<ProfileContract.Problem> problems = ProfileContract.checkAgainstServer(profile,
                URI.create("http://127.0.0.1:" + port), signIn, secrets, here);
        assertEquals(List.of(), problems, "the sender profile against a running Vero");
    }
}
