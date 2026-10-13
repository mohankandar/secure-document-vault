package com.sdv.platform.crypto.key;

import com.fasterxml.jackson.databind.JsonNode;
import com.sdv.platform.application.port.KeyServiceHealthProbe;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class VaultTransitKeyProtectionProvider implements KeyProtectionProvider, KeyServiceHealthProbe {
    public static final String PROVIDER_ID = "VAULT_TRANSIT";
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private final URI baseUri;
    private final String token;
    private final String mount;
    private final String keyPrefix;
    private final HttpClient client;
    private final ObjectMapper json;

    public VaultTransitKeyProtectionProvider(URI baseUri, String token, String mount, String keyPrefix) {
        this(baseUri, token, mount, keyPrefix,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), new ObjectMapper());
    }

    VaultTransitKeyProtectionProvider(URI baseUri,
                                      String token,
                                      String mount,
                                      String keyPrefix,
                                      HttpClient client,
                                      ObjectMapper json) {
        if (baseUri == null) throw new IllegalArgumentException("Vault base URI is required.");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Vault token is required.");
        if (!SAFE_SEGMENT.matcher(mount).matches()) throw new IllegalArgumentException("Vault Transit mount name is invalid.");
        if (!SAFE_SEGMENT.matcher(keyPrefix).matches()) throw new IllegalArgumentException("Vault tenant key prefix is invalid.");
        this.baseUri = URI.create(baseUri.toString().replaceAll("/+$", ""));
        this.token = token;
        this.mount = mount;
        this.keyPrefix = keyPrefix;
        this.client = client;
        this.json = json;
    }


    @Override
    public Health check() {
        java.time.Instant observedAt = java.time.Instant.now();
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/v1/sys/health"))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-Vault-Token", token)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            boolean available = code == 200 || code == 429 || code == 472 || code == 473;
            return new Health(available ? Status.UP : Status.DOWN, PROVIDER_ID,
                    available ? "Vault Transit service is reachable." : "Vault Transit health check returned HTTP " + code + ".",
                    observedAt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Health(Status.DOWN, PROVIDER_ID, "Vault Transit health check was interrupted.", observedAt);
        } catch (Exception ex) {
            return new Health(Status.DOWN, PROVIDER_ID, "Vault Transit service is unreachable.", observedAt);
        }
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public ProtectedDataKey generateDataKey(UUID tenantId) throws IOException {
        String keyName = tenantKeyName(tenantId);
        // Key creation is idempotent in Transit. The credential used by a production deployment
        // can instead pre-provision keys and omit create permission in a future stricter policy.
        post("/v1/" + mount + "/keys/" + keyName, Map.of());
        JsonNode response = post("/v1/" + mount + "/datakey/plaintext/" + keyName, Map.of("bits", 256));
        JsonNode data = response.path("data");
        String plaintext = requiredText(data, "plaintext");
        String ciphertext = requiredText(data, "ciphertext");
        byte[] key = Base64.getDecoder().decode(plaintext);
        if (key.length != 32) throw new IOException("Vault returned an unexpected data-key length.");
        return new ProtectedDataKey(key, ciphertext, PROVIDER_ID, providerVersion(ciphertext));
    }

    @Override
    public byte[] unprotectDataKey(UUID tenantId, String protectedReference) throws IOException {
        if (protectedReference == null || !protectedReference.startsWith("vault:v")) {
            throw new IOException("Protected data-key reference is not a Vault Transit ciphertext.");
        }
        String keyName = tenantKeyName(tenantId);
        JsonNode response = post("/v1/" + mount + "/decrypt/" + keyName, Map.of("ciphertext", protectedReference));
        byte[] key = Base64.getDecoder().decode(requiredText(response.path("data"), "plaintext"));
        if (key.length != 32) throw new IOException("Vault returned an unexpected unprotected data-key length.");
        return key;
    }

    String tenantKeyName(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required.");
        return keyPrefix + tenantId.toString().replace("-", "");
    }

    private JsonNode post(String path, Object body) throws IOException {
        try {
            String payload = json.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-Vault-Token", token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Vault Transit request failed with HTTP " + response.statusCode() + ".");
            }
            if (response.body() == null || response.body().isBlank()) return json.createObjectNode();
            return json.readTree(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Vault Transit request was interrupted.", ex);
        }
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IOException("Vault Transit response did not contain required field '" + field + "'.");
        }
        return value.asText();
    }

    private static String providerVersion(String ciphertext) {
        String[] parts = ciphertext.split(":", 3);
        return parts.length >= 2 ? parts[1] : "unknown";
    }
}
