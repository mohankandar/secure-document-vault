package com.sdv.platform.web.notifications;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.vault.VaultClient;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Task 0081: the secrets of notifications (the mail password or Microsoft 365 credential, Teams and Slack webhook
 * addresses, the approval link key) from the key service's KV store: {@code secret/data/platform/<reference>}, key
 * {@code value}, which Vero's login may read (policy of Setup-SDV-VaultTransit.ps1). Read at every send and never
 * kept. Nothing in the KV store under the reference: empty (a permanent failure, SECRET_NOT_FOUND). The key service
 * away, or not set up in this install: an exception (temporary, SECRET_UNAVAILABLE). The reference and the value never
 * go into a log here.
 */
public class VaultSecrets implements SecretResolver {
    public static final String MOUNT = "secret";
    public static final String PREFIX = "platform/";
    public static final String KEY = "value";

    private final Supplier<VaultClient> vault;

    public VaultSecrets(Supplier<VaultClient> vault) {
        this.vault = vault;
    }

    @Override
    public Optional<Secret> resolve(SecretRef ref) {
        VaultClient client = vault.get();
        if (client == null) throw new IllegalStateException("No key service in this install");
        try {
            String value = client.readSecret(MOUNT, PREFIX + ref.value()).get(KEY);
            return value == null || value.isEmpty() ? Optional.empty() : Optional.of(Secret.of(value));
        } catch (CryptoException ex) {
            if (ex.code() == CryptoError.CRYPTO_SECRET_MISSING) return Optional.empty();
            throw new IllegalStateException("Key service did not answer: " + ex.code());
        }
    }
}
