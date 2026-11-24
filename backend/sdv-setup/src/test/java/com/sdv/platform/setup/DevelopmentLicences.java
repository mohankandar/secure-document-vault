package com.sdv.platform.setup;

import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceFormat;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Task 0057: test licences signed with a throw-away key. Since platform-core 0.6.0 the signing side is its own module
 * ({@code platform-licensing-issue}), which Vero does not take (no new dependency). The JDK signs instead, over exactly
 * the bytes platform-core's verifier checks ({@link LicenceFormat#signedMessage}); every test that uses it verifies
 * the result with platform-core's {@code LicenceVerifier}. The twin in sdv-web's tests is
 * {@code com.sdv.platform.application.identity.DevelopmentLicences}.
 */
public final class DevelopmentLicences {
    private DevelopmentLicences() { }

    public static KeyPair newKey() {
        try {
            return KeyPairGenerator.getInstance(Ed25519.ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The signed licence document ({@value LicenceFormat#FORMAT}). */
    public static String sign(Licence licence, PrivateKey key) {
        byte[] message = LicenceFormat.signedMessage(LicenceFormat.payload(licence));
        String text = new String(message, StandardCharsets.UTF_8);
        // The signed message is the format line, a line break, then the licence's canonical JSON.
        String header = LicenceFormat.FORMAT + "\n";
        if (!text.startsWith(header)) throw new IllegalStateException("unexpected signed message layout");
        String canonical = text.substring(header.length());
        try {
            Signature signer = Signature.getInstance(Ed25519.ALGORITHM);
            signer.initSign(key);
            signer.update(message);
            String value = Base64.getEncoder().encodeToString(signer.sign());
            return "{\"format\":\"" + LicenceFormat.FORMAT + "\",\"licence\":" + canonical + ",\"signature\":{\"algorithm\":\""
                    + Ed25519.ALGORITHM + "\",\"payloadSha256\":\"" + Ed25519.sha256Hex(message) + "\",\"value\":\"" + value + "\"}}";
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
