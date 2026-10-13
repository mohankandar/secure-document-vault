package com.sdv.platform.crypto.key;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VaultTransitKeyProtectionProviderTest {
    @Test
    void derivesDistinctTenantScopedTransitKeyNamesWithoutBusinessIdentifiers() {
        var provider = new VaultTransitKeyProtectionProvider(
                URI.create("http://127.0.0.1:8200"), "test-token", "transit", "sdv-tenant-");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String aName = provider.tenantKeyName(a);
        String bName = provider.tenantKeyName(b);
        assertNotEquals(aName, bName);
        assertTrue(aName.startsWith("sdv-tenant-"));
        assertTrue(aName.endsWith(a.toString().replace("-", "")));
    }

    @Test
    void usesVaultTransitDataKeyAndDecryptApiShapeWithoutPersistingPlaintextMasterKeys() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> paths = new ArrayList<>();
        byte[] dataKey = new byte[32];
        java.util.Arrays.fill(dataKey, (byte) 0x2a);
        String encodedKey = Base64.getEncoder().encodeToString(dataKey);
        String protectedRef = "vault:v7:opaque-protected-key";
        server.createContext("/", exchange -> respond(exchange, paths, encodedKey, protectedRef));
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var provider = new VaultTransitKeyProtectionProvider(base, "test-token", "transit", "sdv-tenant-");
            UUID tenant = UUID.randomUUID();
            String keyName = "sdv-tenant-" + tenant.toString().replace("-", "");

            ProtectedDataKey generated = provider.generateDataKey(tenant);
            assertArrayEquals(dataKey, generated.plaintextKey());
            assertEquals(protectedRef, generated.protectedReference());
            assertEquals("v7", generated.providerVersion());
            assertArrayEquals(dataKey, provider.unprotectDataKey(tenant, protectedRef));

            assertEquals(List.of(
                    "/v1/transit/keys/" + keyName,
                    "/v1/transit/datakey/plaintext/" + keyName,
                    "/v1/transit/decrypt/" + keyName), paths);
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, List<String> paths, String encodedKey, String protectedRef) throws IOException {
        paths.add(exchange.getRequestURI().getPath());
        assertEquals("test-token", exchange.getRequestHeaders().getFirst("X-Vault-Token"));
        String path = exchange.getRequestURI().getPath();
        if (path.contains("/keys/")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        String response;
        if (path.contains("/datakey/plaintext/")) {
            response = "{\"data\":{\"plaintext\":\"" + encodedKey + "\",\"ciphertext\":\"" + protectedRef + "\"}}";
        } else if (path.contains("/decrypt/")) {
            response = "{\"data\":{\"plaintext\":\"" + encodedKey + "\"}}";
        } else {
            response = "{}";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
