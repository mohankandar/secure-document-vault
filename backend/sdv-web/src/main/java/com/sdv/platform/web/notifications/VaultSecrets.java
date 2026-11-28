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
 * addresses, the approval link key) from the key service's KV store: {@code secret/data/platform/vero/<reference>} (task 0102), key
 * {@code value}, which Vero's login may read (policy of Setup-SDV-VaultTransit.ps1). Read at every send and never
 * kept. Nothing in the KV store under the reference: empty (a permanent failure, SECRET_NOT_FOUND). The key service
 * away, or not set up in this install: an exception (temporary, SECRET_UNAVAILABLE). The reference and the value never
 * go into a log here.
 * <p>
 * Task 0102 (VSEC-205, answer Q-6): Vero's own place, {@code secret/platform/vero/<reference>}, and nowhere else; its login
 * may read nothing else. {@link VeroSecretsCheck} stops the start when a configured secret isn't there.
 */
public class VaultSecrets implements SecretResolver {
    public static final String MOUNT = "secret";
    /** Task 0102: Vero's own place. */
    public static final String PREFIX = "platform/vero/";
    public static final String KEY = "value";

    /** One read of the KV store: the value under {@link #KEY}, null when it has none; a CryptoException when it can't. */
    interface Reader {
        String read(String mount, String path);
    }

    private final Supplier<Reader> reader;

    public VaultSecrets(Supplier<VaultClient> vault) {
        this.reader = () -> {
            VaultClient client = vault.get();
            return client == null ? null : (mount, path) -> client.readSecret(mount, path).get(KEY);
        };
    }

    VaultSecrets(Reader reader) {
        this.reader = () -> reader;
    }

    @Override
    public Optional<Secret> resolve(SecretRef ref) {
        Reader r = reader.get();
        if (r == null) throw new IllegalStateException("No key service in this install");
        try {
            String value = r.read(MOUNT, PREFIX + ref.value());
            return value == null || value.isEmpty() ? Optional.empty() : Optional.of(Secret.of(value));
        } catch (CryptoException ex) {
            if (ex.code() == CryptoError.CRYPTO_SECRET_MISSING) return Optional.empty();
            throw new IllegalStateException("Key service did not answer: " + ex.code());
        }
    }
}
