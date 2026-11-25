import com.documentplatform.licensing.Allowance;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.Deployment;
import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceFormat;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceType;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.TrustedKeys;
import com.documentplatform.licensing.json.Fields;
import com.documentplatform.licensing.json.Json;
import com.documentplatform.licensing.json.JsonException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Task 0093: the Java part of New-SDV-ReleaseLicenceKey.ps1 and New-SDV-Licence.ps1, run from its source with
 * platform-core's platform-licensing on the classpath (the format and the check are platform-core's own). It is in no jar.
 *
 * <pre>
 *   new-key  PRIVATE-FILE PUBLIC-FILE KEY-ID              PRIVATE-FILE is there, empty, with its rights already set
 *   publish  PUBLIC-FILE BUILD-KEYS-FILE BUILD-PROPERTIES adds the public key beside those the build trusts
 *   sign     PRIVATE-FILE KEY-ID BUILD-KEYS-FILE INPUT OUT
 * </pre>
 *
 * It never prints a key or a signature. Exit codes: 0 done, 2 refused or failed.
 */
public class VeroLicenceTool {
    static final String PRODUCT = "SDV";

    static final class Refused extends RuntimeException {
        Refused(String message) { super(message); }
    }

    public static void main(String[] args) {
        try {
            switch (args.length == 0 ? "" : args[0]) {
                case "new-key" -> { need(args, 4); newKey(Path.of(args[1]), Path.of(args[2]), args[3]); }
                case "publish" -> { need(args, 4); publish(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])); }
                case "sign" -> { need(args, 6); sign(Path.of(args[1]), args[2], Path.of(args[3]), Path.of(args[4]), Path.of(args[5])); }
                default -> throw new Refused("say new-key, publish or sign");
            }
        } catch (Refused | IllegalArgumentException e) {
            System.err.println("REFUSED: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            // Never the message of an unexpected failure: it could repeat what was read.
            System.err.println("FAILED: " + e.getClass().getSimpleName());
            System.exit(2);
        }
    }

    static void need(String[] args, int count) {
        if (args.length != count) throw new Refused(args[0] + " needs " + (count - 1) + " values");
    }

    static void newKey(Path privateFile, Path publicFile, String keyId) throws Exception {
        Licence.requireKeyId(keyId);
        if (keyId.contains(":")) throw new Refused("the key ID is a name without a version");
        if (!Files.isRegularFile(privateFile) || Files.size(privateFile) != 0) throw new Refused("the private key file must be there and empty");
        if (Files.exists(publicFile)) throw new Refused("the public key file is there already");
        KeyPair pair = KeyPairGenerator.getInstance(Ed25519.ALGORITHM).generateKeyPair();
        Files.writeString(privateFile, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()), StandardOpenOption.WRITE);
        // Read back, so that a file that can't sign is noticed now.
        if (!belongs(readPrivate(privateFile), pair.getPublic())) throw new Refused("the written key doesn't sign");
        Files.writeString(publicFile, TrustedKeys.of(Map.of(keyId, pair.getPublic())).toJson(), StandardOpenOption.CREATE_NEW);
        System.out.println("Made the key " + keyId + ".");
    }

    static void publish(Path publicFile, Path buildKeys, Path buildProperties) throws Exception {
        TrustedKeys fresh = TrustedKeys.parse(Files.readString(publicFile));
        Map<String, PublicKey> all = new HashMap<>();
        if (Files.exists(buildKeys)) {
            TrustedKeys there = TrustedKeys.parse(Files.readString(buildKeys));
            for (String id : there.keyIds()) {
                if (there.isDevelopmentKey(id)) throw new Refused("the release build lists the development key " + id);
                all.put(id, there.key(id).orElseThrow());
            }
        }
        for (String id : fresh.keyIds()) {
            if (fresh.isDevelopmentKey(id)) throw new Refused(id + " is a development key");
            if (all.containsKey(id)) throw new Refused("the release build lists the key " + id + " already; it is never replaced");
            all.put(id, fresh.key(id).orElseThrow());
        }
        if (Files.exists(buildProperties) && Files.readString(buildProperties).contains("development-keys")) {
            throw new Refused(buildProperties.getFileName() + " of the release build names development-keys");
        }
        Files.createDirectories(buildKeys.toAbsolutePath().getParent());
        Files.writeString(buildKeys, TrustedKeys.of(all).toJson());
        if (!Files.exists(buildProperties)) Files.writeString(buildProperties, "built-at=${maven.build.timestamp}\n");
        System.out.println("The release build trusts: " + String.join(", ", new TreeSet<>(all.keySet())));
    }

    static void sign(Path privateFile, String keyId, Path buildKeys, Path input, Path out) throws Exception {
        if (Files.exists(out)) throw new Refused("the licence file is there already; it is never overwritten");
        if (!Files.exists(buildKeys)) throw new Refused("the release build has no licence keys yet");
        TrustedKeys trusted = TrustedKeys.parse(Files.readString(buildKeys));
        PublicKey expected = trusted.key(keyId).orElseThrow(() -> new Refused("the release build doesn't trust the key " + keyId));
        if (trusted.isDevelopmentKey(keyId)) throw new Refused(keyId + " is a development key");
        String given = Files.readString(input);
        Licence licence = read(given.startsWith("﻿") ? given.substring(1) : given, keyId);
        PrivateKey key = readPrivate(privateFile);
        if (!belongs(key, expected)) throw new Refused("the private key isn't the one the release build trusts as " + keyId);
        Map<String, Object> payload = LicenceFormat.payload(licence);
        byte[] message = LicenceFormat.signedMessage(payload);
        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("algorithm", Ed25519.ALGORITHM);
        signature.put("payloadSha256", Ed25519.sha256Hex(message));
        signature.put("value", Base64.getEncoder().encodeToString(signed(key, message)));
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("format", LicenceFormat.FORMAT);
        file.put("licence", payload);
        file.put("signature", signature);
        String text = Json.pretty(file);
        // The check every installation makes, with the keys the release build trusts.
        new LicenceVerifier(trusted).verify(text, licence.tenantId());
        Files.writeString(out, text, StandardOpenOption.CREATE_NEW);
        System.out.println("Licence " + licence.licenceId() + " version " + licence.version() + " for " + licence.tenantId() + ", key " + keyId + ".");
        licence.entitySlots().forEach(s -> System.out.println("  entity " + s.slotId()));
    }

    /** The small input file. A field it doesn't know (a price, for example) refuses the whole file. */
    static Licence read(String text, String keyId) {
        try {
            Fields in = Fields.of(Json.parse(text), "input")
                    .only(Set.of("customer", "tenantId", "version", "kind", "deployment", "startsOn", "expiresOn", "entities"));
            boolean first = in.integer("version") == 1;
            String tenant = id(in.string("tenantId"), "TEN", first);
            List<EntitySlot> slots = new ArrayList<>();
            for (Object o : in.list("entities")) {
                Fields e = Fields.of(o, "entities[]").only(Set.of("slotId", "storageGb", "concurrentSeats", "namedSeats"));
                slots.add(new EntitySlot(id(e.string("slotId"), "ENT", true), List.of(
                        new Allowance(PRODUCT, AllowanceType.STORAGE, e.integer("storageGb")),
                        new Allowance(PRODUCT, AllowanceType.CONCURRENT_SEATS, e.integer("concurrentSeats")),
                        new Allowance(PRODUCT, AllowanceType.NAMED_SEATS, e.integer("namedSeats")))));
            }
            LocalDate starts = date(in.string("startsOn"), "startsOn");
            LocalDate ends = date(in.string("expiresOn"), "expiresOn");
            LicenceKind kind = one(LicenceKind.class, in.string("kind"), "kind");
            if (ends.isBefore(LocalDate.now(ZoneOffset.UTC))) throw new Refused("expiresOn is in the past");
            if (kind == LicenceKind.INSTALL && ChronoUnit.DAYS.between(starts, ends) + 1 > 60) throw new Refused("an INSTALL licence has at most 60 days");
            return new Licence(id("new", "LIC", true), in.integer("version"), in.string("customer"), tenant, List.of(PRODUCT), slots,
                    LicenceType.SUBSCRIPTION, kind, starts, ends, null, one(Deployment.class, in.string("deployment"), "deployment"), keyId,
                    Instant.now().truncatedTo(ChronoUnit.SECONDS));
        } catch (JsonException e) {
            throw new Refused("the input file: " + e.getMessage());
        }
    }

    static <E extends Enum<E>> E one(Class<E> type, String text, String field) {
        for (E e : type.getEnumConstants()) if (e.name().equals(text)) return e;
        throw new Refused(field + " must be one of " + List.of(type.getEnumConstants()));
    }

    static LocalDate date(String text, String field) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeException e) {
            throw new Refused(field + " must be a date such as 2027-11-30");
        }
    }

    /** "new" makes an ID where that is allowed; a customer keeps its own in every later version. */
    static String id(String given, String prefix, boolean mayBeNew) {
        if (!given.equals("new")) {
            if (!given.startsWith(prefix + "-")) throw new Refused("an ID here starts with " + prefix + "- (or is new)");
            return given;
        }
        if (!mayBeNew) throw new Refused("from version 2 on, the customer keeps its tenantId");
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        SecureRandom random = new SecureRandom();
        StringBuilder id = new StringBuilder(prefix);
        for (int i = 0; i < 16; i++) {
            if (i % 4 == 0) id.append('-');
            id.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return id.toString();
    }

    static PrivateKey readPrivate(Path file) throws Exception {
        try {
            byte[] der = Base64.getDecoder().decode(Files.readString(file, StandardCharsets.ISO_8859_1).strip());
            return KeyFactory.getInstance(Ed25519.ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new Refused("the private key file isn't one this tool wrote");
        }
    }

    static byte[] signed(PrivateKey key, byte[] message) throws Exception {
        Signature s = Signature.getInstance(Ed25519.ALGORITHM);
        s.initSign(key);
        s.update(message);
        return s.sign();
    }

    static boolean belongs(PrivateKey key, PublicKey expected) throws Exception {
        byte[] probe = "vero-licence-key-check".getBytes(StandardCharsets.US_ASCII);
        Signature s = Signature.getInstance(Ed25519.ALGORITHM);
        s.initVerify(expected);
        s.update(probe);
        return s.verify(signed(key, probe));
    }
}
