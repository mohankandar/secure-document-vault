package com.sdv.platform.web.crypto;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.persistence.fields.BatchUnwrap;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Task 0078: the data keys of a page of results, opened with one call to Vault Transit's decrypt ({@code batch_input}): the same address,
 * login, mount, key and policy as platform-crypto's own calls. It goes when platform-crypto can open many keys in one call itself.
 * Nothing it throws or logs holds a token, a key or Vault's answer.
 */
final class VaultBatchUnwrap implements BatchUnwrap, AutoCloseable {
    /** The settings of {@code platform.crypto.vault.*} (checked by platform-crypto at start). */
    record Settings(String address, String namespace, String login, String token, String tokenFile, String appRoleMount, String roleId, String secretId,
                    String secretIdFile, String transitMount, Duration timeout) {
        @Override
        public String toString() {
            return "Settings[" + address + "]";
        }
    }

    private final Settings settings;
    private final java.util.function.Function<CustomerId, String> keyName;
    private final HttpClient http;
    private String token;
    private Instant tokenUntil = Instant.EPOCH;

    VaultBatchUnwrap(Settings settings, java.util.function.Function<CustomerId, String> keyName) {
        this.settings = settings;
        this.keyName = keyName;
        this.http = HttpClient.newBuilder().connectTimeout(settings.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Map<String, byte[]> unwrap(CustomerId customer, Collection<String> wrapped) {
        List<String> order = List.copyOf(wrapped);
        if (order.isEmpty()) return Map.of();
        String body = VeroJson.mapper().writeValueAsString(Map.of("batch_input", order.stream().map(w -> Map.of("ciphertext", w)).toList()));
        String path = settings.transitMount() + "/decrypt/" + keyName.apply(customer);
        HttpResponse<byte[]> answer = post(path, body, token(false));
        // A login that ended meanwhile: once more with a new one, as platform-crypto does.
        if (answer.statusCode() == 403 && !"TOKEN".equals(settings.login())) answer = post(path, body, token(true));
        if (answer.statusCode() == 403) throw new CryptoException(CryptoError.CRYPTO_PERMISSION_DENIED, "the batch was refused");
        if (answer.statusCode() == 503) throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_SEALED, "the key service isn't ready");
        // 400: at least one key could not be opened; the others are in the same answer.
        if (answer.statusCode() != 200 && answer.statusCode() != 400) throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "HTTP " + answer.statusCode());
        JsonNode results = read(answer.body()).path("data").path("batch_results");
        if (!results.isArray() || results.size() != order.size()) throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "an answer that isn't the batch's");
        Map<String, byte[]> out = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            JsonNode plain = results.get(i).path("plaintext");
            if (!plain.isString() || plain.asString().isEmpty()) continue;
            try {
                out.put(order.get(i), Base64.getDecoder().decode(plain.asString()));
            } catch (IllegalArgumentException ex) {
                // left out
            }
        }
        return out;
    }

    private synchronized String token(boolean again) {
        if (!again && token != null && Instant.now().isBefore(tokenUntil)) return token;
        try {
            switch (settings.login()) {
                case "TOKEN" -> {
                    token = settings.token();
                    tokenUntil = Instant.MAX;
                }
                case "TOKEN_FILE" -> {
                    token = Files.readString(Path.of(settings.tokenFile())).strip();
                    tokenUntil = Instant.now().plusSeconds(60);
                }
                default -> appRole();
            }
        } catch (IOException ex) {
            throw new CryptoException(CryptoError.CRYPTO_LOGIN_REFUSED, "the login file can't be read");
        }
        if (token == null || token.isBlank()) throw new CryptoException(CryptoError.CRYPTO_LOGIN_REFUSED, "no login");
        return token;
    }

    private void appRole() throws IOException {
        String secret = settings.secretId() != null && !settings.secretId().isBlank() ? settings.secretId() : Files.readString(Path.of(settings.secretIdFile())).strip();
        Map<String, String> login = new LinkedHashMap<>();
        login.put("role_id", settings.roleId());
        login.put("secret_id", secret);
        HttpResponse<byte[]> answer = post("auth/" + settings.appRoleMount() + "/login", VeroJson.mapper().writeValueAsString(login), null);
        if (answer.statusCode() != 200) throw new CryptoException(CryptoError.CRYPTO_LOGIN_REFUSED, "HTTP " + answer.statusCode());
        JsonNode auth = read(answer.body()).path("auth");
        token = auth.path("client_token").asString("");
        long seconds = auth.path("lease_duration").asLong(0);
        tokenUntil = seconds <= 0 ? Instant.MAX : Instant.now().plusSeconds(Math.max(1, seconds * 4 / 5));
    }

    private HttpResponse<byte[]> post(String path, String body, String login) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(settings.address().replaceAll("/+$", "") + "/v1/" + path)).timeout(settings.timeout())
                .header("Content-Type", "application/json").header("X-Vault-Request", "true").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (settings.namespace() != null && !settings.namespace().isBlank()) request.header("X-Vault-Namespace", settings.namespace());
        if (login != null) request.header("X-Vault-Token", login);
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException ex) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, "the key service doesn't answer");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, "interrupted");
        }
    }

    private static JsonNode read(byte[] body) {
        try {
            return VeroJson.mapper().readTree(body);
        } catch (RuntimeException ex) {
            throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_ERROR, "an answer that can't be read");
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
