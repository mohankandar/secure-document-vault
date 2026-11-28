package com.sdv.platform.web.notifications;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.spring.NotificationsProperties;
import com.sdv.platform.domain.error.StartupFailure;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0102 (VSEC-205, answer Q-6): Vero's secrets only under secret/platform/vero/; a missing one stops the start. */
class VaultSecretsTest {
    final Map<String, String> kv = new HashMap<>();
    final List<String> asked = new ArrayList<>();

    final VaultSecrets secrets = new VaultSecrets((mount, path) -> {
        asked.add(mount + "/" + path);
        String v = kv.get(path);
        if (v == null) throw new CryptoException(CryptoError.CRYPTO_SECRET_MISSING, "missing");
        return v;
    });

    private String value(String ref) {
        return secrets.resolve(SecretRef.of(ref)).map(s -> s.reveal()).orElse(null);
    }

    @Test
    void onlyVero_sOwnPlaceIsRead() {
        kv.put("platform/vero/notifications/approval-link-key", "new");
        kv.put("platform/notifications/mail-password", "old");
        assertEquals("new", value("notifications/approval-link-key"));
        assertEquals(null, value("notifications/mail-password"), "the old place is never read");
        assertEquals(List.of("secret/platform/vero/notifications/approval-link-key", "secret/platform/vero/notifications/mail-password"), asked);
    }

    @Test
    void theKeyServiceAwayIsAFailureNotNotFound() {
        VaultSecrets away = new VaultSecrets((mount, path) -> { throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, "away"); });
        assertThrows(IllegalStateException.class, () -> away.resolve(SecretRef.of("notifications/x")));
        VaultSecrets denied = new VaultSecrets((mount, path) -> { throw new CryptoException(CryptoError.CRYPTO_PERMISSION_DENIED, "denied"); });
        assertThrows(IllegalStateException.class, () -> denied.resolve(SecretRef.of("notifications/x")));
        assertThrows(IllegalStateException.class, () -> new VaultSecrets(() -> null).resolve(SecretRef.of("notifications/x")));
    }

    @Test
    void theStartStopsWhenAConfiguredSecretIsMissingAndNamesOnlyTheSetting() {
        kv.put("platform/vero/notifications/approval-link-key", "k");
        Map<String, String> named = new LinkedHashMap<>();
        named.put("platform.notifications.approvals.link-key-secret", "notifications/approval-link-key");
        VeroSecretsCheck.check(named, secrets);
        named.put("platform.notifications.teams.ops", "notifications/teams-ops-webhook");
        StartupFailure stop = assertThrows(StartupFailure.class, () -> VeroSecretsCheck.check(named, secrets));
        assertEquals("SDV-SYS-007", stop.errorCode().code());
        assertTrue(stop.getMessage().contains("platform.notifications.teams.ops"), stop.getMessage());
        assertFalse(stop.getMessage().contains("teams-ops-webhook"), "never the path");
    }

    @Test
    void theStartGoesOnWhenTheKeyServiceCantBeAsked() {
        VaultSecrets away = new VaultSecrets((mount, path) -> { throw new CryptoException(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE, "away"); });
        VeroSecretsCheck.check(Map.of("platform.notifications.approvals.link-key-secret", "notifications/k"), away);
    }

    @Test
    void everySecretTheConfigNamesIsChecked() {
        NotificationsProperties c = new NotificationsProperties();
        c.getMail().setPasswordSecret("notifications/mail");
        c.getTeams().put("ops", "notifications/teams-ops");
        c.getSlack().put("it", "notifications/slack-it");
        c.getApprovals().setLinkKeySecret("notifications/approval-link-key");
        assertEquals(List.of("platform.notifications.mail.password-secret", "platform.notifications.teams.ops",
                "platform.notifications.slack.it", "platform.notifications.approvals.link-key-secret"), List.copyOf(VeroSecretsCheck.named(c).keySet()));
        assertTrue(VeroSecretsCheck.named(new NotificationsProperties()).isEmpty(), "nothing configured, nothing checked");
    }
}
