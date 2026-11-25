package com.sdv.platform.web.crypto;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.vault.VaultAuth;
import com.documentplatform.crypto.vault.VaultClient;
import com.documentplatform.crypto.vault.VaultKeyService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0078: a page's keys in one call, with platform-crypto's own login, key and policy; the last test against a real Vault when one is given. */
class VaultBatchUnwrapTest {
    private static final CustomerId CUSTOMER = CustomerId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    private final List<String> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private HttpServer server;
    private int status = 200;
    private String answer = "{}";
    private int refuseOnce;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            seen.add(exchange.getRequestMethod() + " " + path + " " + exchange.getRequestHeaders().getFirst("X-Vault-Token"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int code = path.endsWith("/login") ? 200 : refuseOnce-- > 0 ? 403 : status;
            byte[] out = (path.endsWith("/login") ? "{\"auth\":{\"client_token\":\"token-" + seen.size() + "\",\"lease_duration\":3600}}" : answer).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private VaultBatchUnwrap unwrap(String login) {
        return new VaultBatchUnwrap(new VaultBatchUnwrap.Settings("http://localhost:" + server.getAddress().getPort() + "/", null, login, "own-token", null, "approle", "role", "very-secret", null,
                "vero-transit", Duration.ofSeconds(5)), c -> "customer-" + c.hex());
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void manyKeysAreOneCallAndOneThatCantBeOpenedIsLeftOut() {
        status = 400;
        answer = "{\"data\":{\"batch_results\":[{\"plaintext\":\"" + b64("one") + "\"},{\"error\":\"nope\"},{\"plaintext\":\"" + b64("three") + "\"}]}}";
        try (VaultBatchUnwrap batch = unwrap("TOKEN")) {
            Map<String, byte[]> opened = batch.unwrap(CUSTOMER, List.of("vault:v1:a", "vault:v1:b", "vault:v2:c"));
            assertEquals(2, opened.size());
            assertEquals("one", text(opened.get("vault:v1:a")));
            assertEquals("three", text(opened.get("vault:v2:c")));
        }
        assertEquals(List.of("POST /v1/vero-transit/decrypt/customer-" + CUSTOMER.hex() + " own-token"), seen);
        assertTrue(bodies.getFirst().contains("vault:v1:b"));
    }

    @Test
    void nothingToOpenIsNoCall() {
        try (VaultBatchUnwrap batch = unwrap("TOKEN")) {
            assertEquals(Map.of(), batch.unwrap(CUSTOMER, List.of()));
        }
        assertEquals(List.of(), seen);
    }

    @Test
    void anAppRoleLogsInOnceAndOnceMoreWhenItsTokenIsRefused() {
        answer = "{\"data\":{\"batch_results\":[{\"plaintext\":\"" + b64("k") + "\"}]}}";
        try (VaultBatchUnwrap batch = unwrap("APPROLE")) {
            batch.unwrap(CUSTOMER, List.of("w"));
            batch.unwrap(CUSTOMER, List.of("w"));
            assertEquals(3, seen.size(), "one login, two calls");
            refuseOnce = 1;
            assertEquals("k", text(batch.unwrap(CUSTOMER, List.of("w")).get("w")));
            assertEquals(6, seen.size(), "refused, a new login, the call again");
            refuseOnce = 2;
            assertEquals(CryptoError.CRYPTO_PERMISSION_DENIED, assertThrows(CryptoException.class, () -> batch.unwrap(CUSTOMER, List.of("w"))).code());
        }
    }

    @Test
    void anAnswerThatIsntTheBatchsIsRefusedAndSaysNothingSecret() {
        try (VaultBatchUnwrap batch = unwrap("APPROLE")) {
            for (String wrong : List.of("", "not json", "{}", "{\"data\":{\"batch_results\":[]}}", "{\"data\":{\"batch_results\":[{\"plaintext\":\"a\"},{\"plaintext\":\"a\"}]}}")) {
                answer = wrong;
                CryptoException ex = assertThrows(CryptoException.class, () -> batch.unwrap(CUSTOMER, List.of("w")), wrong);
                assertEquals(CryptoError.CRYPTO_KEY_SERVICE_ERROR, ex.code());
                assertFalse((ex.getMessage() + ex.reason()).matches("(?s).*(very-secret|token-|not json).*"));
            }
            status = 503;
            assertEquals(CryptoError.CRYPTO_KEY_SERVICE_SEALED, assertThrows(CryptoException.class, () -> batch.unwrap(CUSTOMER, List.of("w"))).code());
            server.stop(0);
            assertEquals(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, assertThrows(CryptoException.class, () -> batch.unwrap(CUSTOMER, List.of("w"))).code());
        }
        assertFalse(new VaultBatchUnwrap.Settings("a", null, "TOKEN", "own-token", null, "approle", "role", "very-secret", null, "m", Duration.ofSeconds(1)).toString().contains("secret"));
    }

    /** The scratch Vault of the check scripts (Use-SdvScratchVault.ps1; its token in SDV_IT_VAULT_TOKEN): what platform-crypto wrapped, the batch opens. */
    @Test
    @EnabledIfEnvironmentVariable(named = "SDV_IT_VAULT_URL", matches = "http://127[.]0[.]0[.]1:8210")
    void whatPlatformCryptoWrappedIsOpenedByOneRealCall() {
        String url = System.getenv("SDV_IT_VAULT_URL"), token = System.getenv("SDV_IT_VAULT_TOKEN");
        try (VaultClient client = new VaultClient(URI.create(url), VaultAuth.token(token))) {
            VaultKeyService keys = new VaultKeyService(client, "vero-transit", VaultKeyService.DEFAULT_PREFIX);
            CustomerId customer = CustomerId.of(UUID.randomUUID());
            keys.createKeys(customer);
            Map<String, byte[]> made = new LinkedHashMap<>();
            for (int i = 0; i < 50; i++) {
                try (var key = keys.newDataKey(customer)) {
                    made.put(key.wrapped(), key.key().clone());
                }
            }
            try (VaultBatchUnwrap batch = new VaultBatchUnwrap(new VaultBatchUnwrap.Settings(url, null, "TOKEN", token, null, null, null, null, null, "vero-transit", Duration.ofSeconds(10)), keys::wrappingKeyName)) {
                Map<String, byte[]> opened = batch.unwrap(customer, made.keySet());
                assertEquals(50, opened.size());
                made.forEach((wrapped, key) -> assertArrayEquals(key, opened.get(wrapped)));
                assertEquals(0, batch.unwrap(customer, List.of("vault:v1:AAAA")).size());
            }
        }
    }
}
