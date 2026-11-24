package com.sdv.platform.web;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0089: the tester instance's files expose the web address only, and hold no secret. */
class TesterTunnelConfigTest {
    private static final Path FILES = ProductNameGuardTest.repositoryRoot().resolve("dev/testers");

    private static String text(String name) throws IOException {
        return Files.readString(FILES.resolve(name)).replace("\r\n", "\n");
    }

    private static Map<String, Object> yaml(String name) throws IOException {
        return new Yaml(new SafeConstructor(new LoaderOptions())).load(text(name));
    }

    @Test
    void theTunnelHasOneNameOneServiceAndThen404() throws IOException {
        Map<String, Object> tunnel = yaml("cloudflared.template.yml");
        assertEquals(Set.of("tunnel", "credentials-file", "no-autoupdate", "metrics", "ingress"), tunnel.keySet());
        assertEquals(List.of(Map.of("hostname", "__HOST__", "service", "http://127.0.0.1:8290"), Map.of("service", "http_status:404")),
                tunnel.get("ingress"));
        assertEquals("127.0.0.1:8293", tunnel.get("metrics"));
    }

    /** Comments left out. */
    private static String caddy() throws IOException {
        return text("Caddyfile").replaceAll("(?m)^\\s*#.*\n", "");
    }

    private static Set<String> all(String regex, String in) {
        Set<String> found = new TreeSet<>();
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(in);
        while (m.find()) found.add(m.group(1).trim());
        return found;
    }

    @Test
    void theProxyPassesOnOnlyVerosApiAndTheTestersSignIn() throws IOException {
        String caddy = caddy();
        assertEquals(Set.of("{$VERO_TESTERS_API:127.0.0.1:8292} {", "{$VERO_TESTERS_LOGIN:127.0.0.1:8291} {"), all("reverse_proxy (.*)$", caddy));
        assertEquals(Set.of("{$VERO_TESTERS_BIND:127.0.0.1}"), all("^\\s*bind (.*)$", caddy));
        assertEquals(Set.of("http://{$VERO_TESTERS_HOST}", "http://"), all("^(\\S*?):8290 \\{$", caddy));
        assertEquals(2, caddy.split(":8290", -1).length - 1);
        assertEquals(Set.of("{$VERO_TESTERS_DIST}"), all("root \\* (.*)$", caddy));
        assertEquals(Set.of(
                "@intake path /api/v1/ingestion /api/v1/ingestion/*",
                "@api path /api/v1/*",
                "@login path_regexp ^/(realms/vero-testers/(protocol/openid-connect/|login-actions/|\\.well-known/openid-configuration$)|resources/[A-Za-z0-9]+/(login|common)/)",
                "@screens path / /index.html /assets/*"), all("^\\s*(@\\w+ path.*)$", caddy));
        assertEquals(List.of("@intake {", "@api {", "@login {", "@screens {", "{"),
                Pattern.compile("^\\thandle (.*)$", Pattern.MULTILINE).matcher(caddy).results().map(r -> r.group(1)).toList());
        assertTrue(caddy.contains("admin off"));
        for (String never : List.of("actuator", "/admin", "master", "metrics", "health", "5432", "5433", "8200", "8280", "5183", "8181", "9000",
                "import ", "try_files", "browse", "handle_path", "rewrite")) {
            assertFalse(caddy.contains(never), never);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theLoginSystemAnswersOnThisComputerOnlyAndItsDatabaseNowhere() throws IOException {
        Map<String, Object> services = (Map<String, Object>) yaml("compose.testers.yml").get("services");
        assertEquals(Set.of("vero-testers-keycloak", "vero-testers-keycloak-db"), services.keySet());
        Map<String, Object> keycloak = (Map<String, Object>) services.get("vero-testers-keycloak");
        Map<String, Object> database = (Map<String, Object>) services.get("vero-testers-keycloak-db");
        assertEquals(List.of("127.0.0.1:8291:8080"), keycloak.get("ports"));
        assertNull(database.get("ports"));
        for (Map<String, Object> service : List.of(keycloak, database)) {
            assertNull(service.get("network_mode"));
            ((Map<String, Object>) service.get("environment")).forEach((name, value) -> {
                if (name.contains("PASSWORD")) assertTrue(String.valueOf(value).matches("\\$\\{VERO_TESTERS_KC_[A-Z_]+:\\?.*}"), name);
            });
        }
    }

    @Test
    void theRealmLetsNobodyRegisterAndHoldsNoUserOrSecret() throws IOException {
        Map<String, Object> realm = yaml("realm-vero-testers.json");
        assertEquals(false, realm.get("registrationAllowed"));
        assertEquals(true, realm.get("bruteForceProtected"));
        assertEquals(true, realm.get("revokeRefreshToken"));
        assertEquals(900, realm.get("accessTokenLifespan"));
        assertFalse(realm.containsKey("users"));
        List<?> clients = (List<?>) realm.get("clients");
        assertEquals(1, clients.size());
        Map<?, ?> client = (Map<?, ?>) clients.get(0);
        assertEquals(true, client.get("publicClient"));
        assertEquals(false, client.get("implicitFlowEnabled"));
        assertEquals(false, client.get("directAccessGrantsEnabled"));
        assertEquals(false, client.get("serviceAccountsEnabled"));
        assertFalse(client.containsKey("secret"));
        assertEquals(List.of("https://${VERO_TESTERS_HOST}/"), client.get("redirectUris"));
        assertEquals("S256", ((Map<?, ?>) client.get("attributes")).get("pkce.code.challenge.method"));
    }

    @Test
    void theTestersHaveTheirOwnCustomerAndNoPlatformAdmin() throws IOException {
        Map<String, Object> signIn = yaml("signin-vero-testers.template.json");
        assertEquals("TEN-VERO-TESTERS-0001", signIn.get("tenantId"));
        String mapping = text("role-mapping-SDV.csv").replaceAll("(?m)^#.*\n", "");
        assertFalse(mapping.contains("PLATFORM_OPERATIONS_ADMIN"));
        assertFalse(mapping.contains("*"));
        assertFalse(mapping.contains("DEMO"));
        assertEquals("client_id,entity_slot,source,description", text("client-bindings-SDV.csv").trim());
    }
}
