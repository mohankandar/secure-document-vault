import com.sun.net.httpserver.HttpServer;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Task 0038: a stand-in sign-in hub for check scripts, run as a single-file program:
 * {@code java test-tools/KeySetServer.java <port> <key file> <token file> <issuer> <audience>}.
 * <p>
 * It serves one RSA signing key as a key set (JWKS) at {@code http://127.0.0.1:<port>/certs}, so Vero's readiness
 * check of the sign-in keys passes, and writes one signed test token to the token file for a script to send. The key
 * is made on the first run and kept in the key file, so a restarted server serves the same key. Both files are for
 * this check only: the key signs nothing but the test token, which is accepted by no system but the Vero under test.
 * It listens on 127.0.0.1 only.
 */
public final class KeySetServer {
    private static final String KEY_ID = "clean-install-1";
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: java KeySetServer.java <port> <key file> <token file> <issuer> <audience>");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        KeyPair key = loadOrCreate(Path.of(args[1]));
        Path tokenFile = Path.of(args[2]);
        if (!Files.exists(tokenFile)) Files.writeString(tokenFile, token(key.getPrivate(), args[3], args[4]), StandardCharsets.US_ASCII);

        RSAPublicKey pub = (RSAPublicKey) key.getPublic();
        byte[] keySet = ("{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"" + KEY_ID + "\",\"n\":\""
                + URL.encodeToString(unsigned(pub.getModulus())) + "\",\"e\":\"" + URL.encodeToString(unsigned(pub.getPublicExponent()))
                + "\"}]}").getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
        server.createContext("/certs", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, keySet.length);
            exchange.getResponseBody().write(keySet);
            exchange.close();
        });
        server.start();
        System.out.println("key set on http://127.0.0.1:" + port + "/certs");
    }

    private static KeyPair loadOrCreate(Path file) throws Exception {
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        if (Files.exists(file)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.US_ASCII);
            PrivateKey priv = rsa.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(lines.get(0))));
            var pub = rsa.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(lines.get(1))));
            return new KeyPair(pub, priv);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Files.writeString(file, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n"
                + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n", StandardCharsets.US_ASCII);
        return pair;
    }

    private static String token(PrivateKey key, String issuer, String audience) throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + KEY_ID + "\"}";
        String payload = "{\"iss\":\"" + issuer + "\",\"aud\":\"" + audience + "\",\"sub\":\"clean-install-person\",\"iat\":"
                + (now - 5) + ",\"exp\":" + (now + 3600) + ",\"groups\":[\"vero-users\"]}";
        String signingInput = URL.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + URL.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Signature rs256 = Signature.getInstance("SHA256withRSA");
        rs256.initSign(key);
        rs256.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + URL.encodeToString(rs256.sign());
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) return java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        return bytes;
    }

    private KeySetServer() { }
}
