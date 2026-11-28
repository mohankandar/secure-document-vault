package com.sdv.platform.web.notifications;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0102 (VSEC-205): Vero's secrets under secret/platform/vero/; the old place only while the login may still read it. */
class VaultSecretsTest {
    final Map<String, String> kv = new HashMap<>();
    final List<String> asked = new ArrayList<>();
    boolean narrowed;

    final VaultSecrets secrets = new VaultSecrets((mount, path) -> {
        asked.add(mount + "/" + path);
        if (narrowed && !path.startsWith("platform/vero/")) throw new CryptoException(CryptoError.CRYPTO_PERMISSION_DENIED, "denied");
        String v = kv.get(path);
        if (v == null) throw new CryptoException(CryptoError.CRYPTO_SECRET_MISSING, "missing");
        return v;
    });

    private String value(String ref) {
        return secrets.resolve(SecretRef.of(ref)).map(s -> s.reveal()).orElse(null);
    }

    @Test
    void vero_sOwnPlaceComesFirst() {
        kv.put("platform/vero/notifications/approval-link-key", "new");
        kv.put("platform/notifications/approval-link-key", "old");
        assertEquals("new", value("notifications/approval-link-key"));
        assertEquals(List.of("secret/platform/vero/notifications/approval-link-key"), asked, "the old place isn't asked");
    }

    @Test
    void anInstallFromBeforeIsFoundAtTheOldPlaceUntilTheLoginIsNarrowed() {
        kv.put("platform/notifications/mail-password", "old");
        assertEquals("old", value("notifications/mail-password"));
        narrowed = true;
        assertEquals(null, value("notifications/mail-password"), "the narrowed login may not read it: not found, never a failure");
    }

    @Test
    void theKeyServiceAwayIsAFailureNotNotFound() {
        VaultSecrets away = new VaultSecrets((mount, path) -> { throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, "away"); });
        assertThrows(IllegalStateException.class, () -> away.resolve(SecretRef.of("notifications/x")));
        VaultSecrets refusedAtNewPlace = new VaultSecrets((mount, path) -> { throw new CryptoException(CryptoError.CRYPTO_PERMISSION_DENIED, "denied"); });
        assertThrows(IllegalStateException.class, () -> refusedAtNewPlace.resolve(SecretRef.of("notifications/x")),
                "Vero's own place refused is a set-up fault, said so");
        assertTrue(new VaultSecrets(() -> null) != null);
        assertThrows(IllegalStateException.class, () -> new VaultSecrets(() -> null).resolve(SecretRef.of("notifications/x")));
    }
}
