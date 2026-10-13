import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** Local acceptance helper only. Never used by SDV runtime. */
public final class Unit21JwtTestIssuer {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private final KeyPair key1;
    private final KeyPair key2;
    private final String issuer;
    private volatile boolean rotated;

    private Unit21JwtTestIssuer(int port) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.key1 = generator.generateKeyPair();
        this.key2 = generator.generateKeyPair();
        this.issuer = "http://127.0.0.1:" + port;
    }

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 18081 : Integer.parseInt(args[0]);
        Unit21JwtTestIssuer app = new Unit21JwtTestIssuer(port);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/jwks", app::jwks);
        server.createContext("/token", app::token);
        server.createContext("/rotate", app::rotate);
        server.createContext("/health", exchange -> respond(exchange, 200, "text/plain", "UP"));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Unit21 JWT test issuer READY " + app.issuer);
    }

    private void jwks(HttpExchange exchange) throws IOException {
        String keys = jwk("unit21-key-1", (RSAPublicKey) key1.getPublic());
        if (rotated) keys += "," + jwk("unit21-key-2", (RSAPublicKey) key2.getPublic());
        respond(exchange, 200, "application/json", "{\"keys\":[" + keys + "]}");
    }

    private void rotate(HttpExchange exchange) throws IOException {
        rotated = true;
        respond(exchange, 200, "application/json", "{\"rotated\":true}");
    }

    private void token(HttpExchange exchange) throws IOException {
        try {
            Map<String, String> query = parseQuery(exchange.getRequestURI());
            String kind = query.getOrDefault("kind", "user");
            String tenant = query.getOrDefault("tenant", "11111111-1111-1111-1111-111111111111");
            String source = query.getOrDefault("source", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            String roles = query.getOrDefault("roles", "BUSINESS_USER");
            String key = query.getOrDefault("key", "1");
            String audience = kind.equals("bad-audience") ? "wrong-api" : "sdv-api";
            String tokenIssuer = kind.equals("bad-issuer") ? issuer + "/wrong" : issuer;
            long now = Instant.now().getEpochSecond();
            long exp = kind.equals("expired") ? now - 30 : now + 300;

            StringBuilder claims = new StringBuilder();
            claims.append("\"iss\":").append(json(tokenIssuer));
            claims.append(",\"aud\":").append(json(audience));
            claims.append(",\"sub\":").append(json(kind.startsWith("source") ? "unit21-client" : "unit21-user"));
            claims.append(",\"iat\":").append(now - 5);
            claims.append(",\"exp\":").append(exp);
            if (!kind.equals("missing-tenant")) {
                claims.append(",\"sdv_tenant_id\":").append(json(tenant));
            }
            switch (kind) {
                case "source" -> claims.append(",\"sdv_source_id\":").append(json(source));
                case "both" -> {
                    claims.append(",\"sdv_source_id\":").append(json(source));
                    claims.append(",\"sdv_roles\":").append(jsonArray(roles));
                }
                case "unknown-role" -> claims.append(",\"sdv_roles\":[\"SUPER_ADMIN\"]");
                default -> claims.append(",\"sdv_roles\":").append(jsonArray(roles));
            }

            KeyPair pair = key.equals("2") ? key2 : key1;
            String kid = key.equals("2") ? "unit21-key-2" : "unit21-key-1";
            String jwt = sign(kid, "{" + claims + "}", pair);
            respond(exchange, 200, "text/plain", jwt);
        } catch (Exception ex) {
            respond(exchange, 500, "text/plain", "fixture failure");
        }
    }

    private static String sign(String kid, String payload, KeyPair pair) throws Exception {
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":" + json(kid) + "}";
        String signingInput = b64(header.getBytes(StandardCharsets.UTF_8)) + "." +
                b64(payload.getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(pair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + b64(signature.sign());
    }

    private static String jwk(String kid, RSAPublicKey key) {
        return "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":" + json(kid)
                + ",\"n\":" + json(unsigned(key.getModulus().toByteArray()))
                + ",\"e\":" + json(unsigned(key.getPublicExponent().toByteArray())) + "}";
    }

    private static String unsigned(byte[] value) {
        int offset = value.length > 1 && value[0] == 0 ? 1 : 0;
        byte[] normalized = new byte[value.length - offset];
        System.arraycopy(value, offset, normalized, 0, normalized.length);
        return b64(normalized);
    }

    private static String jsonArray(String commaSeparated) {
        String[] values = commaSeparated.split(",");
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(json(values[i].trim()));
        }
        return out.append(']').toString();
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String b64(byte[] value) {
        return B64.encodeToString(value);
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) return result;
        for (String item : raw.split("&")) {
            String[] parts = item.split("=", 2);
            result.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
        }
        return result;
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
